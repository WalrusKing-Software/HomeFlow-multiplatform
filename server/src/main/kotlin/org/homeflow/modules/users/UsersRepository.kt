package org.homeflow.modules.users

import org.homeflow.db.Users
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A `users` row as read back by the repository. */
data class UserRow(
    val id: UUID,
    val keycloakSub: String,
    val createdAt: OffsetDateTime,
)

/**
 * DB access for the single-user `users` table. All queries go through Exposed
 * against the injected [db]; no business logic lives here (see layering rules in
 * `__docs/ARCHITECTURE-server.md`).
 */
class UsersRepository(
    private val db: Database,
) {
    /**
     * Returns the user for [keycloakSub], inserting it on first sight. `insertIgnore`
     * + re-select keeps this race-safe against the `keycloak_sub` unique index, so
     * concurrent first logins never create duplicates.
     */
    fun upsertByKeycloakSub(keycloakSub: String): UserRow =
        transaction(db) {
            findBySub(keycloakSub)?.let { return@transaction it }
            val now = OffsetDateTime.now(ZoneOffset.UTC)
            Users.insertIgnore {
                it[Users.keycloakSub] = keycloakSub
                it[createdAt] = now
                it[updatedAt] = now
            }
            findBySub(keycloakSub) ?: error("user upsert failed for the authenticated subject")
        }

    fun findById(id: UUID): UserRow? =
        transaction(db) {
            Users
                .selectAll()
                .where { Users.id eq id }
                .map(::toRow)
                .singleOrNull()
        }

    /**
     * Deletes the user row. Every health-data table references `users.id` with
     * `ON DELETE CASCADE`, so this removes all of the user's data in one statement.
     * Returns the number of rows deleted (0 if already gone).
     */
    fun deleteByUserId(id: UUID): Int =
        transaction(db) {
            Users.deleteWhere { Users.id eq id }
        }

    private fun findBySub(keycloakSub: String): UserRow? =
        Users
            .selectAll()
            .where { Users.keycloakSub eq keycloakSub }
            .map(::toRow)
            .singleOrNull()

    private fun toRow(row: ResultRow): UserRow =
        UserRow(
            id = row[Users.id],
            keycloakSub = row[Users.keycloakSub],
            createdAt = row[Users.createdAt],
        )
}
