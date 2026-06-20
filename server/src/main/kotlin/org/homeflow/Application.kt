package org.homeflow

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

// Placeholder port handling — Phase 3 moves all env reading into config/Config.kt
// (fail-fast validation). For now honor API_PORT so local runs can avoid a port
// already in use; defaults to 8080 (matches docker-compose + .env.example).
private const val DEFAULT_API_PORT = 8080

fun main() {
    val port = System.getenv("API_PORT")?.toIntOrNull() ?: DEFAULT_API_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
    }
}
