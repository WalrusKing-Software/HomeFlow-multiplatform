package org.homeflow.modules.preferences

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.homeflow.core.dto.UpdatePreferencesRequest
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/preferences` — read and replace the dashboard category ordering (`__docs/API.md`).
 * Both routes are JWT-protected and row-scoped to the principal.
 */
fun Route.preferencesRoutes(preferencesService: PreferencesService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/preferences") {
            get {
                call.respond(preferencesService.getPreferences(requirePrincipal()))
            }
            put {
                val principal = requirePrincipal()
                call.respond(preferencesService.updatePreferences(principal, call.receive<UpdatePreferencesRequest>()))
            }
        }
    }
}
