package org.homeflow.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.date
import org.jetbrains.exposed.sql.kotlin.datetime.timestampWithTimeZone
import org.postgresql.util.PGobject

/**
 * Exposed table objects mirroring `__docs/data-model.md` (and the `daily_log_sex`
 * encryption addendum). These are hand-written and must stay in lockstep with the
 * Flyway migrations under `resources/db/migration/` — the schema is created by
 * Flyway (as the superuser), never by Exposed, so these definitions are used only
 * for type-safe query building at runtime by the restricted app role.
 *
 * Conventions: plain [Table] (not the *IdTable helpers) so the UUID primary keys
 * read back as plain `UUID`; `id` is client-generated on insert (the DB also has a
 * `gen_random_uuid()` default as a safety net); `created_at` / `updated_at` are set
 * explicitly by the service layer on write.
 */
object Users : Table("users") {
    val id = uuid("id").autoGenerate()
    val keycloakSub = varchar("keycloak_sub", 255).uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object Cycles : Table("cycles") {
    val id = uuid("id").autoGenerate()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val startDate = date("start_date")
    val endDate = date("end_date").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    /** Soft-delete timestamp (D3); null = live. Added in V3__sync.sql. */
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
        index(false, userId, startDate)
    }
}

// ── Reference data ────────────────────────────────────────────────────────────

object RefSymptomCategories : Table("ref_symptom_categories") {
    val id = uuid("id").autoGenerate()
    val slug = varchar("slug", 100).uniqueIndex()
    val label = varchar("label", 255)
    val selectionType = varchar("selection_type", 20)
    val phase = varchar("phase", 20)
    val sortOrder = integer("sort_order")

    override val primaryKey = PrimaryKey(id)
}

object RefSymptomOptions : Table("ref_symptom_options") {
    val id = uuid("id").autoGenerate()
    val categoryId = reference("category_id", RefSymptomCategories.id, onDelete = ReferenceOption.CASCADE)
    val slug = varchar("slug", 100)
    val label = varchar("label", 255)
    val sortOrder = integer("sort_order")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(categoryId, slug)
    }
}

object RefPainRegions : Table("ref_pain_regions") {
    val id = uuid("id").autoGenerate()
    val slug = varchar("slug", 100).uniqueIndex()
    val label = varchar("label", 255)
    val sortOrder = integer("sort_order")

    override val primaryKey = PrimaryKey(id)
}

object RefPainLocations : Table("ref_pain_locations") {
    val id = uuid("id").autoGenerate()
    val regionId = reference("region_id", RefPainRegions.id, onDelete = ReferenceOption.CASCADE)
    val slug = varchar("slug", 100)
    val label = varchar("label", 255)
    val sortOrder = integer("sort_order")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(regionId, slug)
    }
}

// ── Daily log anchor ──────────────────────────────────────────────────────────

object DailyLogs : Table("daily_logs") {
    val id = uuid("id").autoGenerate()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val cycleId = reference("cycle_id", Cycles.id, onDelete = ReferenceOption.CASCADE)
    val logDate = date("log_date")

    /** `[encrypted]` — AES-256-GCM ciphertext, encrypted/decrypted in the service layer only. */
    val notes = text("notes").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    /** Soft-delete timestamp (D3); null = live. Added in V3__sync.sql. */
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(userId, logDate)
        index(false, cycleId)
    }
}

// ── Single-selection symptom sub-logs (one row per daily log) ─────────────────

/** Base for the single-select sub-logs: a `daily_log_id`-unique row pointing at one option. */
sealed class SingleSelectLog(
    name: String,
) : Table(name) {
    val id = uuid("id").autoGenerate()
    val dailyLogId = reference("daily_log_id", DailyLogs.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val optionId = reference("option_id", RefSymptomOptions.id)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
}

object DailyLogEnergy : SingleSelectLog("daily_log_energy")

object DailyLogFlow : SingleSelectLog("daily_log_flow")

object DailyLogCollection : SingleSelectLog("daily_log_collection")

// ── Multi-select symptom sub-logs (one row per selected option) ───────────────

/** Base for the multi-select sub-logs: many rows per daily log, unique per option. */
sealed class MultiSelectLog(
    name: String,
) : Table(name) {
    val id = uuid("id").autoGenerate()
    val dailyLogId = reference("daily_log_id", DailyLogs.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val optionId = reference("option_id", RefSymptomOptions.id)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(dailyLogId, optionId)
        index(false, userId)
    }
}

object DailyLogEmotions : MultiSelectLog("daily_log_emotions")

object DailyLogSleep : MultiSelectLog("daily_log_sleep")

object DailyLogDischarge : MultiSelectLog("daily_log_discharge")

object DailyLogSkin : MultiSelectLog("daily_log_skin")

object DailyLogDigestion : MultiSelectLog("daily_log_digestion")

object DailyLogMind : MultiSelectLog("daily_log_mind")

// ── Sex/intimacy: single encrypted payload (addendum, Option A) ───────────────

object DailyLogSex : Table("daily_log_sex") {
    val id = uuid("id").autoGenerate()
    val dailyLogId = reference("daily_log_id", DailyLogs.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)

    /** `base64(iv):base64(ciphertext):base64(authTag)` of the JSON option-id array. */
    val encryptedPayload = text("encrypted_payload")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
}

// ── Pain ──────────────────────────────────────────────────────────────────────

object PainLogs : Table("pain_logs") {
    val id = uuid("id").autoGenerate()
    val dailyLogId = reference("daily_log_id", DailyLogs.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, userId)
    }
}

object PainLogLocations : Table("pain_log_locations") {
    val id = uuid("id").autoGenerate()
    val painLogId = reference("pain_log_id", PainLogs.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val locationId = reference("location_id", RefPainLocations.id)

    /** 1–10 for this location; null = selected but not yet rated. CHECK enforced in the DB. */
    val severity = short("severity").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(painLogId, locationId)
        index(false, userId)
        index(false, painLogId)
    }
}

// ── User preferences ──────────────────────────────────────────────────────────

object UserDashboardPreferences : Table("user_dashboard_preferences") {
    val id = uuid("id").autoGenerate()
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE).uniqueIndex()

    /** Ordered JSON array of `ref_symptom_categories.slug` values. */
    val categoryOrder = jsonb("category_order")
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(id)
}

/**
 * `jsonb` is not in core Exposed; this minimal column type (backed by [PGobject])
 * lets `category_order` be written and read as the proper Postgres `jsonb` type.
 */
// ── Sync change-log (Phase 16a) ───────────────────────────────────────────────

/**
 * One row per (user, entity_type, entity_id) — upserted, not appended — so
 * `server_seq` is always the most recent sequence value for that aggregate.
 * entity_type ∈ { "cycle", "day", "preferences" }.
 * `server_seq` is assigned from the PostgreSQL sequence `sync_seq`.
 */
object SyncChanges : Table("sync_changes") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val entityType = varchar("entity_type", 50)
    val entityId = uuid("entity_id")
    val serverSeq = long("server_seq")
    val updatedAt = timestampWithTimeZone("updated_at")
    val deleted = bool("deleted")

    override val primaryKey = PrimaryKey(userId, entityType, entityId)

    init {
        index(false, userId, serverSeq)
    }
}

private class JsonbColumnType : ColumnType<String>() {
    override fun sqlType(): String = "jsonb"

    override fun valueFromDB(value: Any): String =
        when (value) {
            is PGobject -> value.value.orEmpty()
            is String -> value
            else -> value.toString()
        }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = "jsonb"
            this.value = value
        }
}

private fun Table.jsonb(name: String): Column<String> = registerColumn(name, JsonbColumnType())
