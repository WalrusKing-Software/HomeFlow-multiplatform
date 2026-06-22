package org.homeflow.modules.analytics

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/analytics` — the four read-only analytics routes from `__docs/API.md`. All are
 * JWT-protected and row-scoped to the principal; each delegates straight to the service,
 * which computes the result with shared `:core` math. These never `404` — insufficient data
 * comes back as `200` with null fields.
 */
fun Route.analyticsRoutes(analyticsService: AnalyticsService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/analytics") {
            get("/cycle-stats") {
                call.respond(analyticsService.getCycleStats(requirePrincipal()))
            }
            get("/period-length-chart") {
                call.respond(analyticsService.getPeriodLengthChart(requirePrincipal()))
            }
            get("/ovulation-prediction") {
                call.respond(analyticsService.getOvulationPrediction(requirePrincipal()))
            }
            get("/sleep-predictions") {
                call.respond(analyticsService.getSleepPredictions(requirePrincipal()))
            }
        }
    }
}
