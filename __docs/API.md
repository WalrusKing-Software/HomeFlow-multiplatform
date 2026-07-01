Claude Code is significantly more accurate when it has explicit API contracts to implement rather than inferring them from features. You want:

Every route: method, path, auth required, request body schema, response schema, error codes
Which routes are menstruation-phase-only (this has real conditional logic implications)
Pagination strategy for any list endpoints

Without this, Claude Code will invent route shapes, and you'll get inconsistencies between what the frontend expects and what the backend returns.


# API Reference — Secure Period Tracking Web App

## Conventions

- **Base path:** `/api/v1`
- **Auth:** Every route requires a valid JWT in the `Authorization: Bearer <token>` header unless marked `— no auth`. The frontend sends the access token (stored in an `httpOnly` cookie) via the SvelteKit server-side load function or form action — never from client-side JS.
- **User scoping:** All health data routes are implicitly scoped to the authenticated user. `user_id` is never accepted from the request body — it is always taken from `req.user.id`.
- **Content type:** `application/json` for all requests and responses.
- **Dates:** ISO 8601 date strings (`YYYY-MM-DD`) for all date fields. Timestamps use ISO 8601 with timezone (`YYYY-MM-DDTHH:mm:ssZ`).
- **UUIDs:** All `id` fields are UUIDs (v4).
- **Error shape:** All errors return `{ "error": { "code": string, "message": string } }`. See error codes in `ARCHITECTURE.md`.

---

## Route Index

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Health check — no auth |
| `GET` | `/api/v1/version` | Server version + min client requirement — no auth |
| `GET` | `/api/v1/users/me` | Get current user record |
| `DELETE` | `/api/v1/users/me` | Delete account and all data |
| `GET` | `/api/v1/cycles` | List all cycles |
| `POST` | `/api/v1/cycles` | Start a new cycle |
| `GET` | `/api/v1/cycles/current` | Get the active (open) cycle |
| `GET` | `/api/v1/cycles/:cycleId` | Get a single cycle |
| `PATCH` | `/api/v1/cycles/:cycleId` | Close or update a cycle |
| `GET` | `/api/v1/daily-logs/:date` | Get a full day log by date |
| `POST` | `/api/v1/daily-logs` | Create a daily log anchor |
| `PATCH` | `/api/v1/daily-logs/:date/notes` | Update free-text notes |
| `PUT` | `/api/v1/daily-logs/:date/emotions` | Replace emotion selections |
| `PUT` | `/api/v1/daily-logs/:date/sleep` | Replace sleep quality selections |
| `PUT` | `/api/v1/daily-logs/:date/energy` | Replace energy selection |
| `PUT` | `/api/v1/daily-logs/:date/sex` | Replace sex/sex life selections |
| `PUT` | `/api/v1/daily-logs/:date/discharge` | Replace discharge selections |
| `PUT` | `/api/v1/daily-logs/:date/skin` | Replace skin/face selections |
| `PUT` | `/api/v1/daily-logs/:date/digestion` | Replace digestion selections |
| `PUT` | `/api/v1/daily-logs/:date/flow` | Replace blood flow selection (menstruation only) |
| `PUT` | `/api/v1/daily-logs/:date/collection` | Replace collection method selection (menstruation only) |
| `PUT` | `/api/v1/daily-logs/:date/mind` | Replace mind/cognitive selections (menstruation only) |
| `PUT` | `/api/v1/daily-logs/:date/pain` | Replace pain log (severity + locations) |
| `GET` | `/api/v1/analytics/cycle-stats` | Avg cycle length, variation, avg period length |
| `GET` | `/api/v1/analytics/period-length-chart` | Per-cycle bleeding day counts for charting |
| `GET` | `/api/v1/analytics/ovulation-prediction` | Predicted ovulation and period days |
| `GET` | `/api/v1/analytics/sleep-predictions` | Sleep quality predictions by cycle phase |
| `GET` | `/api/v1/preferences` | Get dashboard category order |
| `PUT` | `/api/v1/preferences` | Replace dashboard category order |
| `GET` | `/api/v1/ref-data/symptom-categories` | All symptom categories with their options |
| `GET` | `/api/v1/ref-data/pain-regions` | All pain regions with their locations |
| `POST` | `/api/v1/import` | Import historical data from another app or a HomeFlow backup |
| `GET` | `/api/v1/export` | Export all data (backup or migration format) |

