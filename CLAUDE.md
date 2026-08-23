# ClipCascade - Developer & Homelab Guide

## Repository Overview
* **Server (Backend):** Java 21 / Spring Boot 3 (`ClipCascade_Server/ClipCascade_Backend`)
* **Desktop Client:** Python 3 / STOMP (`ClipCascade_Desktop`)
* **Mobile Client:** React Native (`ClipCascade_Mobile`)
* **Upstream:** `https://github.com/Sathvik-Rao/ClipCascade.git`
* **Fork:** `https://github.com/chixing/ClipCascade.git` (branch `main`)

---

## Homelab Deployment Architecture
* **Live Service URL:** `http://clipcascade.lab` (or `https://clipcascade.lab`)
* **Target Node:** `lab` (LXC 104 on Proxmox, Tailscale IP `100.121.129.40`)
* **Ansible Control Plane:** `admin` (LXC 105, Tailscale IP `100.110.122.74` / `admin.tail212d0.ts.net`)
* **Declared State Location:** `/opt/ansible/site.yml` on `admin`
* **Deployment Source:** Pulls from `https://github.com/chixing/ClipCascade.git` on branch `main` and builds from source using multi-stage Docker (`clipcascade:local`).

---

## How to Deploy Changes
When the user asks to deploy ClipCascade:

1. **Commit and Push changes to GitHub:**
   ```bash
   git add .
   git commit -m "Your descriptive commit message"
   git push origin main
   ```

2. **Trigger Ansible Deployment on `admin`:**
   ```bash
   ssh -i ~/.ssh/id_ed25519_ai -o StrictHostKeyChecking=no root@100.110.122.74 "cd /opt/ansible && ansible-playbook -i inventory.ini site.yml --limit docker_apps"
   ```

3. **Verify Deployment:**
   ```bash
   ssh -i ~/.ssh/id_ed25519_ai -o StrictHostKeyChecking=no root@100.110.122.74 "curl -sI http://clipcascade.lab/login"
   ```

---

## Local Build Commands
* **Compile Backend:**
  ```powershell
  mise exec -- cmd.exe /c "mvnw.cmd compile"
  ```
* **Package JAR:**
  ```powershell
  mise exec -- cmd.exe /c "mvnw.cmd package -DskipTests"
  ```
