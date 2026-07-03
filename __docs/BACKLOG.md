# Backlog

Deferred, non-blocking improvements. Items here are intentionally out of the current
release scope; each notes why it was deferred and what "done" looks like.

## Android — flexible CA trust for self-hosted servers

**Deferred from:** the first LAN release (0.1.0).

**Now:** the release Android build trusts a user-installed CA only for the canonical LAN
hostname `homeflow.lan` (`app/androidApp/src/main/res/xml/network_security_config.xml`,
scoped `<domain-config>`). All other hosts stay system-CA-only. A LAN user who names their
server something other than `homeflow.lan` is not covered, and there is no in-app control.

**Want:**
- Make the trusted host **user-configurable** (mirror the desktop in-app CA picker) rather
  than hardcoding `homeflow.lan`. On Android this means a custom `X509TrustManager` applied
  to both the OkHttp engine (`HttpClientFactory.android.kt`) and AppAuth's
  `ConnectionBuilder` (`OidcClient.android.kt`) — `network_security_config` is static XML and
  cannot reference a runtime-chosen host. The Chrome Custom Tab still relies on the OS-level
  CA install (unavoidable).
- Support **Tailscale** hostnames end-to-end: publicly-trusted `*.ts.net` certs need no user
  CA, but confirm the connect/probe flow and docs cover the `*.ts.net` canonical hostname on
  Android the same way desktop does.

**Done when:** an Android user can connect to a self-hosted server on any hostname (LAN
self-signed or Tailscale) without editing build config, with per-server CA trust rather than
blanket user-CA trust.
