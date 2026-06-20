# Security Review Checklist

Use at the end of any phase involving auth, data access, or export.
Reference in Claude Code prompts with: @__docs/security-review-checklist.md

## Endpoints
- [ ] Every endpoint requires auth except /health and /auth/callback
- [ ] user_id always comes from req.user (JWT), never from request body or params
- [ ] Ownership check runs before any DB query on user-owned resources
- [ ] Returns 404 (not 403) for resources owned by another user

## Input Validation
- [ ] Every request body validated with Fastify JSON Schema or Zod
- [ ] No raw req.body access without prior schema validation
- [ ] Enum fields validated against allowlist

## Queries
- [ ] All queries use Drizzle query builder
- [ ] No string interpolation in any SQL
- [ ] Raw SQL (migrations only) uses parameterized placeholders

## Logging
- [ ] No health data in any log (cycle dates, symptom values, BBT, notes)
- [ ] Fastify redact config covers all health-data fields
- [ ] Audit log metadata contains no health content

## Encryption
- [ ] All [ENC] fields go through encryption.ts, nowhere else
- [ ] Encrypted values are not logged before or after encryption
- [ ] DB_ENCRYPTION_ENABLED=false not present in any production config

## Secrets
- [ ] No hardcoded credentials or keys anywhere
- [ ] All secrets come from environment variables