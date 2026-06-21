package org.homeflow.plugins

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json

/**
 * JSON content negotiation over the shared `:core` DTOs (kotlinx.serialization, no
 * codegen). `encodeDefaults` is on so nullable/`null` fields are emitted explicitly
 * (the client distinguishes "absent" from "null" on day logs); unknown incoming
 * keys are ignored for forward compatibility.
 */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
                explicitNulls = true
            },
        )
    }
}
