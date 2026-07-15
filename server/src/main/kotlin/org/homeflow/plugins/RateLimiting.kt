package org.homeflow.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import org.homeflow.config.RateLimitConfig
import kotlin.time.Duration.Companion.milliseconds

/**
 * A rate limit applied to every route — a safety net against API abuse /
 * brute-force, independent of Keycloak's own login brute-force protection.
 * Limits come from [RateLimitConfig] (env, with `.env.example` defaults) and are
 * enforced **per client address** (from `X-Forwarded-For` via the
 * `XForwardedHeaders` plugin installed in `Application.module`, falling back to
 * the socket address when the header is absent, e.g. in tests) so one client
 * cannot exhaust the limit for others.
 */
fun Application.configureRateLimiting(config: RateLimitConfig) {
    install(RateLimit) {
        global {
            rateLimiter(limit = config.maxRequests, refillPeriod = config.windowMillis.milliseconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
}
