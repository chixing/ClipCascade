"""Exercise the real desktop transport against a synthetic WebSocket server.

UI/clipboard dependencies are replaced; no account settings or clipboard is read.
"""
import ast
import json
import uuid
import logging
import random
import threading
import sys
import tempfile
import time
import types
import unittest
from pathlib import Path
from threading import Event, Lock, Thread
from unittest.mock import Mock

SOURCE = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SOURCE))
from stomp_ws.frame import Frame
from utils.device_identity import get_device_info
from utils import binary_protocol as binary
from utils.relay_recipients import RelayRecipients
from utils.clipboard_echoes import ClipboardEchoes
from utils.transfer_limits import *


def load_class(path, name, namespace):
    source = ast.parse((SOURCE / path).read_text(encoding="utf-8"))
    node = next(node for node in source.body if isinstance(node, ast.ClassDef) and node.name == name)
    exec(compile(ast.Module(body=[node], type_ignores=[]), str(SOURCE / path), "exec"), namespace)
    return namespace[name]


class Socket:
    def __init__(self, url, headers, scenario="success"):
        self.url, self.headers, self.scenario = url, headers, scenario
        self.closed = Event()
        self.frames = []
        self.on_close = None

    def run_forever(self, **kwargs):
        if self.scenario == "opening_timeout":
            self.closed.wait(1)
        elif self.scenario == "refused":
            self.on_error(self, ConnectionError("synthetic outage"))
        else:
            self.on_open(self)
            self.closed.wait(1)
        if self.on_close is not None:
            self.on_close(self)

    def send(self, wire):
        frame = Frame.unmarshall_single(wire)
        self.frames.append(frame)
        if frame.command == "CONNECT":
            if self.scenario == "success":
                self.on_message(self, "CONNECTED\nversion:1.1\n\n\x00")
            elif self.scenario == "rejected":
                self.on_message(self, "ERROR\nmessage:synthetic rejection\n\n\x00")

    def close(self):
        self.closed.set()
        if self.on_close is not None:
            self.on_close(self)  # Also exercise synchronous close notification.


class ReconnectTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.config = types.SimpleNamespace(file_name=str(Path(self.temp.name) / "DATA"), data={
            "websocket_url": "ws://synthetic.invalid/clipsocket", "cookie": "synthetic opaque session",
        })
        self.scenarios = []
        self.sockets = []
        self.namespace = {
            "RelayRecipients": RelayRecipients, "ClipboardEchoes": ClipboardEchoes,
            "receive_limit_header": receive_limit_header, "LIMIT_HEADER": LIMIT_HEADER,
            "json": json, "uuid": uuid, "binary": binary,
            "valid_size": valid_size, "content_size": content_size, "SIZE_FIELD": SIZE_FIELD,
            "SEND_DESTINATION": "/app/cliptext",
            "Event": Event, "Lock": Lock, "Thread": Thread, "time": time, "logging": logging,
            "threading": threading, "random": random, "RECONNECT_WS_JITTER_RATIO": 0,
            "Frame": Frame, "VERSIONS": "1.0,1.1", "websocket": types.SimpleNamespace(WebSocketApp=self.socket),
            "WSInterface": object, "Config": object, "TaskbarPanel": object,
            "ClipboardManager": lambda _: Mock(), "CipherManager": lambda _: Mock(),
            "NotificationManager": lambda _: Mock(),
            "RequestManager": types.SimpleNamespace(format_cookie=lambda cookie: cookie),
            "websocket_sslopt_for_config": lambda _: {}, "get_device_info": get_device_info,
            "RECONNECT_WS_TIMER": .01, "WEBSOCKET_TIMEOUT": 60, "APP_NAME": "ClipCascade",
            "SUBSCRIPTION_DESTINATION": "/user/queue/cliptext",
        }
        self.Client = load_class("stomp_ws/client.py", "Client", self.namespace)
        self.namespace["ReconnectMixin"] = load_class("utils/reconnect.py", "ReconnectMixin", self.namespace)
        self.Manager = load_class("stomp_ws/stomp_manager.py", "STOMPManager", self.namespace)
        self.manager = self.Manager(self.config, is_login_phase=False)
        self.addCleanup(self.manager.disconnect)

    def socket(self, url, headers):
        socket = Socket(url, headers, self.scenarios.pop(0) if self.scenarios else "success")
        self.sockets.append(socket)
        return socket

    def wait_for(self, predicate):
        deadline = time.monotonic() + 6
        while not predicate() and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertTrue(predicate(), "transport state did not settle")

    def test_recovers_after_multiple_failed_retries_and_reuses_session_and_identity(self):
        Path(self.config.file_name).write_bytes(b"synthetic settings remain unchanged")
        self.assertTrue(self.manager.connect()[0])
        first_id = self.sockets[0].frames[0].headers["deviceId"]
        self.scenarios[:] = ["refused", "opening_timeout", "no_handshake", "success"]
        self.sockets[0].close()
        self.wait_for(lambda: len(self.sockets) == 5 and self.manager.is_connected)
        self.wait_for(lambda: self.manager._retry_thread is None)
        self.assertFalse(self.manager.is_auto_reconnecting)
        self.assertEqual(self.manager.clipboard_manager.on_copy.call_count, 2)
        self.assertEqual(self.sockets[-1].frames[0].headers["deviceId"], first_id)
        self.assertEqual(self.sockets[-1].frames[1].command, "SUBSCRIBE")
        self.assertTrue(all(socket.headers["Cookie"] == "synthetic opaque session" for socket in self.sockets))
        self.assertEqual(Path(self.config.file_name).read_bytes(), b"synthetic settings remain unchanged")
        titles = [call.kwargs["title"] for call in self.manager.notification_manager.notify.call_args_list]
        self.assertEqual(sum("Lost" in title for title in titles), 1)
        self.assertEqual(sum("Restored" in title for title in titles), 1)

    def test_disconnect_cancels_retry_without_waiting_for_the_timer(self):
        self.namespace["RECONNECT_WS_TIMER"] = 30
        self.assertTrue(self.manager.connect()[0])
        self.sockets[0].close()
        self.wait_for(lambda: self.manager.is_auto_reconnecting)
        self.manager.disconnect()
        self.wait_for(lambda: self.manager._retry_thread is None)
        count_before_manual = len(self.sockets)
        self.assertFalse(self.manager.is_auto_reconnecting)
        self.assertFalse(self.manager.is_connected)
        self.manager.manual_reconnect()
        self.wait_for(lambda: self.manager.is_connected)
        self.assertEqual(len(self.sockets), count_before_manual + 1)

    def test_manual_connect_failure_also_keeps_retrying(self):
        self.manager.disconnect()
        self.scenarios[:] = ["opening_timeout", "refused", "success"]
        self.manager.manual_reconnect()
        self.wait_for(lambda: len(self.sockets) == 3 and self.manager.is_connected)

    def test_late_close_from_old_socket_does_not_disconnect_replacement(self):
        self.assertTrue(self.manager.connect()[0])
        previous = self.manager.client
        self.sockets[0].close()
        self.wait_for(lambda: len(self.sockets) == 2 and self.manager.is_connected)
        self.manager._on_close(previous)
        self.assertTrue(self.manager.is_connected)

    def test_disconnect_during_handshake_then_manual_connect(self):
        self.namespace["WEBSOCKET_TIMEOUT"] = 1000
        self.scenarios[:] = ["no_handshake", "success"]
        result = []
        connecting = Thread(target=lambda: result.append(self.manager.connect()[0]))
        connecting.start()
        self.wait_for(lambda: len(self.sockets) == 1 and bool(self.sockets[0].frames))
        self.manager.disconnect()
        self.manager.manual_reconnect()
        connecting.join(1)
        self.assertFalse(connecting.is_alive())
        self.assertEqual(result, [False])
        self.wait_for(lambda: self.manager.is_connected)
        self.assertEqual(len(self.sockets), 2)

    def test_replaced_socket_cannot_deliver_clipboard_messages(self):
        self.assertTrue(self.manager.connect()[0])
        previous = self.manager.client
        self.sockets[0].close()
        self.wait_for(lambda: len(self.sockets) == 2 and self.manager.is_connected)
        self.manager._receive = Mock()
        message = "MESSAGE\nsubscription:sub-0\nmessage-id:synthetic\n\n{}\x00"
        previous._on_message(previous.ws, message)
        self.manager._receive.assert_not_called()
        self.manager.client._on_message(self.manager.client.ws, message)
        self.manager._receive.assert_called_once()

    def test_login_failure_does_not_start_background_retries(self):
        self.manager.is_login_phase = True
        self.scenarios[:] = ["refused"]
        self.assertFalse(self.manager.connect()[0])
        self.assertFalse(self.manager.is_auto_reconnecting)
        self.assertIsNone(self.manager._retry_thread)

    def test_open_socket_without_stomp_ack_is_not_a_successful_connection(self):
        self.manager.is_login_phase = True
        for scenario in ["no_handshake", "rejected"]:
            with self.subTest(scenario=scenario):
                self.scenarios[:] = [scenario]
                self.assertFalse(self.manager.connect()[0])
                self.assertFalse(self.manager.is_connected)
                self.assertFalse(self.manager.client.connected)
                self.assertTrue(self.sockets[-1].closed.is_set())


