# HomeFlow Android — Setup Guide

This guide covers installing the HomeFlow Android app and (optionally) connecting it to
your self-hosted server.

The app supports two modes:

- **Local-only (Mode A):** Data is stored on this device only. No server required.
- **Server-connected (Mode B):** Data lives on your HomeFlow server and is accessible
  from any device (desktop + Android) that connects to the same server.

You can start in Mode A and move to Mode B later — local data can be uploaded to the
server when you connect.

---

## Requirements

- Android 8.0 (API 26) or newer
- Chrome installed (used for the login browser flow)
- For server-connected mode: a running HomeFlow server reachable from this device

---

## Installation

HomeFlow is distributed as a sideloaded APK — it is not on the Play Store.

### Step 1 — Download the APK

Download `homeflow-android-X.Y.Z.apk` to your Android device. Options:
- Open a browser on the device and download directly from this release page
- Transfer via Tailscale's file sharing, a cable, or cloud storage

### Step 2 — Allow installation from unknown sources

Android requires you to allow your file manager or browser to install apps from outside
the Play Store. This permission is **per-app** on Android 8+:

1. Tap the downloaded `.apk` file to begin installation
2. If Android shows "For your security, your phone is not allowed to install unknown
   apps from this source", tap **Settings**
3. Toggle **Allow from this source** on
4. Tap the back button, then **Install**

You can revoke this permission after installation.

### Step 3 — Install and launch

After installation, tap **Open** or find **HomeFlow** in your app drawer.

---

## First launch

On first launch you'll be prompted to choose a mode.

**Local-only:** Tap **Use locally** to start immediately. Data is stored encrypted on
this device. No server, no account, no network access needed.

**Connect to a server:** Tap **Connect to a server** and enter the hostname of your
HomeFlow server (e.g. `homeflow.<tailnet>.ts.net`). The app checks compatibility, then
opens a login page.

---

## Connecting to a server

Whether at first launch or later from Settings, connecting to a server follows the same
steps:

1. Go to **Settings → Server → Connect to a server**
2. Enter your server hostname — the `APP_HOSTNAME` value from your server's `.env` file
3. Tap **Connect**
4. A Chrome Custom Tab opens showing the Keycloak login page
5. Enter your username and password
6. **First login only:** Keycloak prompts you to register a passkey — tap the prompt and
   save the passkey to your device or a compatible password manager
7. After login the Custom Tab closes and the app switches to server-connected mode

After first login, the app stores your session securely in the device Keystore. On
subsequent launches your session resumes silently (no password prompt). You'll only need
to log in again if your session is revoked on the server.

### Server with a private/self-signed certificate (LAN)

If your server uses a private certificate — a LAN `homeflow.lan` install behind Caddy's
internal CA — you must **install the server's CA certificate on the device** so both the
app and the login browser trust it:

1. Copy `caddy-root.crt` (exported by your server admin, `SETUP-SERVER.md` Step 7) to the
   phone.
2. **Settings → Security → Encryption & credentials → Install a certificate → CA
   certificate**, then select `caddy-root.crt`. Android warns that a third party could
   monitor traffic — expected for a self-installed CA.

The release app trusts a user-installed CA **only for `homeflow.lan`**; all other hostnames
require a publicly-trusted certificate (e.g. Tailscale). Your server's hostname must be
`homeflow.lan` for this to apply.

> **DNS:** the phone must resolve `homeflow.lan` via your router. If you use **Private DNS**
> (Settings → Network & internet → Private DNS), set it to **Off** or **Automatic**, or the
> hostname won't resolve on the LAN.

---

## Uploading local data when switching to server-connected mode (Mode A → B)

If you've accumulated data in local-only mode and now want to move it to your server:

1. Go to **Settings → Server → Connect to a server**
2. Enter your server hostname and tap **Connect**
3. Log in via the Keycloak screen
4. The app detects your local data and shows an **Upload** prompt — tap **Upload**
5. A summary shows how many cycles and days were transferred

The upload is idempotent: re-running it is safe. Data already on the server is
preserved; duplicate entries are skipped.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| "App not installed" during installation | Incomplete APK download — re-download and check the file size; also confirm unknown-sources is enabled for your browser or file manager |
| Can't reach the server | For a LAN server: install the server's CA (`caddy-root.crt`) on the device, ensure the hostname is `homeflow.lan`, and turn off Private DNS (see "Server with a private/self-signed certificate" above). For Tailscale: install Tailscale and join your tailnet |
| Login Custom Tab shows "invalid redirect_uri" | The `homeflow-android` Keycloak client's redirect URI doesn't match — verify the server was set up with the unmodified `realm-export.json` |
| Login succeeds but every API request returns 401 | The audience mapper for `homeflow-android` is missing in Keycloak — see `SETUP-SERVER.md` Step 6 and the Keycloak configuration guide |
| App requires re-login every launch | The `offline_access` scope isn't granted to `homeflow-android` in Keycloak — verify the client has `offline_access` assigned as a default scope |
| Passkey prompt fails or shows "invalid credential" | The WebAuthn RP ID on the server doesn't match your server hostname — check `SETUP-SERVER.md` Step 3; you may need to re-register your passkey |
| Custom Tab opens but shows a blank or broken page | Confirm TLS is working on the server: `curl -sk https://<APP_HOSTNAME>/health` should return 200 |
| "App version incompatible with server" | Update the Android app or the server to compatible versions; check the compatibility table in the release notes |
