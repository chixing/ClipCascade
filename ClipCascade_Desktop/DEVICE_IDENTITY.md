Desktop clients send a random persistent installation ID so distinct devices sharing
an IP remain distinct and reconnects retain their identity. Desktop STOMP headers
and clipboard metadata include this ID and the OS family. P2P supplies it through
the `X-ClipCascade-Device-Id` handshake header. Older servers can ignore this
additional metadata.

The desktop ID is created atomically in `DATA.device-id`, beside the existing
configuration file, with private file permissions. On macOS that directory remains
`~/Library/Application Support/ClipCascade`. Creating the ID does not read or
rewrite `DATA`, credentials, cookies, encryption settings or autostart settings.
Keep this small sidecar when updating the app. Do not copy it to another device.
Deleting app data creates a new installation identity; old history is retained.

The Android client remains identical to upstream. Persistent installation IDs in
this fork apply to desktop clients. The server continues accepting stock clients.

The updated server preserves existing owned device records and legacy IP fallback
records. New installation IDs start new device entries; older clipboard history
remains attached to its original entry. IP fallback remains for older clients.
It cannot reliably distinguish older clients sharing the same address and type.

Source changes take effect only after installing a rebuilt desktop app. The client
workflow runs dependency-free Python regression tests; it does not publish signed
desktop releases.

Run checks with `python3 -m unittest discover -s ClipCascade_Desktop/tests -v`
from the repository root.
