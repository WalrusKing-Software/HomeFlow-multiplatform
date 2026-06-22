package org.homeflow.app.shared.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * OkHttp engine on Android. **No BODY-level logging interceptor is installed** — the
 * generated/default client must never log request/response bodies or `Authorization`
 * (CLAUDE.md "What Never Gets Logged").
 */
actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create { }
