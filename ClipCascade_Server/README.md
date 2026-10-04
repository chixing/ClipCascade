# ClipCascade Server (Homelab Deployment)

This directory contains the customized Spring Boot backend and Docker Compose deployment configuration for the homelab ClipCascade server.

## Features & Customizations

### 1. Persistent Session Storage (Spring Session JDBC)
* Authentication sessions are stored in the persistent embedded H2 database (`SPRING_SESSION` table).
* Configured with a custom `CookieSerializer` bean (`JSESSIONID`) ensuring 100% compatibility with unmodified stock upstream desktop (`v3.2.0`) and mobile (`v3.2.0`) clients.
* Rebuilding or restarting the server container preserves authenticated sessions without requiring devices to re-login.

### 2. Client IP Resolution & Host Networking
* The server container runs with `network_mode: host` listening on port `8086`.
* WebSocket handshake interceptors in `StompWebSocketConfig` and `P2PWebSocketConfig` extract reverse-proxy headers (`X-Forwarded-For`, `X-Real-IP`) via `ServletServerHttpRequest`.
* Traefik passes client IPs via `forwardedHeaders.insecure=true`.
* Device cards on the admin dashboard (`https://clipcascade.lab/admin/dashboard`) accurately record and display authentic client Tailscale/LAN IP addresses (`100.x.x.x` / `192.168.x.x`).

### 3. Historical Database
* Configured volume `./cc_users:/database` with database URL `jdbc:h2:file:/database/clipcascade`.
* Retains clipboard history across container recreation.
* Database files, credentials and backups are private runtime data. Keep them outside Git and the Docker build context.

### 4. Thread-Safe Device Registration
* Persistent client IDs distinguish devices sharing an IP. Stock clients without an ID retain the existing username/IP/device-type fallback.
* Overlapping connections and browser tabs share a device record; an older disconnect does not mark a replacement connection offline.
* Device names are rendered as text and can safely include quotes.

### 5. Private Previews and Optional History Cleanup
* Clipboard previews and downloads send `Cache-Control: private, no-store`.
* Anonymous `/health` and `/ping` checks do not create cookies or stored sessions. Authenticated sessions remain persistent.
* Pin clips with the dashboard star and filter by pinned status. Age/device cleanup keeps pinned clips; explicit item/selected deletion can still remove them after confirmation.
* `CC_HISTORY_RETENTION_DAYS=0` is the default and keeps all history. Set a positive number of days in the server environment to opt into automatic cleanup. Negative values reject startup.
* Cleanup runs one minute after startup, then once a day, deleting at most 10,000 expired unpinned clips per run in batches of 500. Existing history starts unpinned; the additive schema update preserves every existing payload.

## Directory Structure
* `ClipCascade_Backend/`: Spring Boot (Java 21) backend application.
* `docker-compose/`: Docker Compose configuration; `./cc_users/` is ignored runtime storage.

## Tests and packaged versions

The server workflow runs Java 21 tests against disposable in-memory databases, tests the actual dashboard script with Node, builds the Docker image, and checks its health and login routes. Regression coverage includes login sessions, ownership, device reconnects, image formats, pinning, cleanup, and repeatable legacy-schema upgrades. Docker builds also run the Java tests. Production databases and credentials are never needed for these checks.

Successful builds of this fork's `main` branch publish an AMD64 server image at `ghcr.io/chixing/clipcascade:sha-<full-commit-id>` and `ghcr.io/chixing/clipcascade:latest`. Use the commit tag for a reproducible deployment. Publishing an image does not deploy it to the homelab; the Ansible rebuild procedure still controls that step.
