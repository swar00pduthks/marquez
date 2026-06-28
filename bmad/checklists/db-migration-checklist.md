# Database Migration Checklist (Flyway)

Use this checklist for every change to the PostgreSQL schema.
**NEVER modify an existing migration file.** Always create a new one.

---

## Before Writing the Migration

- [ ] Checked the current highest migration version in `api/src/main/resources/marquez/db/migration/`
- [ ] New file name: `V{N+1}__{snake_case_description}.sql` (two underscores, sequential number)
- [ ] Apache 2.0 license header + SPDX identifier at top of file
- [ ] Migration reviewed against the spec (`specs/<feature>/spec.md` section 4)

---

## Safety Rules

### Mandatory
- [ ] No `DROP COLUMN` — mark deprecated in comment; drop in a future major version
- [ ] No `ALTER TABLE ... RENAME COLUMN` — breaks existing queries
- [ ] No `ALTER TABLE ... ALTER COLUMN ... SET NOT NULL` on populated table without a default
- [ ] No `TRUNCATE` or `DELETE FROM` in a migration
- [ ] No DDL that acquires an `ACCESS EXCLUSIVE` lock for longer than 2 seconds on a large table

### Preferred
- [ ] New columns are nullable OR have a `DEFAULT` value
- [ ] `IF NOT EXISTS` / `IF EXISTS` guards on all `CREATE` and `DROP` statements
- [ ] `CREATE INDEX CONCURRENTLY` used for indexes on large tables (> 100k rows expected)
- [ ] `CREATE UNIQUE INDEX CONCURRENTLY` used for unique index additions

---

## Migration File Template

```sql
-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2018-2024 contributors to the Marquez project.

-- V{N}__your_description_here.sql
-- Purpose: [One sentence describing what this migration does and why]

-- [Your DDL statements here]

ALTER TABLE your_table
    ADD COLUMN IF NOT EXISTS your_column TEXT;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_your_table_your_column
    ON your_table(your_column);
```

---

## Testing the Migration

### Local Test (Required)
```bash
# Start a fresh database
docker-compose -f docker-compose.db.yml down -v
docker-compose -f docker-compose.db.yml up -d

# Run migration
./gradlew :api:flywayMigrate

# Verify schema applied correctly
docker exec -it marquez_db psql -U marquez -c "\d your_table"
```

- [ ] Migration applies cleanly to a **fresh** PostgreSQL 14 database
- [ ] Migration applies cleanly to a database with **existing production-like data**
- [ ] Schema after migration matches the spec exactly (`\d table_name`)

### Rollback Test
- [ ] Identified what manual steps are needed to undo this migration (for incident response)
- [ ] Rollback documented in `specs/<feature>/adr.md` section "Migration / Rollback Strategy"
- [ ] If the migration is reversible, a `V{N}__rollback_description.sql` script is prepared (but NOT committed unless needed)

---

## Multi-Step Migration Strategy (for risky changes)

For changes that cannot be done safely in one migration on a live system, use the expand/contract pattern:

### Step 1 — Expand (this migration)
- Add the new column/table (nullable)
- Application code reads from old column, writes to both old and new

### Step 2 — Backfill (separate job or migration)
- Backfill data into the new column
- Application code reads from new column, writes to both

### Step 3 — Contract (future migration)
- Apply NOT NULL constraint (after backfill confirmed complete)
- Remove old column reads from application
- Drop old column in a subsequent release

- [ ] Multi-step strategy documented in the ADR if this migration uses expand/contract

---

## Post-Migration Verification

```sql
-- Run after migration to verify
SELECT column_name, data_type, is_nullable, column_default
FROM information_schema.columns
WHERE table_name = 'your_table'
ORDER BY ordinal_position;

-- Verify index was created
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'your_table';
```

- [ ] Post-migration SQL verification queries run and output matches expected schema
- [ ] Application starts successfully against migrated database
- [ ] `./gradlew :api:test` passes (integration tests run against migrated schema)

---

## Performance Impact

- [ ] Estimated table size at time of migration: [N rows]
- [ ] Lock duration estimated: [< 1s / 1–10s / > 10s]
- [ ] If lock > 2s on a table with > 1M rows: scheduled maintenance window required
- [ ] `EXPLAIN ANALYZE` run on critical queries that touch the changed table

---

## Checklist Sign-Off

| Check | Person | Date |
|-------|--------|------|
| Migration file reviewed | | |
| Safety rules verified | | |
| Local test passed (fresh DB) | | |
| Local test passed (existing data) | | |
| Performance impact assessed | | |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
