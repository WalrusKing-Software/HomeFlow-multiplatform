package org.homeflow.core.dto

import kotlinx.serialization.json.Json
import org.homeflow.core.ApiError
import org.homeflow.core.ErrorBody
import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SerializationTest {
    private val json = Json

    @Test
    fun cycleRoundTripsWithNullEndDate() {
        val cycle =
            CycleDto(
                id = "c1",
                startDate = "2024-02-12",
                endDate = null,
                createdAt = "2024-02-12T09:00:00Z",
                updatedAt = "2024-02-12T09:00:00Z",
            )
        val decoded = json.decodeFromString<CycleDto>(json.encodeToString(cycle))
        assertEquals(cycle, decoded)
    }

    @Test
    fun apiErrorSerializesToTheCanonicalShape() {
        val encoded =
            json.encodeToString(
                ApiError(ErrorBody(ErrorCode.RESOURCE_NOT_FOUND, "Daily log not found for the given date.")),
            )
        assertTrue(encoded.contains("\"code\":\"RESOURCE_NOT_FOUND\""), encoded)
        assertTrue(encoded.contains("\"error\""), encoded)

        val decoded = json.decodeFromString<ApiError>(encoded)
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, decoded.error.code)
    }

    @Test
    fun dailyLogReadsTheApiExampleFields() {
        val payload =
            """
            {
              "id": "log-1",
              "logDate": "2024-02-14",
              "cycleId": "cyc-1",
              "notes": "Feeling okay today.",
              "emotions": ["o-fine", "o-anxious"],
              "energy": "o-tired",
              "flow": null,
              "pain": { "id": "p1", "locations": [ { "locationId": "loc-lower-back", "severity": 7 } ] },
              "createdAt": "2024-02-14T08:00:00Z",
              "updatedAt": "2024-02-14T20:00:00Z"
            }
            """.trimIndent()
        val log = json.decodeFromString<DailyLogDto>(payload)
        assertEquals(listOf("o-fine", "o-anxious"), log.emotions)
        assertEquals("o-tired", log.energy)
        assertEquals(null, log.flow)
        assertEquals(
            7,
            log.pain
                ?.locations
                ?.first()
                ?.severity,
        )
        assertEquals(null, log.sleep) // absent field decodes to default null
    }
}
