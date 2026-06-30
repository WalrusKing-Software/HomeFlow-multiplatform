package org.homeflow.app.shared.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.homeflow.app.shared.auth.OidcTokens
import org.homeflow.app.shared.config.AuthConfig
import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Verifies that [RemoteDataSource.uploadHomeflowImport] issues a `POST` to
 * `.../import?source=homeflow` with a multipart body and maps responses correctly.
 */
class RemoteImportUploadTest {
    private val config = AuthConfig(host = "example.test")

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun client(engine: MockEngine) =
        buildHttpClient(
            config = config,
            tokenHolder = TokenHolder().apply { set(OidcTokens("test-AT", "test-RT", null)) },
            onRefresh = { null },
            engine = engine,
        )

    @Test
    fun `uploadHomeflowImport POSTs to import with source=homeflow`() =
        runTest {
            val capturedRequests = mutableListOf<io.ktor.client.request.HttpRequestData>()
            val engine =
                MockEngine { request ->
                    capturedRequests += request
                    respond(
                        content =
                            """
                            {"cyclesCreated":1,"dailyLogsCreated":2,"dailyLogsSkipped":0,"warnings":[]}
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders(),
                    )
                }

            val result =
                RemoteDataSource(client(engine))
                    .uploadHomeflowImport("""{"homeflow_export":1,"cycles":[],"days":[]}""")

            assertIs<ApiResult.Success<*>>(result)
            assertEquals(1, capturedRequests.size)
            val req = capturedRequests.first()
            assertEquals(HttpMethod.Post, req.method)
            // path ends with the import endpoint
            val url = req.url.toString()
            assert(url.contains("import")) { "URL must contain 'import': $url" }
            assert(url.contains("source=homeflow")) { "URL must contain 'source=homeflow': $url" }
        }

    @Test
    fun `uploadHomeflowImport decodes success ImportResultDto`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """{"cyclesCreated":3,"dailyLogsCreated":7,"dailyLogsSkipped":1,"warnings":["w1"]}""",
                        status = HttpStatusCode.OK,
                        headers = jsonHeaders(),
                    )
                }

            val result = RemoteDataSource(client(engine)).uploadHomeflowImport("{}")
            assertIs<ApiResult.Success<*>>(result)
            val dto = (result as ApiResult.Success).value
            assertEquals(3, dto.cyclesCreated)
            assertEquals(7, dto.dailyLogsCreated)
            assertEquals(1, dto.dailyLogsSkipped)
            assertEquals(listOf("w1"), dto.warnings)
        }

    @Test
    fun `uploadHomeflowImport maps 400 VALIDATION_ERROR body to Failure`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = """{"error":{"code":"VALIDATION_ERROR","message":"source not supported"}}""",
                        status = HttpStatusCode.BadRequest,
                        headers = jsonHeaders(),
                    )
                }

            val result = RemoteDataSource(client(engine)).uploadHomeflowImport("{}")
            assertIs<ApiResult.Failure>(result)
            assertEquals(ErrorCode.VALIDATION_ERROR, result.code)
        }
}
