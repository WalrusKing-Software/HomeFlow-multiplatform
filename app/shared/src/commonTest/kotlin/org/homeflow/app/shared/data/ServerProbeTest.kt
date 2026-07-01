package org.homeflow.app.shared.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ServerProbeTest {
    private fun versionBody(
        server: String,
        minClient: String,
    ) = """{"serverVersion":"$server","minClientVersion":"$minClient","apiVersion":"v1"}"""

    private fun jsonEngine(body: String) =
        MockEngine {
            respond(content = body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }

    @Test
    fun reachable_when_client_meets_min_version() =
        runTest {
            val engine = jsonEngine(versionBody(server = "2.0.0", minClient = "1.0.0"))

            val result = probeServer(host = "example.test", clientVersion = "1.0.0", engine = engine)

            val reachable = assertIs<ProbeResult.Reachable>(result)
            assertEquals("2.0.0", reachable.serverVersion)
        }

    @Test
    fun incompatible_when_client_below_min_version() =
        runTest {
            val engine = jsonEngine(versionBody(server = "2.0.0", minClient = "2.0.0"))

            val result = probeServer(host = "example.test", clientVersion = "1.0.0", engine = engine)

            val incompatible = assertIs<ProbeResult.Incompatible>(result)
            assertEquals("2.0.0", incompatible.minClient)
        }

    @Test
    fun reachable_when_server_predates_version_endpoint() =
        runTest {
            val engine = MockEngine { respond(content = "", status = HttpStatusCode.NotFound) }

            val result = probeServer(host = "example.test", clientVersion = "1.0.0", engine = engine)

            assertIs<ProbeResult.Reachable>(result)
        }

    @Test
    fun unreachable_when_transport_fails() =
        runTest {
            val engine = MockEngine { throw RuntimeException("connection refused") }

            val result = probeServer(host = "example.test", clientVersion = "1.0.0", engine = engine)

            assertIs<ProbeResult.Unreachable>(result)
        }
}
