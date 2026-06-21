package org.homeflow.plugins

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.homeflow.core.dto.HealthDto
import org.homeflow.modules.cycles.CyclesService
import org.homeflow.modules.cycles.cyclesRoutes
import org.homeflow.modules.dailylogs.DailyLogsService
import org.homeflow.modules.dailylogs.dailyLogsRoutes
import org.homeflow.modules.users.UsersService
import org.homeflow.modules.users.usersRoutes

/**
 * Top-level route table. `/health` is public (Docker/Caddy probe); everything under
 * `/api/v1` is mounted by its domain module and JWT-protected. Module route files
 * live under `modules/<domain>/`.
 */
fun Application.configureRouting(
    usersService: UsersService,
    cyclesService: CyclesService,
    dailyLogsService: DailyLogsService,
) {
    routing {
        get("/health") {
            call.respond(HealthDto(status = "ok"))
        }
        usersRoutes(usersService)
        cyclesRoutes(cyclesService)
        dailyLogsRoutes(dailyLogsService)
    }
}
