# HomeFlow Desktop — Setup Guide

This guide covers installing the HomeFlow desktop app and (optionally) connecting it to
your self-hosted server.

The app supports two modes:

- **Local-only (Mode A):** Data is stored on this computer only. No server required.
  Useful for getting started or as a permanent single-device setup.
- **Server-connected (Mode B):** Data lives on your HomeFlow server and is accessible
  from any device (desktop + Android) that connects to the same server.

You can start in Mode A and move to Mode B later — local data can be uploaded to the
server when you connect.

---

## Installation

Download the installer for your operating system from this release.

### Windows (`HomeFlow-X.Y.Z.msi`)

Double-click the `.msi` file and follow the prompts.

> **SmartScreen warning:** The installer is currently unsigned. If Windows shows a
> "Windows protected your PC" dialog, click **More info → Run anyway** to proceed. You
> only need to do this once.
>
> **To avoid the dialog entirely**, remove the "downloaded from the internet" mark before
> running the installer — either right-click `HomeFlow-X.Y.Z.msi` → **Properties** → tick
> **Unblock** → **OK**, or run this in PowerShell from the download folder:
>
> ```powershell
> Unblock-File .\HomeFlow-X.Y.Z.msi
> ```
>
> Then double-click the `.msi` as usual — SmartScreen won't prompt. (This works because
> the warning is tied to the file's Mark-of-the-Web, which `Unblock-File` clears.)

### macOS (`HomeFlow-X.Y.Z.dmg`)

1. Open the `.dmg` file
2. Drag **HomeFlow** into your **Applications** folder
3. Launch it from Applications

> **Gatekeeper warning:** The app is currently unsigned. On first launch, macOS may say
> "HomeFlow can't be opened because it is from an unidentified developer." To open it:
> right-click the app in Finder and choose **Open**, then confirm in the dialog. You only
> need to do this once.

Alternatively, from the terminal:
```bash
xattr -d com.apple.quarantine /Applications/HomeFlow.app
```

### Linux (`homeflow_X.Y.Z_amd64.deb`)

```bash
sudo apt install ./homeflow_X.Y.Z_amd64.deb
```

Or open the `.deb` file with your distribution's software center.

---

## First launch

On first launch you'll be prompted to choose a mode.

**Local-only:** Tap **Use locally** to start immediately. Your data is stored in
`~/.homeflow/` on this machine. No server, no account, no network access needed.

**Connect to a server:** Tap **Connect to a server** and enter the hostname of your
HomeFlow server (e.g. `homeflow.<tailnet>.ts.net`). The app checks the server version,
then opens a browser window for login.

---

## Connecting to a server

Whether at first launch or later from Settings, connecting to a server follows the same
steps:

1. Go to **Settings → Server → Connect to a server**
2. Enter your server hostname — the `APP_HOSTNAME` value from your server's `.env` file
3. Tap **Connect**
4. Your system browser opens the Keycloak login page
5. Enter your username and password
6. **First login only:** Keycloak prompts you to register a passkey — save it to a
   password manager that supports passkeys (Bitwarden works well)
7. After login the browser closes and the app switches to server-connected mode

The hostname is saved automatically. You won't need to re-enter it unless you change
servers.

---

## Uploading local data when switching to server-connected mode (Mode A → B)

If you've accumulated data in local-only mode and now want to move it to your server:

1. Go to **Settings → Server → Connect to a server**
2. Enter your server hostname and tap **Connect**
3. Log in via the browser (or passkey if already registered)
4. The app detects your local data and shows an **Upload** prompt — tap **Upload**
5. A summary shows how many cycles and days were transferred

The upload is idempotent: re-running it is safe. Data already on the server is
preserved; duplicate entries are skipped.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| Can't reach the server | Confirm the server is running (`docker compose ps` on the Pi); if using Tailscale, confirm both devices are enrolled and MagicDNS is enabled |
| "Connection refused" or TLS error | The server hostname or TLS certificate is not trusted — verify `APP_HOSTNAME` matches what you're typing; for LAN setups, install the Caddy root CA on this machine (see `SETUP-SERVER.md` Step 7) |
| Login opens a browser but immediately shows an error | Hostname mismatch between what you entered and the server's `APP_HOSTNAME` — copy-paste the hostname rather than retyping |
| "Invalid credential" on passkey | The server hostname changed since you registered the passkey — re-register via the Keycloak admin on the Pi |
| Every API request fails with an auth error after login | The server's `PUBLIC_KEYCLOAK_URL` is misconfigured — check Step 3 of `SETUP-SERVER.md` |
| "App version incompatible with server" | Update the desktop app or the server to compatible versions; check the compatibility table in the release notes |
| macOS: app won't open at all, even after right-click Open | Run `xattr -d com.apple.quarantine /Applications/HomeFlow.app` in Terminal |
