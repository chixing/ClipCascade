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
* `DeviceService.registerDevice()` is synchronized and includes database fallback handling to prevent race conditions during concurrent WebSocket connections.

## Directory Structure
* `ClipCascade_Backend/`: Spring Boot (Java 21) backend application.
* `docker-compose/`: Docker Compose configuration; `./cc_users/` is ignored runtime storage.

## Tests and packaged versions

The server workflow runs Java 21 tests against a disposable in-memory database, builds the Docker image, and checks its health and login routes. Docker builds also run the tests. Production databases and credentials are never needed for these checks.

Successful builds of this fork's `main` branch publish an AMD64 server image at `ghcr.io/chixing/clipcascade:sha-<full-commit-id>` and `ghcr.io/chixing/clipcascade:latest`. Use the commit tag for a reproducible deployment. Publishing an image does not deploy it to the homelab; the Ansible rebuild procedure still controls that step.
