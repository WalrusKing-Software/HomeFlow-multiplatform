package org.homeflow.modules.refdata

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/ref-data` — the two read-only reference routes from `__docs/API.md`. The
 * data is the same for every user, but the routes are still JWT-protected (every
 * `/api/v1` route requires auth); [requirePrincipal] enforces a valid token.
 */
fun Route.refDataRoutes(refDataService: RefDataService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/ref-data") {
            get("/symptom-categories") {
                requirePrincipal()
                call.respond(refDataService.getSymptomCategories())
            }
            get("/pain-regions") {
                requirePrincipal()
                call.respond(refDataService.getPainRegions())
            }
        }
    }
}
