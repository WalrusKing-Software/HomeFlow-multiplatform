package org.homeflow.modules.cycles

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
import org.homeflow.core.dto.CreateCycleRequest
import org.homeflow.core.dto.UpdateCycleRequest
import org.homeflow.lib.NotFoundException
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/cycles` — the five cycle routes from `__docs/API.md`. All are
 * JWT-protected and row-scoped to the principal from the validated token.
 * `current` is declared before `{cycleId}` so it is never captured as an id.
 */
fun Route.cyclesRoutes(cyclesService: CyclesService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/cycles") {
            get {
                val principal = requirePrincipal()
                call.respond(cyclesService.listCycles(principal))
            }
            post {
                val principal = requirePrincipal()
                val request = call.receive<CreateCycleRequest>()
                call.respond(HttpStatusCode.Created, cyclesService.createCycle(principal, request))
            }
            get("/current") {
                val principal = requirePrincipal()
                call.respond(cyclesService.getCurrentCycle(principal))
            }
            get("/{cycleId}") {
                val principal = requirePrincipal()
                call.respond(cyclesService.getCycle(principal, cycleId()))
            }
            patch("/{cycleId}") {
                val principal = requirePrincipal()
                val request = call.receive<UpdateCycleRequest>()
                call.respond(cyclesService.updateCycle(principal, cycleId(), request))
            }
        }
    }
}

private fun RoutingContext.cycleId(): String = call.parameters["cycleId"] ?: throw NotFoundException("Cycle not found.")
