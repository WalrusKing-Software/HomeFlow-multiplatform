package org.homeflow.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.IColumnType
import org.jetbrains.exposed.sql.TextColumnType
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Runs [statement] in an Exposed transaction scoped to [userId] for PostgreSQL
 * row-level security (issue #78). Before any statement runs it sets the
 * transaction-local GUC `app.current_user_id`, which the RLS policies added in
 * `V5__row_level_security.sql` compare every user-scoped row against.
 *
 * This is defense-in-depth: the app-layer `AND user_id = <jwt user>` filter in each
 * repository query remains the primary, tested control. RLS is the backstop — if a
 * query ever forgets that filter, the database still returns only [userId]'s rows,
 * and a transaction that never sets the scope sees nothing (deny-by-default).
 *
 * Use this in place of `transaction(db)` for every repository operation that reads or
 * writes a user-scoped table. `users` (read during auth, before the request's user is
 * known) and the `ref_*` reference tables are not RLS-protected and use a plain
 * `transaction`.
 */
fun <T> userScopedTransaction(
    db: Database,
    userId: UUID,
    statement: Transaction.() -> T,
): T =
    transaction(db) {
        setCurrentUserId(userId)
        statement()
    }

/**
 * Sets `app.current_user_id` for the current transaction only. `set_config(..., is_local
 * => true)` has SET LOCAL semantics — it is reset at COMMIT/ROLLBACK, so the value never
 * leaks to the next transaction that reuses this pooled Hikari connection. The id is bound
 * as a query parameter, never string-interpolated, so it is injection-proof by construction.
 */
private fun Transaction.setCurrentUserId(userId: UUID) {
    exec(
        "SELECT set_config('app.current_user_id', ?, true)",
        listOf<Pair<IColumnType<*>, Any?>>(TextColumnType() to userId.toString()),
    )
}
