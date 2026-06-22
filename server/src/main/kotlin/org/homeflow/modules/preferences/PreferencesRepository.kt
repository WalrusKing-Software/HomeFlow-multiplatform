package org.homeflow.modules.preferences

import org.homeflow.db.UserDashboardPreferences
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
 */
data class PreferencesRow(
    val categoryOrder: String,
    val updatedAt: OffsetDateTime,
)

/**
 * DB access for `user_dashboard_preferences` (one row per user, keyed by `user_id`). The
 * repository stores/reads the category order as the raw JSON string; the service handles
 * (de)serialization and validation. Scoped to the authenticated `userId` throughout.
 */
class PreferencesRepository(
    private val db: Database,
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
            if (updated == 0) {
                UserDashboardPreferences.insert {
                    it[id] = UUID.randomUUID()
                    it[UserDashboardPreferences.userId] = userId
                    it[categoryOrder] = categoryOrderJson
                    it[updatedAt] = now
                }
            }
            now
        }

    private fun toRow(row: ResultRow): PreferencesRow =
        PreferencesRow(
            categoryOrder = row[UserDashboardPreferences.categoryOrder],
            updatedAt = row[UserDashboardPreferences.updatedAt],
        )
}
