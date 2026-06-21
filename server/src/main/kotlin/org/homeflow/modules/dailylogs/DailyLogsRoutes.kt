package org.homeflow.modules.dailylogs

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.lib.NotFoundException
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/daily-logs` — the Phase 4 anchor routes (`__docs/API.md`): fetch a day,
 * create the anchor, update encrypted notes. All JWT-protected and row-scoped. The
 * `:date` path segment is an ISO date (the natural key from the user's perspective),
 * not a UUID. Sub-log category PUTs arrive in Phase 5.
 */
fun Route.dailyLogsRoutes(dailyLogsService: DailyLogsService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/daily-logs") {
            post {
                val principal = requirePrincipal()
                val request = call.receive<CreateDailyLogRequest>()
                call.respond(HttpStatusCode.Created, dailyLogsService.createAnchor(principal, request))
            }
            get("/{date}") {
                val principal = requirePrincipal()
                call.respond(dailyLogsService.getDailyLog(principal, date()))
            }
            patch("/{date}/notes") {
                val principal = requirePrincipal()
                val request = call.receive<NotesUpdateRequest>()
                call.respond(dailyLogsService.updateNotes(principal, date(), request))
            }
        }
    }
}

private fun RoutingContext.date(): String =
    call.parameters["date"] ?: throw NotFoundException("No log exists for this date.")
