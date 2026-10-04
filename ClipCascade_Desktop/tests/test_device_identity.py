import ast
import concurrent.futures
import json
import logging
import os
import sys
import tempfile
import time
from threading import Event, Lock
import types
import unittest
from pathlib import Path
from unittest.mock import Mock

SOURCE = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SOURCE))
from utils.device_identity import get_device_info
from stomp_ws.frame import Frame


def transport_method(path, name, namespace):
    """Run the real transport method with UI/network dependencies replaced."""
    source = ast.parse((SOURCE / path).read_text())
    manager = next(node for node in source.body if isinstance(node, ast.ClassDef))
    method = next(node for node in manager.body if isinstance(node, ast.FunctionDef) and node.name == name)
    module = ast.Module(body=[method], type_ignores=[])
    exec(compile(module, str(SOURCE / path), "exec"), namespace)
    return namespace[name]


class DeviceIdentityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.config = types.SimpleNamespace(file_name=str(Path(self.temporary.name) / "DATA"))

    def test_restart_reuses_identity_without_reading_or_changing_config(self):
        account_settings = Path(self.config.file_name)
        account_settings.write_bytes(b"synthetic opaque settings")
        before = account_settings.read_bytes()
        first = get_device_info(self.config)
        restarted = get_device_info(types.SimpleNamespace(file_name=self.config.file_name))
        self.assertEqual(first, restarted)
        self.assertEqual(account_settings.read_bytes(), before)
        self.assertEqual(first["deviceType"], "desktop")
        self.assertLessEqual(len(first["deviceId"]), 64)
        if os.name != "nt":
            self.assertEqual(Path(self.config.file_name + ".device-id").stat().st_mode & 0o777, 0o600)

    def test_simultaneous_transports_reuse_one_persisted_id(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            identities = list(pool.map(lambda _: get_device_info(self.config)["deviceId"], range(16)))
        self.assertEqual(len(set(identities)), 1)
        self.assertEqual(len(list(Path(self.temporary.name).iterdir())), 1)

    def test_separate_installations_do_not_share_an_id(self):
        other = types.SimpleNamespace(file_name=str(Path(self.temporary.name) / "other" / "DATA"))
        self.assertNotEqual(get_device_info(self.config)["deviceId"], get_device_info(other)["deviceId"])

    def test_invalid_stored_id_fails_without_replacing_it(self):
        path = Path(self.config.file_name + ".device-id")
        path.write_text("invalid identity")
        with self.assertRaisesRegex(ValueError, "Stored installation device ID is invalid"):
            get_device_info(self.config)
        self.assertEqual(path.read_text(), "invalid identity")

    def test_stomp_connect_and_clipboard_metadata_use_the_persisted_identity(self):
        config = self.config
        config.data = {"websocket_url": "wss://example.invalid/clipsocket", "cookie": None, "cipher_enabled": False}
        client = Mock()
        namespace = {
            "Client": Mock(return_value=client), "get_device_info": get_device_info,
            "RequestManager": types.SimpleNamespace(format_cookie=lambda _: ""),
            "websocket_sslopt_for_config": lambda _: {}, "WEBSOCKET_TIMEOUT": 3000,
            "SUBSCRIPTION_DESTINATION": "/user/queue/cliptext", "SEND_DESTINATION": "/app/cliptext",
            "logging": logging, "json": json,
        }
        connect = transport_method("stomp_ws/stomp_manager.py", "connect", namespace)
        send = transport_method("stomp_ws/stomp_manager.py", "send", namespace)
        manager = types.SimpleNamespace(
            config=config, is_connected=False, disconnected=False, first_conn_lost=True,
            clipboard_manager=Mock(), notification_manager=Mock(),
            _on_close=lambda *_: None, send=lambda _: None, _receive=lambda _: None,
            _connect_lock=Lock(),
        )
        success, _ = connect(manager)
        self.assertTrue(success)
        expected = get_device_info(config)
        self.assertEqual(client.connect.call_args.kwargs["headers"], expected)
        send(manager, "synthetic clipboard")
        body = json.loads(client.send.call_args.kwargs["body"])
        self.assertEqual(body["metadata"], expected)
        self.assertEqual(body["payload"], "synthetic clipboard")

    def test_custom_stomp_client_serializes_the_device_headers_on_the_wire(self):
        namespace = {"logging": logging, "time": time, "VERSIONS": "1.0,1.1", "Frame": Frame}
        connect = transport_method("stomp_ws/client.py", "connect", namespace)
        transmit = transport_method("stomp_ws/client.py", "_transmit", namespace)
        client = types.SimpleNamespace(url="wss://example.invalid/clipsocket", _connect=Mock(), ws=Mock(),
                                       connected=True, _connected_event=Event())
        client._connected_event.set()
        client._transmit = types.MethodType(transmit, client)
        expected = get_device_info(self.config)

        connect(client, headers=expected.copy())

        sent = Frame.unmarshall_single(client.ws.send.call_args.args[0])
        self.assertEqual(sent.command, "CONNECT")
        self.assertEqual(sent.headers["deviceId"], expected["deviceId"])
        self.assertEqual(sent.headers["deviceType"], "desktop")
        self.assertEqual(sent.headers["accept-version"], "1.0,1.1")
        self.assertEqual(sent.headers["heart-beat"], "0,20000")

    def test_p2p_handshake_uses_the_same_identity_without_changing_cookie(self):
        config = self.config
        config.data = {"websocket_url": "wss://example.invalid/p2psignaling", "cookie": None}
        socket = Mock()
        websocket = types.SimpleNamespace(WebSocketApp=Mock(return_value=socket))
        namespace = {
            "websocket": websocket, "get_device_info": get_device_info,
            "RequestManager": types.SimpleNamespace(format_cookie=lambda _: "synthetic-cookie"),
            "websocket_sslopt_for_config": lambda _: {}, "Thread": Mock(),
            "P2P_WS_PING_INTERVAL_SEC": 25, "P2P_WS_PING_TIMEOUT_SEC": 20,
            "logging": logging,
        }
        connect = transport_method("p2p/p2p_manager.py", "connect", namespace)
        manager = types.SimpleNamespace(
            config=config, ws_client=None, is_connected=False, is_login_phase=False,
            is_clipboard_monitoring_on=False, clipboard_manager=Mock(), send=lambda _: None,
            _on_ws_open=lambda _: None, _on_ws_error=lambda *_: None,
            _on_ws_message=lambda *_: None, _on_ws_close=lambda *_: None,
        )
        success, _ = connect(manager)
        self.assertTrue(success)
        header = websocket.WebSocketApp.call_args.kwargs["header"]
        self.assertEqual(header["X-ClipCascade-Device-Id"], get_device_info(config)["deviceId"])
        self.assertEqual(header["Cookie"], "synthetic-cookie")


if __name__ == "__main__":
    unittest.main()
