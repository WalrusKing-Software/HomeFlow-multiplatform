package org.homeflow.core.service

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncMergeTest {
    @Test
    fun remoteWinsWhenNewer() {
        assertEquals(
            MergeWinner.REMOTE,
            mergeDecision("2025-01-01T00:00:00Z", "aaa", "2025-06-01T00:00:00Z", "bbb"),
        )
    }

    @Test
    fun localWinsWhenNewer() {
        assertEquals(
            MergeWinner.LOCAL,
            mergeDecision("2025-06-01T00:00:00Z", "bbb", "2025-01-01T00:00:00Z", "aaa"),
        )
    }

    @Test
    fun remoteWinsOnTimestampTieByGreaterUuid() {
        val ts = "2025-06-01T12:00:00Z"
        assertEquals(
            MergeWinner.REMOTE,
            mergeDecision(ts, "00000000-0000-0000-0000-000000000001", ts, "00000000-0000-0000-0000-000000000002"),
        )
    }

    @Test
    fun localWinsOnTimestampTieByGreaterUuid() {
        val ts = "2025-06-01T12:00:00Z"
        assertEquals(
            MergeWinner.LOCAL,
            mergeDecision(ts, "00000000-0000-0000-0000-000000000002", ts, "00000000-0000-0000-0000-000000000001"),
        )
    }

    @Test
    fun localWinsWhenEverythingIsEqual() {
        val ts = "2025-06-01T12:00:00Z"
        val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertEquals(
            MergeWinner.LOCAL,
            mergeDecision(ts, id, ts, id),
        )
    }
}
