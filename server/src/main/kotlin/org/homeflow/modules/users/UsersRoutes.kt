package org.homeflow.modules.users

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.homeflow.lib.UnauthorizedException
import org.homeflow.plugins.KEYCLOAK_AUTH

/**
 * `/api/v1/users` — the current-user record and account deletion. Both routes are
 * JWT-protected; the [UserPrincipal] (and the row scoping it carries) comes from
 * the validated token, never the request. See `__docs/API.md` (Users).
 */
fun Route.usersRoutes(usersService: UsersService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/users") {
            get("/me") {
                val principal = call.principal<UserPrincipal>() ?: throw UnauthorizedException()
                call.respond(usersService.getMe(principal))
            }
            delete("/me") {
                val principal = call.principal<UserPrincipal>() ?: throw UnauthorizedException()
                usersService.deleteAccount(principal)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
