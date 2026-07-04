package org.homeflow.plugins

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.homeflow.core.dto.HealthDto
import org.homeflow.modules.analytics.AnalyticsService
import org.homeflow.modules.analytics.analyticsRoutes
import org.homeflow.modules.cycles.CyclesService
import org.homeflow.modules.cycles.cyclesRoutes
import org.homeflow.modules.dailylogs.DailyLogSubsService
import org.homeflow.modules.dailylogs.DailyLogsService
import org.homeflow.modules.dailylogs.dailyLogsRoutes
import org.homeflow.modules.importexport.ImportExportService
import org.homeflow.modules.importexport.importExportRoutes
import org.homeflow.modules.preferences.PreferencesService
import org.homeflow.modules.preferences.preferencesRoutes
import org.homeflow.modules.refdata.RefDataService
import org.homeflow.modules.refdata.refDataRoutes
import org.homeflow.modules.sync.SyncService
import org.homeflow.modules.sync.syncRoutes
import org.homeflow.modules.users.UsersService
import org.homeflow.modules.users.usersRoutes
import org.homeflow.modules.version.versionRoutes

/**
 * Top-level route table. `/health` is public (Docker/Caddy probe); everything under
 * `/api/v1` is mounted by its domain module and JWT-protected. Module route files
 * live under `modules/<domain>/`.
 */
@Suppress("LongParameterList") // one service per domain module, by design
fun Application.configureRouting(
    usersService: UsersService,
    cyclesService: CyclesService,
    dailyLogsService: DailyLogsService,
    dailyLogSubsService: DailyLogSubsService,
    refDataService: RefDataService,
    analyticsService: AnalyticsService,
    preferencesService: PreferencesService,
    importExportService: ImportExportService,
    syncService: SyncService,
    serverVersion: String,
    minClientVersion: String,
) {
    routing {
        get("/health") {
            call.respond(HealthDto(status = "ok"))
        }
        versionRoutes(serverVersion, minClientVersion)
        usersRoutes(usersService)
        cyclesRoutes(cyclesService)
        dailyLogsRoutes(dailyLogsService, dailyLogSubsService)
        refDataRoutes(refDataService)
        analyticsRoutes(analyticsService)
        preferencesRoutes(preferencesService)
        importExportRoutes(importExportService)
        syncRoutes(syncService)
    }
}
