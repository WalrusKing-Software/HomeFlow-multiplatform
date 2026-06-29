package org.homeflow.modules.dailylogs

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.homeflow.core.dto.CreateDailyLogRequest
import org.homeflow.core.dto.NotesUpdateRequest
import org.homeflow.core.dto.OptionIdRequest
import org.homeflow.core.dto.OptionIdsRequest
import org.homeflow.core.dto.PainUpdateRequest
import org.homeflow.lib.NotFoundException
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/daily-logs` (`__docs/API.md`): fetch the assembled day, create the anchor,
 * update encrypted notes, and the Phase 5 symptom sub-log replace routes (one `PUT`
 * per category plus pain). All JWT-protected and row-scoped. The `:date` path segment
 * is an ISO date (the natural key from the user's perspective), not a UUID.
 */
fun Route.dailyLogsRoutes(
    dailyLogsService: DailyLogsService,
    subsService: DailyLogSubsService,
) {
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
            delete("/{date}") {
                val principal = requirePrincipal()
                dailyLogsService.deleteDay(principal, date())
                call.respond(HttpStatusCode.NoContent)
            }
            patch("/{date}/notes") {
                val principal = requirePrincipal()
                val request = call.receive<NotesUpdateRequest>()
                call.respond(dailyLogsService.updateNotes(principal, date(), request))
            }

            // ── Multi-select symptom categories ───────────────────────────────
            put("/{date}/emotions") {
                val principal = requirePrincipal()
                call.respond(subsService.setEmotions(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/sleep") {
                val principal = requirePrincipal()
                call.respond(subsService.setSleep(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/sex") {
                val principal = requirePrincipal()
                call.respond(subsService.setSex(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/discharge") {
                val principal = requirePrincipal()
                call.respond(subsService.setDischarge(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/skin") {
                val principal = requirePrincipal()
                call.respond(subsService.setSkin(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/digestion") {
                val principal = requirePrincipal()
                call.respond(subsService.setDigestion(principal, date(), call.receive<OptionIdsRequest>()))
            }
            put("/{date}/mind") {
                val principal = requirePrincipal()
                call.respond(subsService.setMind(principal, date(), call.receive<OptionIdsRequest>()))
            }

            // ── Single-select symptom categories ──────────────────────────────
            put("/{date}/energy") {
                val principal = requirePrincipal()
                call.respond(subsService.setEnergy(principal, date(), call.receive<OptionIdRequest>()))
            }
            put("/{date}/flow") {
                val principal = requirePrincipal()
                call.respond(subsService.setFlow(principal, date(), call.receive<OptionIdRequest>()))
            }
            put("/{date}/collection") {
                val principal = requirePrincipal()
                call.respond(subsService.setCollection(principal, date(), call.receive<OptionIdRequest>()))
            }

            // ── Pain ──────────────────────────────────────────────────────────
            put("/{date}/pain") {
                val principal = requirePrincipal()
                call.respond(subsService.setPain(principal, date(), call.receive<PainUpdateRequest>()))
            }
        }
    }
}

private fun RoutingContext.date(): String =
    call.parameters["date"] ?: throw NotFoundException("No log exists for this date.")
