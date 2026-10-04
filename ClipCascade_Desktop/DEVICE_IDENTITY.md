Native clients send a random persistent installation ID so distinct devices sharing
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

Android stores its installation ID in AsyncStorage under `clipcascade_device_id`.
STOMP headers and clipboard metadata include it; P2P uses the `deviceId` URL query.
App updates and logout retain it. Clearing app data or reinstalling creates a new
identity. No hardware IDs, hostnames or account information are used to generate it.

The updated server preserves existing owned device records and legacy IP fallback
records. New installation IDs start new device entries; older clipboard history
remains attached to its original entry. IP fallback remains for older clients.
It cannot reliably distinguish older clients sharing the same address and type.

Source changes take effect only after installing a rebuilt desktop app or Android
APK. The client workflow runs dependency-free Python and Node regression tests;
it does not publish signed desktop/mobile releases. The mobile foreground service
is Android-specific; this change does not add iOS clipboard support.

Run checks with `python3 -m unittest discover -s ClipCascade_Desktop/tests -v`
and `node --test ClipCascade_Mobile/tests/*.test.cjs` from the repository root.
