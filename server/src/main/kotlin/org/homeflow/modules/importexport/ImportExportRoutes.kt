package org.homeflow.modules.importexport

import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.homeflow.lib.ValidationException
import org.homeflow.plugins.KEYCLOAK_AUTH
import org.homeflow.plugins.requirePrincipal

/** Upload guard for `POST /import` (D-12.9). */
private const val MAX_IMPORT_FILE_BYTES = 25L * 1024 * 1024
private const val EXPORT_FILE_NAME = "homeflow-export.json"

/**
 * `/api/v1/export` and `/api/v1/import` — the native `homeflow` export/import format
 * (`__docs/API.md` "Import & Export"). Only `source=homeflow`/`format=json` are
 * implemented (D-12.2); anything else is `400 VALIDATION_ERROR`.
 */
fun Route.importExportRoutes(service: ImportExportService) {
    authenticate(KEYCLOAK_AUTH) {
        route("/api/v1") {
            get("/export") {
                val format = call.request.queryParameters["format"] ?: "json"
                if (format != "json") throw ValidationException("Unsupported export format: $format.")
                val principal = requirePrincipal()
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment
                        .withParameter(ContentDisposition.Parameters.FileName, EXPORT_FILE_NAME)
                        .toString(),
                )
                call.respond(service.exportAll(principal))
            }
            post("/import") {
                val source = call.request.queryParameters["source"]
                if (source != "homeflow") throw ValidationException("Unsupported import source: $source.")
                val principal = requirePrincipal()
                call.respond(service.importHomeflow(principal, call.receiveImportFile()))
            }
        }
    }
}

/** Reads the multipart request's single `file` part as UTF-8 text, enforcing [MAX_IMPORT_FILE_BYTES]. */
private suspend fun ApplicationCall.receiveImportFile(): String {
    var fileText: String? = null
    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem && fileText == null) {
            val bytes = part.provider().readRemaining(MAX_IMPORT_FILE_BYTES + 1).readByteArray()
            if (bytes.size > MAX_IMPORT_FILE_BYTES) {
                throw ValidationException("Import file exceeds the maximum size of 25 MB.")
            }
            fileText = bytes.toString(Charsets.UTF_8)
        }
        part.release()
    }
    return fileText ?: throw ValidationException("Import request must include a file part named 'file'.")
}
