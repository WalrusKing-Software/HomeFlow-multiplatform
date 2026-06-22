package org.homeflow.app.shared.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO

/**
 * CIO engine on desktop. No BODY-level logging is installed (CLAUDE.md). When a dev CA
 * is configured ([DesktopTls]) it is added to the TLS trust so a Caddy-internal-CA host
 * (`make dev` on `localhost`) is reachable; otherwise the system truststore is used.
 */
actual fun platformHttpEngine(): HttpClientEngine = CIO.create { DesktopTls.applyTo(this) }
