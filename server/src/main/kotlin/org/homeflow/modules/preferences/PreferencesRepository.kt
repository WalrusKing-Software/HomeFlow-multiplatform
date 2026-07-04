package org.homeflow.modules.preferences

import org.homeflow.db.UserDashboardPreferences
import org.homeflow.modules.sync.ChangeLogRepository
import org.homeflow.modules.sync.ChangeLogRepository.Companion.TYPE_PREFERENCES
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The user's saved dashboard preferences. [categoryOrder] is the raw `jsonb` column value
 * (a JSON array of category slugs) exactly as stored — the service encodes/decodes it.
 * [id] is included so the sync change-log can reference the row by UUID.
 */
data class PreferencesRow(
    val id: UUID,
    val categoryOrder: String,
    val updatedAt: OffsetDateTime,
)

/**
 * DB access for `user_dashboard_preferences` (one row per user, keyed by `user_id`). The
 * repository stores/reads the category order as the raw JSON string; the service handles
 * (de)serialization and validation. Scoped to the authenticated `userId` throughout.
 *
 * [upsert] records a `preferences` change inside the same Exposed transaction so the
 * preference write and the sync change-log entry are atomic (Phase 16a D-16a.3).
 */
class PreferencesRepository(
    private val db: Database,
    private val changeLogRepository: ChangeLogRepository,
) {
    /** The user's saved preferences, or null if they have never saved any. */
    fun find(userId: UUID): PreferencesRow? =
        transaction(db) {
            UserDashboardPreferences
                .selectAll()
                .where { UserDashboardPreferences.userId eq userId }
                .map(::toRow)
                .singleOrNull()
        }

    /**
     * Inserts or replaces the user's category order with [categoryOrderJson] (a JSON array of
     * slugs), returning the new `updated_at`. The `user_id` unique index makes this an upsert.
     * Records a `preferences` change for the row.
     */
    fun upsert(
        userId: UUID,
        categoryOrderJson: String,
    ): OffsetDateTime =
        transaction(db) {
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            val updated =
                UserDashboardPreferences.update({ UserDashboardPreferences.userId eq userId }) {
                    it[categoryOrder] = categoryOrderJson
                    it[updatedAt] = now
                }
            val rowId: UUID
            if (updated == 0) {
                rowId = UUID.randomUUID()
                UserDashboardPreferences.insert {
                    it[id] = rowId
                    it[UserDashboardPreferences.userId] = userId
                    it[categoryOrder] = categoryOrderJson
                    it[updatedAt] = now
                }
            } else {
                rowId =
                    UserDashboardPreferences
                        .selectAll()
                        .where { UserDashboardPreferences.userId eq userId }
                        .map { it[UserDashboardPreferences.id] }
                        .single()
            }
            changeLogRepository.record(userId, TYPE_PREFERENCES, rowId, now, deleted = false)
            now
        }

    private fun toRow(row: ResultRow): PreferencesRow =
        PreferencesRow(
            id = row[UserDashboardPreferences.id],
            categoryOrder = row[UserDashboardPreferences.categoryOrder],
            updatedAt = row[UserDashboardPreferences.updatedAt],
        )
}