---

## Health Check

### `GET /health` — no auth

Used by Docker and the reverse proxy to verify the backend is running.

**Response `200`**
```json
{ "status": "ok" }
```

---

## Users

### `GET /api/v1/users/me`

Returns the current user's internal record. The frontend uses this to confirm the app-side user exists after first login.

**Response `200`**
```json
{
  "id": "uuid",
  "createdAt": "2024-01-15T10:00:00Z"
}
```

---

### `DELETE /api/v1/users/me`

Permanently deletes the user's account. Cascades in this order (enforced in the service layer):
1. Delete all health data rows where `user_id = req.user.id`
2. Delete the `users` row
3. Call Keycloak Admin API to delete the Keycloak identity

This operation is irreversible. The frontend should show a confirmation dialog before calling this.

**Response `204`** — no body

**Errors**
- `500 INTERNAL_ERROR` — if Keycloak deletion fails after DB deletion, log the Keycloak user ID for manual cleanup

---

## Cycles

### `GET /api/v1/cycles`

Returns all cycles for the authenticated user, newest first.

**Response `200`**
```json
{
  "cycles": [
    {
      "id": "uuid",
      "startDate": "2024-01-15",
      "endDate": "2024-02-11",
      "createdAt": "2024-01-15T10:00:00Z",
      "updatedAt": "2024-02-11T08:00:00Z"
    },
    {
      "id": "uuid",
      "startDate": "2024-02-12",
      "endDate": null,
      "createdAt": "2024-02-12T09:00:00Z",
      "updatedAt": "2024-02-12T09:00:00Z"
    }
  ]
}
```

> `endDate` is `null` for the currently active cycle.

---

### `POST /api/v1/cycles`

Starts a new cycle. If an open cycle exists, it is automatically closed: its `end_date` is set to `startDate - 1 day`.

**Request body**
```json
{
  "startDate": "2024-02-12"
}
```

**Validation**
- `startDate` — required, valid date string, must not be in the future

