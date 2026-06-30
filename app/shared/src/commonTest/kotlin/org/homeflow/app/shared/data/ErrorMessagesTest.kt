package org.homeflow.app.shared.data

import org.homeflow.core.ErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMessagesTest {
    @Test
    fun user_facing_codes_surface_the_server_message() {
        // Validation / conflict / not-found messages are already user-meaningful.
        val validation = "Start date is in the future."
        assertEquals(validation, userFacingMessage(ErrorCode.VALIDATION_ERROR, validation))
        assertEquals("A cycle already exists.", userFacingMessage(ErrorCode.CONFLICT, "A cycle already exists."))
        assertEquals("No log for that day.", userFacingMessage(ErrorCode.RESOURCE_NOT_FOUND, "No log for that day."))
    }

    @Test
    fun transport_and_auth_faults_use_friendly_generic_lines() {
        // Server message for these is often an exception string or null — never surfaced raw.
        val internal = userFacingMessage(ErrorCode.INTERNAL_ERROR, "java.net.ConnectException: ...")
        assertTrue(internal.startsWith("Something went wrong"))
        assertTrue(userFacingMessage(ErrorCode.UNAUTHORIZED, null).contains("session"))
        assertTrue(userFacingMessage(ErrorCode.FORBIDDEN, "stack trace").startsWith("You don't have access"))
    }

    @Test
    fun blank_server_message_falls_back_to_a_default() {
        assertTrue(userFacingMessage(ErrorCode.VALIDATION_ERROR, "  ").isNotBlank())
        assertTrue(userFacingMessage(ErrorCode.CONFLICT, null).isNotBlank())
    }

    @Test
    fun failure_extension_maps_through() {
        val failure = ApiResult.Failure(ErrorCode.INTERNAL_ERROR, "Network error", 0)
        assertEquals(userFacingMessage(ErrorCode.INTERNAL_ERROR, "Network error"), failure.userMessage())
    }
}
