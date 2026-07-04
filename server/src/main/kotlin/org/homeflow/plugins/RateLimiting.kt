package org.homeflow.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.ratelimit.RateLimit
import org.homeflow.config.RateLimitConfig
import kotlin.time.Duration.Companion.milliseconds

/**
 * A coarse global rate limit applied to every route — a safety net against API
 * abuse / brute-force, independent of Keycloak's own login brute-force protection.
 * Limits come from [RateLimitConfig] (env, with `.env.example` defaults).
 */
fun Application.configureRateLimiting(config: RateLimitConfig) {
    install(RateLimit) {
        global {
            rateLimiter(limit = config.maxRequests, refillPeriod = config.windowMillis.milliseconds)
        }
    }
}
