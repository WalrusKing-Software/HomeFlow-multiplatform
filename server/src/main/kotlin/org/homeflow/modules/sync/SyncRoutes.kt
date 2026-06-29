package org.homeflow.modules.sync

import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import org.homeflow.core.dto.SyncPushRequest
import org.homeflow.lib.ValidationException
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/**
 * `/api/v1/sync` — the two sync endpoints from `__docs/API.md` (Phase 16a).
 *
 * - `POST /api/v1/sync/changes` — push local changes to the server (LWW merge).
 * - `GET  /api/v1/sync/changes?cursor=N` — pull server changes since [cursor].
 */
fun Route.syncRoutes(syncService: SyncService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1/sync/changes") {
            post {
                val principal = requirePrincipal()
                val request = call.receive<SyncPushRequest>()
                call.respond(syncService.push(principal, request))
            }
            get {
                val principal = requirePrincipal()
                val cursor =
                    call.request.queryParameters["cursor"]?.toLongOrNull()
                        ?: throw ValidationException(
                            "Missing or invalid 'cursor' query parameter (expected a non-negative integer).",
                        )
                call.respond(syncService.pull(principal, cursor))
            }
        }
    }
}