class TransferTests(unittest.TestCase):
    setUp = ReconnectTests.setUp
    socket = ReconnectTests.socket

    def prepare_transfer(self, plan):
        self.manager.is_connected = True
        self.config.data["cipher_enabled"] = False
        self.manager.client = Mock()
        self.manager.clipboard_manager.hash_clipboard.side_effect = lambda payload, kind: payload
        self.manager.clipboard_manager.previous_clipboard_hash = None
        self.manager._relay_recipients.get = lambda: plan
        return self.manager.client

    def test_mixed_recipients_share_identity_size_and_transfer_id(self):
        client = self.prepare_transfer({True: ["new"], False: ["old"]})
        self.manager.send("héllo")
        calls = client.send.call_args_list
        self.assertEqual(len(calls), 2)
        modern = binary.decode_frame(calls[0].kwargs["body"])
        legacy = json.loads(calls[1].kwargs["body"])
        self.assertEqual(modern["metadata"], legacy["metadata"])
        metadata = legacy["metadata"]
        self.assertEqual(metadata["deviceId"], get_device_info(self.config)["deviceId"])
        self.assertEqual(metadata[SIZE_FIELD], 6)
        uuid.UUID(metadata["historyTransferId"])
        self.assertEqual(calls[0].kwargs["headers"]["x-clipcascade-recipients"], "new")
        self.assertEqual(calls[1].kwargs["headers"]["x-clipcascade-recipients"], "old")
        self.manager.send("next update")
        next_metadata = json.loads(client.send.call_args.kwargs["body"])["metadata"]
        self.assertNotEqual(metadata["historyTransferId"], next_metadata["historyTransferId"])

    def test_old_server_fallback_retains_identity(self):
        client = self.prepare_transfer(None)
        self.namespace["RequestManager"] = Mock(return_value=types.SimpleNamespace(binary_recipients=lambda: None))
        self.manager.send("legacy")
        client.send.assert_called_once()
        self.assertEqual(client.send.call_args.kwargs["headers"], {})
        body = json.loads(client.send.call_args.kwargs["body"])
        self.assertEqual(body["metadata"]["deviceId"], get_device_info(self.config)["deviceId"])

    def test_empty_recipient_snapshot_preserves_no_send_policy(self):
        client = self.prepare_transfer({True: [], False: []})
        self.manager.send("local only")
        client.send.assert_not_called()

    def test_cancel_or_reconnect_between_representations_stops_old_transfer(self):
        for interrupt in (self.manager._on_local_change,
                          lambda: setattr(self.manager, "client", Mock()),
                          lambda: setattr(self.manager, "is_connected", False)):
            client = self.prepare_transfer({True: ["new"], False: ["old"]})
            client.send.side_effect = lambda **_: interrupt()
            self.manager.send("cancel me")
            self.assertEqual(client.send.call_count, 1)
            self.assertIsNone(self.manager.clipboard_manager.previous_clipboard_hash)

    def test_cancelled_preparation_sends_nothing(self):
        client = self.prepare_transfer({True: ["new"], False: ["old"]})
        self.manager.send("cancelled", is_current=lambda: False)
        client.send.assert_not_called()

    def test_encrypted_representations_share_metadata_and_keep_echo_tracking(self):
        client = self.prepare_transfer({True: ["new"], False: ["old"]})
        self.config.data["cipher_enabled"] = True
        self.manager.cipher_manager.encrypt_bytes.return_value = {
            "nonce": b"n" * 12, "tag": b"t" * 16, "ciphertext": b"opaque binary"}
        self.manager.cipher_manager.encrypt.return_value = {
            "nonce": "legacy nonce", "tag": "legacy tag", "ciphertext": "opaque legacy"}
        self.namespace["CipherManager"] = types.SimpleNamespace(encode_to_json_string=lambda **envelope: json.dumps(envelope))
        self.manager.send("private content", content_size_bytes=15)
        calls = client.send.call_args_list
        self.assertEqual(len(calls), 2)
        modern = binary.decode_frame(calls[0].kwargs["body"])
        legacy = json.loads(calls[1].kwargs["body"])
        self.assertTrue(modern["encrypted"])
        self.assertEqual(modern["metadata"], legacy["metadata"])
        self.assertEqual(legacy["metadata"][SIZE_FIELD], 15)
        self.assertNotIn("private content", calls[1].kwargs["body"])
        self.assertTrue(self.manager._sent_echoes.consume(("text", "encrypted", "legacy nonce", "legacy tag")))
