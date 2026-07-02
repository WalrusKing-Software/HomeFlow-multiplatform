package org.homeflow.modules.version

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.homeflow.core.dto.VersionDto

/**
 * `GET /api/v1/version` — **no authentication required**. Clients call this before
 * loading the user to check whether the server is compatible with their version.
 * Mount this outside any `authenticate()` block, alongside `/health`.
 *
 * [serverVersion] and [minClientVersion] are read from [org.homeflow.config.Config]
 * at startup (env vars `SERVER_VERSION` / `MIN_CLIENT_VERSION`; see `COMPATIBILITY.md`).
 */
fun Route.versionRoutes(
    serverVersion: String,
    minClientVersion: String,
) {
    route("/api/v1") {
        get("/version") {
            call.respond(VersionDto(serverVersion, minClientVersion, "v1"))
        }
    }
}
