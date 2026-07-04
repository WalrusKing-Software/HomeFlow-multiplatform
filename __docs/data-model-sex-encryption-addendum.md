# Data Model Addendum — `daily_log_sex` Encryption Decision

## Summary

This document resolves the open question in `data-model.md` regarding the `daily_log_sex` table encryption approach. Apply these changes to `data-model.md` directly.

---

## Decision: Single Encrypted JSON Payload Column (Option A)

Sex tracking data uses a **single encrypted payload column** rather than per-row UUID foreign keys. This is a deliberate departure from how all other symptom sub-log tables are structured.

**Reason:** The `option_id` column on other symptom tables is a UUID FK referencing `ref_symptom_options`. Storing ciphertext in a UUID column is not possible, and even a hybrid approach (keeping the FK, adding an encrypted column) would leave the option IDs visible as plaintext in the database — defeating the purpose of encrypting this table entirely.

Sex and intimacy data is the highest-sensitivity category in the data model (see threat model TS-1). The FK referential integrity trade-off is acceptable because sex data is never used in analytics queries and never needs to be joined to reference tables at the DB layer.

---

## Replace the `daily_log_sex` Table Definition

**Remove** the existing `daily_log_sex` table definition in `data-model.md` and **replace** it with the following:

### `daily_log_sex`
Single encrypted payload per daily log. Stores the full set of selected sex/intimacy option IDs as AES-256-GCM encrypted JSON.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `daily_log_id` | `uuid` FK → `daily_logs.id` NOT NULL UNIQUE | One row per daily log — UNIQUE enforced |
| `user_id` | `uuid` FK → `users.id` NOT NULL | For RLS |
| `encrypted_payload` | `text` NOT NULL | AES-256-GCM encrypted JSON array of option ID strings. Format: `base64(iv):base64(ciphertext):base64(authTag)` |
| `created_at` | `timestamptz` NOT NULL | |
| `updated_at` | `timestamptz` NOT NULL | |

**No FK to `ref_symptom_options`.** The option IDs inside `encrypted_payload` are validated at the service layer before encryption — the service looks up valid option IDs from the `sex` category in `ref_symptom_options` and rejects any unknown IDs before encrypting. Referential integrity is enforced in application code, not at the DB layer.

**`UNIQUE (daily_log_id)`** — enforced via the `UNIQUE` constraint on `daily_log_id`. Unlike other multi-select tables that have one row per option, this table has exactly one row per daily log regardless of how many options are selected.

---

## How This Table Differs From All Other Symptom Tables

| Aspect | Other symptom tables (e.g. `daily_log_emotions`) | `daily_log_sex` |
|---|---|---|
| Rows per daily log | One row per selected option | One row per daily log |
| Option ID storage | UUID FK column referencing `ref_symptom_options` | Encrypted inside `encrypted_payload` |
| Referential integrity | Enforced by DB FK constraint | Enforced by service layer validation before encryption |
| DB visibility | Option IDs visible in plaintext | DB sees only ciphertext — no option IDs visible |
| Unique constraint | `UNIQUE (daily_log_id, option_id)` | `UNIQUE (daily_log_id)` |
| `UNIQUE` on `daily_log_id` | No (multiple rows expected) | Yes (exactly one row) |

---

## Service Layer Behavior

The `daily-logs.service.ts` function handling `PUT /daily-logs/:date/sex` must:

1. Receive the array of `optionIds` from the validated request body
2. Look up all valid option IDs for the `sex` category from `ref_symptom_options`
3. Reject any submitted option ID not in that set (`VALIDATION_ERROR`)
4. Serialize the validated array to JSON: `JSON.stringify(optionIds)`
5. Encrypt the JSON string using `encrypt()` from `lib/encryption.ts`
6. Upsert the `daily_log_sex` row — insert if none exists for this `daily_log_id`, update `encrypted_payload` if one already exists
7. On read (`GET /daily-logs/:date`): decrypt `encrypted_payload`, parse JSON, return the array of option ID strings

**To clear sex data:** Upsert with an encrypted empty array (`encrypt(JSON.stringify([]))`), or delete the row outright. Either is valid — use delete for consistency with how other sub-log tables handle clearing via empty `optionIds`.

---

## Migration Impact

In `migration 001_initial_schema.ts`, the `daily_log_sex` table must be created with this schema instead of the multi-row FK pattern. Specifically:

```sql
CREATE TABLE daily_log_sex (
  id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  daily_log_id uuid NOT NULL UNIQUE REFERENCES daily_logs(id) ON DELETE CASCADE,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  encrypted_payload text NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_daily_log_sex_user_id ON daily_log_sex(user_id);
```

Note the `UNIQUE` on `daily_log_id` — this is different from all other multi-select tables, which do not have this constraint on `daily_log_id` alone.

---

## Impact on `resetDatabase()` in Tests

No change needed. `daily_log_sex` is already in the truncation list in `TESTING.md`'s `setup.ts`. The table structure change does not affect the reset logic.

## Impact on `GET /daily-logs/:date` Response

The response shape for the `sex` field does not change from the API contract in `API.md`:

```json
"sex": ["uuid-no-sex", "uuid-high-drive"]
```

The array of option ID strings is what the frontend receives after decryption — identical to what other multi-select fields return. The encryption is fully transparent to the API consumer.
