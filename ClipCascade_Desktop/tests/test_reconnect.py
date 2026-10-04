"""Exercise the real desktop transport against a synthetic WebSocket server.

UI/clipboard dependencies are replaced; no account settings or clipboard is read.
"""
import ast
import logging
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


def load_class(path, name, namespace):
    source = ast.parse((SOURCE / path).read_text())
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
            "Event": Event, "Lock": Lock, "Thread": Thread, "time": time, "logging": logging,
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
        self.Manager = load_class("stomp_ws/stomp_manager.py", "STOMPManager", self.namespace)
        self.manager = self.Manager(self.config, is_login_phase=False)
        self.addCleanup(self.manager.disconnect)

    def socket(self, url, headers):
        socket = Socket(url, headers, self.scenarios.pop(0) if self.scenarios else "success")
        self.sockets.append(socket)
        return socket

    def wait_for(self, predicate):
        deadline = time.monotonic() + 2
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
        self.wait_for(lambda: self.manager._reconnect_thread is None)
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
        self.wait_for(lambda: self.manager._reconnect_thread is None)
        self.assertEqual(len(self.sockets), 1)
        self.assertFalse(self.manager.is_auto_reconnecting)
        self.assertFalse(self.manager.is_connected)
        self.manager.manual_reconnect()
        self.assertTrue(self.manager.is_connected)
        self.assertEqual(len(self.sockets), 2)

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
        self.assertTrue(self.manager.is_connected)
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
        self.assertIsNone(self.manager._reconnect_thread)

    def test_open_socket_without_stomp_ack_is_not_a_successful_connection(self):
        self.manager.is_login_phase = True
        for scenario in ["no_handshake", "rejected"]:
            with self.subTest(scenario=scenario):
                self.scenarios[:] = [scenario]
                self.assertFalse(self.manager.connect()[0])
                self.assertFalse(self.manager.is_connected)
                self.assertFalse(self.manager.client.connected)
                self.assertTrue(self.sockets[-1].closed.is_set())
