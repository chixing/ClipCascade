import json
import logging
from threading import Event, Lock, Thread


from interfaces.ws_interface import WSInterface
from stomp_ws.client import Client
from core.config import Config
from utils.cipher_manager import CipherManager
from clipboard.clipboard_manager import ClipboardManager
from utils.notification_manager import NotificationManager
from utils.request_manager import RequestManager
from utils.ssl_helper import websocket_sslopt_for_config
from utils.device_identity import get_device_info
from core.constants import *

if PLATFORM.startswith(LINUX) and LINUX_USE_CLI_UI:
    from cli.tray import TaskbarPanel
else:
    from gui.tray import TaskbarPanel


class STOMPManager(WSInterface):
    def __init__(self, config: Config, is_login_phase=True):
        self.config = config
        self.clipboard_manager = ClipboardManager(self.config)
        self.cipher_manager = CipherManager(self.config)
        self.notification_manager = NotificationManager(self.config)
        self.sys_tray: TaskbarPanel = None
        self.first_conn_lost = True
        self.is_login_phase = is_login_phase
        self.client = None
        self.is_connected = False
        self.disconnected = False
        self.is_auto_reconnecting = False
        self._connect_lock = Lock()
        self._reconnect_lock = Lock()
        self._reconnect_stop = Event()
        self._reconnect_thread = None

    def set_tray_ref(self, sys_tray: TaskbarPanel):
        """
        Sets the system tray reference.
        """
        self.sys_tray = sys_tray
        self.clipboard_manager.set_tray_ref(sys_tray)

    def get_total_timeout(self):
        """
        Returns the total timeout value in milliseconds."""
        return (RECONNECT_WS_TIMER * 1000) + WEBSOCKET_TIMEOUT

    def get_stats(self):
        return None

    def connect(self) -> tuple[bool, str]:
        # Manual connects and the retry worker must not open competing sockets.
        with self._connect_lock:
            if self.is_connected:
                return True, ""
            if self.disconnected:
                return False, "Websocket disconnected"
            client = None
            try:
                client = Client(
                    self.config.data["websocket_url"],
                    headers={"Cookie": RequestManager.format_cookie(self.config.data["cookie"])},
                    sslopt=websocket_sslopt_for_config(self.config),
                )
                self.client = client
                client.on_close_callback = lambda: self._on_close(client)
                client.connect(
                    headers=get_device_info(self.config),
                    timeout=WEBSOCKET_TIMEOUT,
                    connectCallback=lambda _: client.subscribe(
                        destination=SUBSCRIPTION_DESTINATION,
                        callback=lambda frame: self._receive(frame) if client is self.client else None,
                    ),
                )
                if self.disconnected or not client.connected:
                    client.disconnect()
                    return False, "Websocket disconnected"
                self.is_connected = True
                self.is_auto_reconnecting = False
                if not self.first_conn_lost:
                    self.first_conn_lost = True
                    self.notification_manager.notify(
                        title=f"{APP_NAME}: WebSocket Connection Restored 🔗",
                        message="Connection re-established",
                    )
                self.clipboard_manager.on_copy(self.send)
                return True, "Websocket connected"
            except Exception as e:
                self.is_connected = False
                if client is not None:
                    client.disconnect()
                msg = f"Failed to connect websocket: {e}"
                logging.error(msg)
                return False, msg

    def _on_close(self, client=None):
        with self._connect_lock:
            if client is not None and client is not self.client:
                return  # A previous socket must not disconnect its replacement.
            self.is_connected = False
        if not self.is_login_phase and not self.disconnected:
            if self.first_conn_lost:
                self.first_conn_lost = False
                self.notification_manager.notify(
                    title=f"{APP_NAME}: WebSocket Connection Lost ⛓️‍💥",
                    message="Check your internet connection. Retrying...",
                )
            self._start_reconnecting()

    def _start_reconnecting(self):
        with self._reconnect_lock:
            if self.disconnected:
                return
            if self._reconnect_thread is not None and self._reconnect_thread.is_alive():
                return
            self.is_auto_reconnecting = True
            self._reconnect_stop.clear()
            self._reconnect_thread = Thread(target=self._reconnect, daemon=True)
            self._reconnect_thread.start()

    def _reconnect(self):
        try:
            while not self._reconnect_stop.wait(RECONNECT_WS_TIMER):
                if self.disconnected:
                    return
                success, _ = self.connect()
                if success:
                    return
        finally:
            with self._reconnect_lock:
                self.is_auto_reconnecting = False
                self._reconnect_thread = None
                # A socket may close just as the successful retry exits.
                retry_again = not self.disconnected and not self.is_connected
            if retry_again:
                self._start_reconnecting()

    def send(self, payload: str, payload_type: str = "text"):
        try:
            if self.is_connected:
                if self.clipboard_manager.has_clipboard_changed(payload):
                    if self.config.data["cipher_enabled"]:
                        payload = CipherManager.encode_to_json_string(
                            **self.cipher_manager.encrypt(payload)
                        )
                    body = json.dumps({
                        "payload": payload,
                        "type": payload_type,
                        "metadata": get_device_info(self.config),
                    })
                    self.client.send(destination=SEND_DESTINATION, body=body)
        except Exception as e:
            logging.error(f"Failed to send data: {e}")

    def _receive(self, frame: any) -> str:
        try:
            if self.is_connected:
                body = json.loads(frame.body)
                payload = body["payload"]
                payload_type = body.get("type", "text")
                if self.config.data["cipher_enabled"]:
                    payload = self.cipher_manager.decrypt(
                        **CipherManager.decode_from_json_string(payload)
                    )

                if self.clipboard_manager.has_clipboard_changed(payload):
                    self.clipboard_manager.base64_to_clipboard(
                        base64_string=payload, type_=payload_type
                    )
        except json.decoder.JSONDecodeError:
            logging.error(
                "If cipher is enabled, please make sure it is enabled on all devices"
            )
        except Exception as e:
            logging.error(f"Failed to receive data: {e}")

    def manual_reconnect(self):
        self.disconnected = False
        success, _ = self.connect()
        if not success and not self.is_login_phase:
            self._start_reconnecting()

    def disconnect(self):
        try:
            self.clipboard_manager.previous_clipboard_hash = 0
            self.disconnected = True
            self._reconnect_stop.set()
            self.is_auto_reconnecting = False
            self.is_connected = False
            self.first_conn_lost = True
            try:
                self.client.disconnect()
                self.is_connected = False
                logging.info("Websocket disconnected")
            except Exception as e:
                pass  # silent catch
            self.clipboard_manager.stop()
        except Exception as e:
            logging.error(f"Failed to disconnect websocket: {e}")