**Response `201`**
```json
{
  "id": "uuid",
  "startDate": "2024-02-12",
  "endDate": null,
  "createdAt": "2024-02-12T09:00:00Z",
  "updatedAt": "2024-02-12T09:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — invalid or future date

---

### `GET /api/v1/cycles/current`

Returns the currently open cycle (no `end_date`). Used by the frontend to determine whether the user is in an active cycle and which phase-gated tracking fields to show.

**Response `200`**
```json
{
  "id": "uuid",
  "startDate": "2024-02-12",
  "endDate": null,
  "createdAt": "2024-02-12T09:00:00Z",
  "updatedAt": "2024-02-12T09:00:00Z"
}
```

**Errors**
- `404 RESOURCE_NOT_FOUND` — no open cycle exists

---

### `GET /api/v1/cycles/:cycleId`

Returns a single cycle by ID.

**Response `200`** — same shape as a single cycle object above

**Errors**
- `404 RESOURCE_NOT_FOUND` — cycle not found or belongs to another user

---

### `PATCH /api/v1/cycles/:cycleId`

Updates a cycle. Currently only supports setting `endDate` (closing the cycle manually).

**Request body**
```json
{
  "endDate": "2024-02-18"
}
```

**Validation**
- `endDate` — must be on or after `startDate`, must not be in the future

**Response `200`** — updated cycle object

**Errors**
- `400 VALIDATION_ERROR` — end date before start date or in the future
- `404 RESOURCE_NOT_FOUND` — cycle not found or belongs to another user

---

## Daily Logs

The daily log (`daily_logs` row) is the anchor record for a given date. All symptom sub-logs hang off it. The frontend creates the anchor first (if it doesn't already exist), then issues `PUT` calls for each tracking category the user fills in.

All daily log routes use `:date` as an ISO 8601 date string (e.g. `2024-02-14`), not a UUID, because dates are the natural key from the user's perspective.

### `GET /api/v1/daily-logs/:date`

Returns the full log for a given date, including all symptom sub-logs. If no log exists for that date, returns `404` — the frontend uses this to determine whether to show an empty state or pre-populate.

**Response `200`**
```json
{
  "id": "uuid",
  "logDate": "2024-02-14",
  "cycleId": "uuid",
  "notes": "Feeling okay today.",
  "emotions": ["uuid-fine", "uuid-anxious"],
  "sleep": ["uuid-trouble-falling-asleep"],
  "energy": "uuid-tired",
  "sex": ["uuid-no-sex"],
  "discharge": ["uuid-creamy", "uuid-white"],
  "skin": ["uuid-fine"],
  "digestion": ["uuid-bloating"],
  "flow": null,
  "collection": null,
  "mind": null,
  "pain": null,
  "createdAt": "2024-02-14T08:00:00Z",
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

> All symptom fields return arrays of `option_id` UUIDs (or a single UUID for single-select, or `null` if not logged). The frontend resolves labels using the ref data it fetched at startup. `flow`, `collection`, and `mind` are `null` when this is not a menstruation day. `pain` is `null` when no pain was logged.

**Pain object shape (when present):**
```json
"pain": {
  "id": "uuid",
  "locations": [
    { "locationId": "uuid-lower-back", "severity": 7 },
    { "locationId": "uuid-ovaries", "severity": null }
  ]
}
```

> Each selected location carries its own `severity` (integer 1–10), or `null` when the
> location is selected but not yet rated. There is no day-level severity. `pain` is
> `null` when no pain locations are logged.

**Errors**
- `404 RESOURCE_NOT_FOUND` — no log exists for this date

---

### `POST /api/v1/daily-logs`

Creates the anchor `daily_logs` row for a date. Must be called before any `PUT` sub-log routes for that date. Idempotent — if a log already exists for this date, returns `409`.

**Request body**
```json
{
  "date": "2024-02-14",
  "cycleId": "uuid"
}
```

**Validation**
- `date` — required, valid date string
- `cycleId` — required, must be a cycle that belongs to the authenticated user, and `date` must fall within that cycle's date range

**Response `201`**
```json
{
  "id": "uuid",
  "logDate": "2024-02-14",
  "cycleId": "uuid",
  "createdAt": "2024-02-14T08:00:00Z",
  "updatedAt": "2024-02-14T08:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — invalid date or cycleId
- `409 CONFLICT` — a log already exists for this date

---

### `PATCH /api/v1/daily-logs/:date/notes`

Updates the free-text notes field for a day. Encrypted before storage.

**Request body**
```json
{
  "notes": "Cramping started in the afternoon."
}
```

**Validation**
- `notes` — string, max 5000 characters; pass `null` to clear

**Response `200`**
```json
{
  "notes": "Cramping started in the afternoon.",
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors**
- `404 RESOURCE_NOT_FOUND` — no log exists for this date

---

### `PUT /api/v1/daily-logs/:date/emotions`

Replaces the full set of emotion selections for the day. Sending an empty array clears all selections.

**Request body**
```json
{
  "optionIds": ["uuid-fine", "uuid-anxious"]
}
```

**Validation**
- `optionIds` — array of UUIDs; all must exist in `ref_symptom_options` under the `emotions` category
- Empty array is valid (clears selections)

**Response `200`**
```json
{
  "emotions": ["uuid-fine", "uuid-anxious"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — unknown option ID or option from wrong category
- `404 RESOURCE_NOT_FOUND` — no log exists for this date

---

### `PUT /api/v1/daily-logs/:date/sleep`

Replaces sleep quality selections. Same pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-trouble-falling-asleep"]
}
```

**Validation** — all UUIDs must be from the `sleep_quality` category

**Response `200`**
```json
{
  "sleep": ["uuid-trouble-falling-asleep"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors** — same as emotions

---

### `PUT /api/v1/daily-logs/:date/energy`

Replaces the single energy selection for the day. Send `null` to clear.

**Request body**
```json
{
  "optionId": "uuid-tired"
}
```

**Validation**
- `optionId` — single UUID or `null`; must be from the `energy` category

**Response `200`**
```json
{
  "energy": "uuid-tired",
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors** — same as emotions

---

### `PUT /api/v1/daily-logs/:date/sex`

Replaces sex/sex life selections. Stored encrypted. Same array pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-no-sex"]
}
```

**Validation** — all UUIDs must be from the `sex` category

**Response `200`**
```json
{
  "sex": ["uuid-no-sex"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors** — same as emotions

---

### `PUT /api/v1/daily-logs/:date/discharge`

Replaces discharge selections. Same array pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-creamy", "uuid-white"]
}
```

**Validation** — all UUIDs must be from the `discharge` category

**Response `200`**
```json
{
  "discharge": ["uuid-creamy", "uuid-white"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

---

### `PUT /api/v1/daily-logs/:date/skin`

Replaces skin/face selections. Same array pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-acne"]
}
```

**Validation** — all UUIDs must be from the `skin` category

**Response `200`**
```json
{
  "skin": ["uuid-acne"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

---

### `PUT /api/v1/daily-logs/:date/digestion`

Replaces digestion selections. Same array pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-bloating", "uuid-nausea"]
}
```

**Validation** — all UUIDs must be from the `digestion` category

**Response `200`**
```json
{
  "digestion": ["uuid-bloating", "uuid-nausea"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

---

### `PUT /api/v1/daily-logs/:date/flow`

Replaces the blood flow selection. **Menstruation phase only** — the service layer must verify that the date falls within a cycle where blood flow logging is active (i.e. the current cycle has at least one flow log, or this is the first flow log being set). Send `null` to clear.

**Request body**
```json
{
  "optionId": "uuid-medium"
}
```

**Validation**
- `optionId` — single UUID or `null`; must be from the `blood_flow` category

**Response `200`**
```json
{
  "flow": "uuid-medium",
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — option not from `blood_flow` category
- `404 RESOURCE_NOT_FOUND` — no log exists for this date

---

### `PUT /api/v1/daily-logs/:date/collection`

Replaces the collection method selection. Menstruation phase only. Send `null` to clear.

**Request body**
```json
{
  "optionId": "uuid-tampon"
}
```

**Validation** — must be from the `collection_method` category

**Response `200`**
```json
{
  "collection": "uuid-tampon",
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

---

### `PUT /api/v1/daily-logs/:date/mind`

Replaces mind/cognitive state selections. Menstruation phase only. Same array pattern as emotions.

**Request body**
```json
{
  "optionIds": ["uuid-brain-fog", "uuid-calm"]
}
```

**Validation** — all UUIDs must be from the `mind` category

**Response `200`**
```json
{
  "mind": ["uuid-brain-fog", "uuid-calm"],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

---

### `PUT /api/v1/daily-logs/:date/pain`

Replaces the full pain log for the day — every selected location with its own severity, together in one call. Send a `null` body (or an empty `locations` array) to clear all pain data.

**Request body**
```json
{
  "locations": [
    { "locationId": "uuid-lower-back", "severity": 6 },
    { "locationId": "uuid-ovaries", "severity": null }
  ]
}
```

**Request body (clear all pain)**
```json
{
  "locations": []
}
```

**Validation**
- `locations` — array of `{ locationId, severity }`; empty array clears the entry
- `locationId` — UUID; must exist in `ref_pain_locations`; no duplicate `locationId`s
- `severity` — integer 1–10, or `null` (selected but unrated)

**Response `200`**
```json
{
  "pain": {
    "id": "uuid",
    "locations": [
      { "locationId": "uuid-lower-back", "severity": 6 },
      { "locationId": "uuid-ovaries", "severity": null }
    ]
  },
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Response `200` (cleared)**
```json
{
  "pain": null,
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — severity out of range, unknown location ID, or duplicate location ID
- `404 RESOURCE_NOT_FOUND` — no log exists for this date

---

## Analytics

All analytics routes are read-only and compute results at query time from the authenticated user's historical data. They return `404` if insufficient data exists to compute the statistic.

---

### `GET /api/v1/analytics/cycle-stats`

Returns the three summary statistics shown in the analytics view.

**Response `200`**
```json
{
  "averageCycleLength": 28,
  "cycleVariation": 2.3,
  "averagePeriodLength": 5
}
```

> `averageCycleLength` — mean of `end_date - start_date` across all closed cycles, rounded to nearest integer (days).
> `cycleVariation` — standard deviation of cycle lengths over the last 6 months, rounded to 1 decimal place (days).
> `averagePeriodLength` — mean number of days with at least one flow log per cycle, rounded to nearest integer.

All three fields may be `null` if there is insufficient history to compute them (e.g. fewer than 2 closed cycles).

**Response `200` (insufficient data)**
```json
{
  "averageCycleLength": null,
  "cycleVariation": null,
  "averagePeriodLength": null
}
```

---

### `GET /api/v1/analytics/period-length-chart`

Returns one data point per closed cycle for the period length trend chart. Each point represents a single cycle.

**Response `200`**
```json
{
  "dataPoints": [
    { "cycleStartDate": "2023-10-01", "bleedingDays": 5 },
    { "cycleStartDate": "2023-10-29", "bleedingDays": 6 },
    { "cycleStartDate": "2023-11-26", "bleedingDays": 4 }
  ]
}
```

> `bleedingDays` — count of distinct `log_date` values within the cycle that have a `daily_log_flow` record.
> Ordered oldest to newest. Empty array if no closed cycles exist.

---

### `GET /api/v1/analytics/ovulation-prediction`

Returns predicted period start dates and ovulation dates for the next 3 months, based on average cycle length.

**Response `200`**
```json
{
  "averageCycleLength": 28,
  "predictions": [
    {
      "predictedPeriodStart": "2024-02-12",
      "predictedOvulationDate": "2024-01-29"
    },
    {
      "predictedPeriodStart": "2024-03-11",
      "predictedOvulationDate": "2024-02-26"
    },
    {
      "predictedPeriodStart": "2024-04-08",
      "predictedOvulationDate": "2024-03-25"
    }
  ]
}
```

> Ovulation is estimated as `predicted_period_start - 14 days`.
> Returns `null` for `predictions` if fewer than 2 closed cycles exist.

**Response `200` (insufficient data)**
```json
{
  "averageCycleLength": null,
  "predictions": null
}
```

---

### `GET /api/v1/analytics/sleep-predictions`

Returns a predicted sleep quality profile for each cycle phase, based on historical sleep logs grouped by phase.

**Response `200`**
```json
{
  "phases": {
    "menstruation": {
      "mostCommon": ["uuid-trouble-staying-asleep", "uuid-woke-rested"],
      "sampleSize": 12
    },
    "follicular": {
      "mostCommon": ["uuid-woke-rested"],
      "sampleSize": 18
    },
    "ovulation": {
      "mostCommon": ["uuid-woke-rested"],
      "sampleSize": 8
    },
    "luteal": {
      "mostCommon": ["uuid-trouble-falling-asleep", "uuid-trouble-staying-asleep"],
      "sampleSize": 20
    }
  }
}
```

> `mostCommon` — up to 3 sleep option IDs most frequently logged during that phase, ordered by frequency descending. The frontend resolves labels using ref data.
> `sampleSize` — number of days of data the prediction is based on for that phase.
> A phase object is `null` if fewer than 5 days of data exist for that phase.

**Phase definitions (cycle day ranges, approximate):**
- Menstruation: days with at least one flow log
- Follicular: cycle day 1 to ovulation day (non-bleeding days before ovulation)
- Ovulation: 2 days around predicted ovulation date
- Luteal: predicted ovulation day + 1 through end of cycle

---

## Preferences

### `GET /api/v1/preferences`

Returns the user's dashboard category ordering. If the user has never saved preferences, returns the default order from `ref_symptom_categories.sort_order`.

**Response `200`**
```json
{
  "categoryOrder": [
    "emotions",
    "energy",
    "sleep_quality",
    "sex",
    "discharge",
    "skin",
    "digestion",
    "blood_flow",
    "collection_method",
    "mind"
  ]
}
```

---

### `PUT /api/v1/preferences`

Replaces the user's full category order. The frontend sends the complete ordered array after the user rearranges rows.

**Request body**
```json
{
  "categoryOrder": [
    "energy",
    "emotions",
    "sleep_quality",
    "digestion",
    "sex",
    "discharge",
    "skin",
    "blood_flow",
    "collection_method",
    "mind"
  ]
}
```

**Validation**
- `categoryOrder` — array of category slugs; must contain every slug from `ref_symptom_categories` exactly once (no extras, no omissions)

**Response `200`**
```json
{
  "categoryOrder": ["energy", "emotions", "sleep_quality", "..."],
  "updatedAt": "2024-02-14T20:00:00Z"
}
```

**Errors**
- `400 VALIDATION_ERROR` — missing slugs, unknown slugs, or duplicate slugs

---

## Reference Data

These routes return seed data that the frontend loads once at startup (or caches). They define all selectable options in the UI. No writes — these tables are read-only at runtime.

### `GET /api/v1/ref-data/symptom-categories`

Returns all symptom categories with their options nested, in default display order.

**Response `200`**
```json
{
  "categories": [
    {
      "id": "uuid",
      "slug": "emotions",
      "label": "Emotions",
      "selectionType": "multi",
      "phase": "always",
      "sortOrder": 1,
      "options": [
        { "id": "uuid", "slug": "fine", "label": "Fine", "sortOrder": 1 },
        { "id": "uuid", "slug": "mood_swings", "label": "Mood Swings", "sortOrder": 2 },
        { "id": "uuid", "slug": "anxious", "label": "Anxious", "sortOrder": 3 }
      ]
    },
    {
      "id": "uuid",
      "slug": "blood_flow",
      "label": "Blood Flow",
      "selectionType": "single",
      "phase": "menstruation",
      "sortOrder": 8,
      "options": [
        { "id": "uuid", "slug": "light", "label": "Light", "sortOrder": 1 },
        { "id": "uuid", "slug": "medium", "label": "Medium", "sortOrder": 2 },
        { "id": "uuid", "slug": "heavy", "label": "Heavy", "sortOrder": 3 },
        { "id": "uuid", "slug": "super_heavy", "label": "Super Heavy", "sortOrder": 4 }
      ]
    }
  ]
}
```

> The `phase` field tells the frontend whether to show this category always or only during menstruation (when a flow log exists for the current cycle).

---

### `GET /api/v1/ref-data/pain-regions`

Returns all pain regions with their locations nested, in display order.

**Response `200`**
```json
{
  "regions": [
    {
      "id": "uuid",
      "slug": "head_neck",
      "label": "Head & Neck",
      "sortOrder": 1,
      "locations": [
        { "id": "uuid", "slug": "front_headache", "label": "Front Headache", "sortOrder": 1 },
        { "id": "uuid", "slug": "migraine", "label": "Migraine", "sortOrder": 3 }
      ]
    },
    {
      "id": "uuid",
      "slug": "abdomen",
      "label": "Abdomen",
      "sortOrder": 3,
      "locations": [
        { "id": "uuid", "slug": "uterus", "label": "Uterus", "sortOrder": 2 },
        { "id": "uuid", "slug": "ovaries", "label": "Ovaries", "sortOrder": 3 }
      ]
    }
  ]
}
```

---

## Import & Export

### `POST /api/v1/import`

Imports historical cycles and daily logs from another app's export, or restores a
HomeFlow backup. `multipart/form-data` with a single file part (`file`). The
`source` rides in the **query string** (not a form field) so the parser is chosen
before the file body is read — letting large Apple Health exports stream through.

**Query:** `?source=clue | apple_health | csv | homeflow`

| Source | Expected file | Fidelity |
|---|---|---|
| `clue` | Clue `measurements.json` (bare JSON array) | **Not yet implemented** — `400 VALIDATION_ERROR` |
| `apple_health` | Apple Health `export.xml` | **Not yet implemented** — `400 VALIDATION_ERROR` |
| `csv` | `date,flow,notes` CSV | **Not yet implemented** — `400 VALIDATION_ERROR` |
| `homeflow` | A HomeFlow native export (see below) | Full fidelity — lossless restore |

Only `source=homeflow` is implemented today; the other sources are a separate later
feature. Imports are **additive and idempotent**: existing days are never
overwritten, overlapping cycles are reused, and re-running a file creates no
duplicates. Slugs that don't map to this app's reference data are dropped and
counted in `warnings`; a day outside its cycle's range is skipped and counted.

**Response `200`:**
```json
{ "cyclesCreated": 2, "dailyLogsCreated": 31, "dailyLogsSkipped": 4, "warnings": ["3 unrecognized values were skipped"] }
```

**Errors:** `400 VALIDATION_ERROR` — bad/missing/unsupported `source`, the upload
exceeds 25 MB, or the file is structurally invalid (not valid JSON, or not a
`homeflow_export` envelope). There is no `422`; every import failure is `400`.

### `GET /api/v1/export`

Exports **all** of the authenticated user's cycles and daily logs as a downloadable
file. The response decrypts `notes` and sex data into plaintext (see
`threat-model.md` — a plaintext export is breach-sensitive), so it is authenticated,
row-scoped to `req.user.id`, sent with `Content-Disposition: attachment`, and never
logged.

**Query:** `?format=json | clue | apple_health | csv` — only `json` is implemented
today; the other formats are a separate later feature and return `400`.

| Format | Content-Type | Round-trips via import source |
|---|---|---|
| `json` | `application/json` | `homeflow` (full-fidelity backup/restore) |
| `clue` | — | **Not yet implemented** — `400 VALIDATION_ERROR` |
| `apple_health` | — | **Not yet implemented** — `400 VALIDATION_ERROR` |
| `csv` | — | **Not yet implemented** — `400 VALIDATION_ERROR` |

**Native export shape (`format=json`):** a JSON object with a `homeflow_export`
integer version marker (`1`), a `cycles` array (`{ startDate, endDate }`), and a
`days` array — one entry per logged day carrying `date`, `cycleStartDate`,
single-selects (`flow`, `collectionMethod`, `energy`), multi-select slug arrays
(`emotions`, `sleep`, `discharge`, `skin`, `digestion`, `mind`, `sex`), `pain`
(`{ location, severity }`), and decrypted `notes`. Every option/location is
identified by its **slug**, never a UUID (reference-data UUIDs are per-database and
don't survive a cross-store round-trip). Dashboard preferences are excluded — they
are not health data. Re-importable verbatim via `POST /api/v1/import?source=homeflow`.

**Errors:** `400 VALIDATION_ERROR` (unsupported format).

---

## Implementation Notes for Claude Code

- **`PUT` semantics for symptom sub-logs:** All symptom category routes use `PUT` (full replacement), not `PATCH`. The service layer deletes all existing rows for that `daily_log_id` + category, then inserts the new set. This keeps the implementation simple and avoids diff logic. An empty `optionIds` array is a valid "clear all" operation.

- **`POST /daily-logs` before `PUT` sub-logs:** The frontend must create the anchor log before writing any sub-log. If a `PUT` sub-log is called for a date with no anchor, return `404 RESOURCE_NOT_FOUND`.

- **Menstruation-phase gating:** `flow`, `collection`, and `mind` routes do not enforce a hard gate at the API layer — they validate that the option ID belongs to the correct category, which is sufficient. The frontend handles showing/hiding these fields based on whether the current cycle has any flow logs.

- **Date-as-key pattern:** Routes use `:date` (e.g. `/daily-logs/2024-02-14`) rather than `:dailyLogId` because that's the natural identifier for the frontend. The repository looks up the `daily_logs` row by `(user_id, log_date)`.

- **Ref data caching:** The frontend should fetch ref data once per session (on login or first load) and keep it in memory. It does not change at runtime. Do not add cache headers to these responses — the reverse proxy or browser will not cache `httpOnly`-cookie-authenticated responses anyway.

- **Analytics `null` handling:** Analytics routes always return `200`. Use `null` field values to signal insufficient data rather than `404`. This simplifies frontend handling — the component always gets a response and decides whether to render a stat or a "not enough data yet" empty state.