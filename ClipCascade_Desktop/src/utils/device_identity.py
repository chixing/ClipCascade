"""Persist an installation ID without reading or rewriting account settings."""

import os
import platform
import re
import tempfile
import threading
import uuid
from pathlib import Path

_IDENTITY_LOCK = threading.Lock()
_IDENTITY_PATTERN = re.compile(r"desktop-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")


def get_device_info(config):
    """Return the same identity for STOMP, P2P, and future app launches."""
    path = Path(os.fspath(config.file_name) + ".device-id")
    with _IDENTITY_LOCK:
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(
                    mode="w", encoding="utf-8", dir=path.parent,
                    prefix=path.name + ".", delete=False,
                ) as output:
                    temporary = Path(output.name)
                    output.write("desktop-" + str(uuid.uuid4()) + "\n")
                    output.flush()
                    os.fsync(output.fileno())
                try:
                    # Atomic first-writer publication also handles separate processes.
                    os.link(temporary, path)
                except FileExistsError:
                    pass
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        with path.open(encoding="utf-8") as stored:
            device_id = stored.read(65).strip()
        if not _IDENTITY_PATTERN.fullmatch(device_id):
            raise ValueError("Stored installation device ID is invalid")

    system = platform.system()
    os_info = "macOS" if system == "Darwin" else system
    return {"deviceId": device_id, "deviceType": "desktop", "osInfo": os_info}
