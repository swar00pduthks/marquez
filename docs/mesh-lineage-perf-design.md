# Mesh Lineage Performance Design — V1 Hardening & V2/V3 Parity

**Scope:** 1 million OpenLineage events/day (≈5,000 Spark job runs/day × 200 events/run), 2-year retention, PostgreSQL backend.

---

## 0. The Core Bottleneck: Spark Writes 200 Events Per Job

Spark's OpenLineage integration emits one event per stage transition plus heartbeats. A typical run produces:
- 1 `START` — no datasets yet
- ~197 `RUNNING` — metrics updates; **lineage graph unchanged**
- 1 `COMPLETE` or `FAIL` — full dataset lineage

Every event today hits the same code path in `OpenLineageService`:

```
POST /api/v1/lineage
  └─ updateMarquezModel()          ← always hits normalized tables
  └─ populateDenormalizedEntitiesForEvent()  ← always upserts denorm tables
```

This means **198 of 200 events per Spark job are pure write churn** into `run_lineage_denormalized` and `run_parent_lineage_denormalized` — touching the same rows each time via `ON CONFLICT DO UPDATE` with identical data.

### Write IOPS Math

| Factor | Value |
|---|---|
| Job runs/day | 5,000 |
| Spark events/run | 200 |
| Avg datasets per run (inputs × outputs) | 10 × 3 = 30 rows in denorm |
| Denorm table upserts/day | 5,000 × 200 × 30 = **30 million** |
| Useful writes (only COMPLETE/FAIL) | 5,000 × 30 = **150,000** |
| Write amplification factor | **200×** |

Each upsert costs: index probe + heap fetch + row update + WAL write + 3 index updates.

---

## 1. Bug Inventory (Production Impact)

### BUG-01: Tautology in `getDatasetVersionData` — always wrong facets

**File:** `api/src/main/java/marquez/db/LineageDao.java:651`

```sql
-- CURRENT (always TRUE — never filters by dataset version)
WHERE dvf.run_uuid = dvf.run_uuid

-- SHOULD BE
WHERE dvf.run_uuid = dv.run_uuid
```

**Impact:** Every dataset version returns ALL facets for every run that ever touched it, not just the run that produced that version. Causes massively bloated responses and wrong data.

### BUG-02: `log.info()` on hot path serializes huge collections

**File:** `api/src/main/java/marquez/service/LineageService.java:537`

```java
// CURRENT — serializes all dataset version IDs to string on EVERY request
log.info("DatasetVersionIds found in run data: {}", datasetVersionIds);

// SHOULD BE
log.debug("DatasetVersionIds found in run data: {}", datasetVersionIds);
```

**Impact:** At 1M events/day with log level INFO (typical in prod), this serializes a large `Set<DatasetVersionId>` to string on every lineage READ request, wasting CPU.

### BUG-03: `OR` condition in recursive JOIN prevents index use

**File:** `api/src/main/java/marquez/db/LineageDao.java:309`

```sql
-- CURRENT — forces BitmapOr, may degrade to seq scan on the CTE work table
JOIN lineage l
  ON (io.input_version_uuid = l.output_version_uuid
      OR io.output_version_uuid = l.input_version_uuid)

-- SHOULD BE (two separate arms — planner can use each index independently)
JOIN lineage l ON io.input_version_uuid = l.output_version_uuid
  WHERE l.depth < :depth
UNION ALL
SELECT ... FROM run_lineage_denormalized io
JOIN lineage l ON io.output_version_uuid = l.input_version_uuid
  AND io.run_uuid != l.run_uuid
  WHERE l.depth < :depth
```

**Impact:** At scale, PostgreSQL's recursive CTE work table is not indexed. The OR join causes a sequential scan of the materialized CTE at every recursion level.

### BUG-04: V2 lineage falls back to V1 for run/dataset-version nodes

**File:** `api/src/main/java/marquez/service/LineageService.java:202-204`

```java
// CURRENT — V2 optimization bypassed entirely for run-type nodes
if (nodeId.isRunType() || nodeId.isDatasetVersionType()) {
    return lineage(nodeId, depth, aggregateToParentRun, includeFacets); // ← V1 path
}
```

**Impact:** V2 API returns no performance benefit when queried by run ID — the most common UI access pattern. V2 parity is broken.

### BUG-05: `getDatasetDataV2` fallback creates silent inconsistency

**File:** `api/src/main/java/marquez/service/LineageService.java:253-258`

```java
datasets.addAll(this.getDatasetDataV2(datasetIds));
if (datasets.isEmpty()) {
    datasets.addAll(this.getDatasetData(datasetIds)); // ← falls back to V1 normalized table
}
```

**Impact:** If denorm tables are stale (e.g., event lag), V2 silently returns V1 data. The caller cannot know which path was taken. This causes inconsistency between V2 calls for the same node.

### BUG-06: Unique constraint allows NULL accumulation

**File:** V89 migration

```sql
UNIQUE (run_uuid, input_version_uuid, output_version_uuid, run_date)
```

In PostgreSQL, `(run_uuid, NULL, NULL, run_date)` is **not** considered a duplicate — each NULL row is treated as distinct. A `START` event with no datasets inserts a row with `NULL` version UUIDs; every subsequent RUNNING event inserts another duplicate NULL row because the constraint is not violated. This silently accumulates garbage rows.

**Fix:** Add a partial unique index or use `COALESCE(input_version_uuid, '00000000-0000-0000-0000-000000000000')` in a unique expression index.

---

## 2. Write Path Redesign

### 2a. Gate Denormalized Writes on Event Type

The single highest-impact change: **only write to denormalized tables on terminal or lineage-changing events**.

```java
// OpenLineageService.java — inside the marquez.thenAccept() lambda

private static final Set<String> LINEAGE_CHANGING_EVENT_TYPES =
    Set.of("START", "COMPLETE", "FAIL", "ABORT");

// Skip expensive denorm write for pure RUNNING metric events from Spark
String eventType = event.getEventType() != null
    ? event.getEventType().toUpperCase()
    : "OTHER";

boolean hasNewDatasets = (update.getInputs().isPresent() && !update.getInputs().get().isEmpty())
    || (update.getOutputs().isPresent() && !update.getOutputs().get().isEmpty());

if (LINEAGE_CHANGING_EVENT_TYPES.contains(eventType) || hasNewDatasets) {
    denormalizedLineageService.populateDenormalizedEntitiesForEvent(
        update.getNamespace().getUuid(), jobUuid, datasetUuids);
}
```

**Expected IOPS reduction:** 200× → from 30M/day to ~150K/day meaningful writes.

### 2b. Deduplicate Run Lineage Denorm Rows at Upsert

The current upsert re-writes all columns on `ON CONFLICT`. Change to a conditional update that skips if nothing changed:

```sql
INSERT INTO run_lineage_denormalized (run_uuid, ..., run_date)
VALUES (:runUuid, ..., :runDate)
ON CONFLICT (run_uuid, COALESCE(input_version_uuid, uuid_nil()), 
             COALESCE(output_version_uuid, uuid_nil()), run_date)
DO UPDATE SET
    state = EXCLUDED.state,
    ended_at = EXCLUDED.ended_at,
    updated_at = EXCLUDED.updated_at
WHERE run_lineage_denormalized.state IS DISTINCT FROM EXCLUDED.state
   OR run_lineage_denormalized.ended_at IS DISTINCT FROM EXCLUDED.ended_at;
```

The `WHERE` clause makes the update a no-op when nothing changed, eliminating WAL writes for redundant events.

### 2c. Replace `run_lineage_denormalized` Row-per-Pair Model

**Current problem:** The unique constraint `(run_uuid, input_version_uuid, output_version_uuid, run_date)` stores one row per input×output pair. A Spark job with 10 inputs and 3 outputs = 30 rows per run.

**Better design:** Store one row per run_uuid with arrays:

```sql
-- TARGET SCHEMA for run_lineage_denormalized (V106)
CREATE TABLE run_lineage_summary (
    run_uuid          UUID        NOT NULL,
    run_date          DATE        NOT NULL,
    namespace_name    TEXT        NOT NULL,
    job_name          TEXT        NOT NULL,
    job_uuid          UUID,
    job_version_uuid  UUID,
    state             TEXT,
    started_at        TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    created_at        TIMESTAMPTZ,
    updated_at        TIMESTAMPTZ,
    parent_run_uuid   UUID,
    -- Arrays replace the N×M row explosion
    input_version_uuids  UUID[]  NOT NULL DEFAULT '{}',
    output_version_uuids UUID[]  NOT NULL DEFAULT '{}',
    -- Lightweight JSON payloads only — no facets
    input_datasets    JSONB     NOT NULL DEFAULT '[]',
    output_datasets   JSONB     NOT NULL DEFAULT '[]',
    PRIMARY KEY (run_uuid, run_date)
) PARTITION BY RANGE (run_date);
```

**Storage impact at 1M events/day (5,000 runs/day):**

| Design | Rows/day | Row size | Storage/day | 2-year total |
|---|---|---|---|---|
| Current row-per-pair | 150,000 | ~500B | ~75 MB | ~54 GB |
| Proposed row-per-run | 5,000 | ~1KB | ~5 MB | ~3.6 GB |
| Reduction | **30×** | — | **15×** | **15×** |

**Recursive CTE becomes simpler:**

```sql
WITH RECURSIVE lineage AS (
    -- base
    SELECT r.run_uuid, r.input_version_uuids, r.output_version_uuids, 0 AS depth
    FROM run_lineage_summary r
    WHERE r.run_uuid IN (<runIds>)
      AND r.run_date >= :minDate::date AND r.run_date <= :maxDate::date

    UNION ALL

    -- upstream: next run's outputs overlap this run's inputs
    SELECT io.run_uuid, io.input_version_uuids, io.output_version_uuids, l.depth + 1
    FROM run_lineage_summary io, lineage l
    WHERE io.output_version_uuids && l.input_version_uuids   -- GIN overlap operator
      AND io.run_uuid != l.run_uuid
      AND l.depth < :depth
      AND io.run_date >= :minDate::date AND io.run_date <= :maxDate::date
    UNION ALL
    -- downstream: next run's inputs overlap this run's outputs
    SELECT io.run_uuid, io.input_version_uuids, io.output_version_uuids, l.depth + 1
    FROM run_lineage_summary io, lineage l
    WHERE io.input_version_uuids && l.output_version_uuids
      AND io.run_uuid != l.run_uuid
      AND l.depth < :depth
      AND io.run_date >= :minDate::date AND io.run_date <= :maxDate::date
)
SELECT DISTINCT ON (run_uuid) run_uuid, input_version_uuids, output_version_uuids, MIN(depth) AS depth
FROM lineage
GROUP BY run_uuid, input_version_uuids, output_version_uuids
```

**Required index:**

```sql
-- GIN index enables fast && (array overlap) operator
CREATE INDEX idx_run_lineage_summary_input_versions
    ON run_lineage_summary USING GIN (input_version_uuids);

CREATE INDEX idx_run_lineage_summary_output_versions
    ON run_lineage_summary USING GIN (output_version_uuids);

CREATE INDEX idx_run_lineage_summary_run_uuid
    ON run_lineage_summary (run_uuid, run_date);
```

---

## 3. Read Path Redesign

### 3a. Eliminate Extra Round-Trip for Partition Pruning

**Current:** `LineageService.lineage()` makes TWO sequential DB calls:
1. `getRunDateRange(runIds)` → hits `runs` table
2. Actual lineage query with date bounds

**Fix:** Store `run_date` in the initial `getLatestRunUuidsForJobV2()` and `getSeedRunUuidsForDatasetV2()` lookups — return it alongside run UUIDs. Alternatively, pass the date bounds from the HTTP request context (the UI knows the time range the user is browsing).

```java
// Add a record that carries both UUID and date
record RunWithDate(UUID runUuid, LocalDate runDate) {}

// getLatestRunUuidsForJobV2 returns Set<RunWithDate>
// → no extra query needed for partition pruning
```

### 3b. Fix `getDatasetVersionData` N+1 and Tautology

The method is called ONCE after the lineage query but fetches ALL dataset version data, including a full `JSONB_AGG` of facets. At large depth, a lineage graph may contain 200+ dataset versions, causing a large `IN (...)` list and massive JSON aggregation.

**Fix — lazy facet loading:**

```sql
-- Fast path (no facets): covering index scan
SELECT dv.uuid, d.type, d.name, d.physical_name, d.namespace_name,
       d.source_name, d.description, dv.lifecycle_state,
       dv.created_at, dv.uuid AS current_version_uuid, dv.version,
       sv.schema_location, t.tags
FROM dataset_versions dv
INNER JOIN datasets_view d ON d.uuid = dv.dataset_uuid
LEFT JOIN stream_versions sv ON sv.dataset_version_uuid = dv.uuid
LEFT JOIN (...tags subquery...) t ON t.dataset_uuid = dv.dataset_uuid
WHERE dv.uuid IN (<versions>)
-- facets loaded separately ONLY when includeFacets is specified
```

### 3c. Eliminate `JSON_AGG(DISTINCT jsonb_build_object(...))` CPU Spike

JSONB DISTINCT requires sorting by JSONB internal comparison (expensive). Replace with:

```sql
-- Use subquery to deduplicate before aggregation
JSON_AGG(sub.obj) AS input_versions
FROM (
    SELECT DISTINCT ON (input_dataset_version_uuid)
        jsonb_build_object(
            'namespace', input_dataset_namespace,
            'name', input_dataset_name,
            'version', input_dataset_version,
            'dataset_version_uuid', input_dataset_version_uuid
        ) AS obj
    FROM lineage
    WHERE input_dataset_name IS NOT NULL
    ORDER BY input_dataset_version_uuid
) sub
```

`DISTINCT ON` with an ORDER BY is an O(n log n) sort on a UUID key — far cheaper than JSONB comparison.

### 3d. Cap Depth and Response Size

**Current:** No server-side depth cap. A client can request `depth=100`.

**Fix — enforced caps per API version:**

```java
// LineageResource.java
private static final int MAX_DEPTH_V1 = 10;
private static final int MAX_DEPTH_V2 = 20;
private static final int MAX_NODE_COUNT = 500;

// After graph construction
if (nodes.size() > MAX_NODE_COUNT) {
    // Truncate to MAX_NODE_COUNT nodes, add a `truncated: true` meta field
    log.warn("Lineage graph truncated: {} nodes exceeded max {}", nodes.size(), MAX_NODE_COUNT);
}
```

### 3e. Add `work_mem` and `statement_timeout` Per Query

Recursive CTEs on large graphs consume unbounded memory. Set per-transaction limits:

```java
// In LineageDao — before executing recursive lineage queries
handle.execute("SET LOCAL work_mem = '128MB'");
handle.execute("SET LOCAL statement_timeout = '30s'");
```

---

## 4. V2 Parity Fixes

### 4a. V2 Run-Type Nodes Must Use Denormalized Path

```java
// LineageService.lineageV2() — replace the fallback:

public Lineage lineageV2(NodeId nodeId, int depth, boolean aggregateToParentRun,
                          Set<String> includeFacets) {
    if (nodeId.isRunType() || nodeId.isDatasetVersionType()) {
        // V2 ALSO uses denormalized tables for run nodes — NOT a fallback to V1
        Set<UUID> runIds = resolveRunIds(nodeId, aggregateToParentRun);
        if (runIds.isEmpty()) return new Lineage(ImmutableSortedSet.of());
        
        RunDateRange dateRange = getRunDateRange(runIds);
        String minDate = dateRange != null ? dateRange.minDate().toString() : null;
        String maxDate = dateRange != null ? dateRange.maxDate().toString() : null;
        
        Set<RunData> runData = (aggregateToParentRun && hasChildRuns(runIds))
            ? getParentRunLineage(runIds, depth, minDate, maxDate)
            : getRunLineage(runIds, depth, minDate, maxDate);
        
        return toRunLineage(runData);
    }
    // ... job/dataset path continues as before
}
```

### 4b. Remove Silent V2 → V1 Fallback for Dataset Data

The fallback `if (datasets.isEmpty()) { datasets.addAll(this.getDatasetData(datasetIds)); }` in `lineageV2()` must be replaced with a proper error or explicit fallback indicator:

```java
datasets.addAll(this.getDatasetDataV2(datasetIds));
if (datasets.isEmpty()) {
    // Log as warning — denorm tables may be lagging
    log.warn("V2 dataset lookup returned empty for {} UUIDs; denorm tables may be stale", 
             datasetIds.size());
    // Do NOT silently fall back — return the V2 response with empty datasets
    // and let the client retry or show stale-state indicator
}
```

### 4c. `job_denormalized` Missing `namespace_name`

The V88 schema for `job_denormalized` stores `namespace_uuid` (not `namespace_name`). The `getLineageV2` query at LineageDao:700 selects `j.namespace_name`, which relies on a JOIN to `namespaces` not present in the query. This is a latent query error.

**Fix:** Either add `namespace_name` as a denormalized column to `job_denormalized` (consistent with the dataset/run denorm design), or join to `namespaces` explicitly.

---

## 5. Data Retention — 2-Year Strategy

### 5a. Automated Partition Lifecycle

Use `pg_partman` or a Quartz job to:
1. Create next month's partition on the 1st of each month (already exists in `PartitionManagementService`)
2. **Detach partitions older than 24 months** from the parent table (they become standalone tables)
3. `VACUUM ANALYZE` the detached partition
4. Move to cold storage (S3 via `pg_partman` + `pg_dump`) or drop

```sql
-- Monthly maintenance job (add to PartitionManagementService)
-- Detach partition from 25 months ago (keeps 2 years accessible, detaches the 25th)
SELECT detach_monthly_partition('run_lineage_denormalized',
    DATE_TRUNC('month', NOW() - INTERVAL '25 months')::date);
```

### 5b. Add a `DEFAULT` Partition as Safety Net

```sql
-- Add a catch-all partition — prevents hard errors for out-of-range dates
CREATE TABLE run_lineage_denormalized_default
    PARTITION OF run_lineage_denormalized DEFAULT;

CREATE TABLE run_parent_lineage_denormalized_default
    PARTITION OF run_parent_lineage_denormalized DEFAULT;
```

Alert when rows land in the DEFAULT partition — it means the partition creation job failed.

### 5c. Normalized Table Retention

The `lineage_events`, `runs`, `run_facets`, `dataset_versions`, and `run_states` tables are NOT partitioned. At 1M events/day × 730 days:

| Table | Rows at 2 years |
|---|---|
| lineage_events | ~730M |
| runs | ~3.65M (5,000/day) |
| run_states | ~14.6M (4 states/run) |
| run_facets | ~7.3B (at 10 facets/event × 200 events) |
| dataset_versions | ~7.3M |

**`run_facets` is the critical one**: at 10 facets/event × 1M events/day = 10M rows/day → **7.3 billion rows** over 2 years. This must be partitioned.

```sql
-- V106: Partition run_facets by lineage_event_time
-- (requires data migration — do as background job)
CREATE TABLE run_facets_partitioned (
    LIKE run_facets INCLUDING ALL
) PARTITION BY RANGE (lineage_event_time);
```

### 5d. Retention Policy Table

```sql
CREATE TABLE retention_policy (
    table_name TEXT PRIMARY KEY,
    retention_months INT NOT NULL,
    action TEXT NOT NULL DEFAULT 'DROP', -- DROP or ARCHIVE
    last_applied TIMESTAMPTZ
);

INSERT INTO retention_policy VALUES
    ('run_lineage_denormalized', 24, 'DROP', NULL),
    ('run_parent_lineage_denormalized', 24, 'DROP', NULL),
    ('lineage_events', 24, 'DROP', NULL),
    ('run_facets', 12, 'DROP', NULL);  -- facets only kept 12 months
```

---

## 6. V3 / Apache AGE Design

### Current Problem

V3 ingestion is synchronous — writes to both relational store AND AGE graph in the same request:

```java
// V3 is BLOCKING — user waits for both relational + graph write
CompletableFuture<Void> graphFuture = ... graphWriter.write(event, graphJdbi) ...
CompletableFuture.allOf(relationalFuture, graphFuture).join(); // ← blocks
```

At 200 Spark events/job this doubles the write latency for V3.

### Fix: Async AGE Graph Writes

Move AGE graph writes to the same async executor as the denormalized table writes. Graph writes should never block the HTTP response:

```java
// AGE write — fire and forget, same pattern as denorm writes
CompletableFuture.runAsync(() -> {
    try {
        graphWriter.write(event, graphJdbi);
    } catch (Exception e) {
        log.error("AGE graph write failed for run {}", runId, e);
        // Increment metric for monitoring; do NOT throw
    }
}, executor);
```

### V3 Read Parity with V1

V3 reads use Apache AGE Cypher queries. The current gap:
1. V3 does not support `aggregateToParentRun`
2. V3 does not support `includeFacets`
3. V3 depth is hardcoded at 2 (vs configurable in V1/V2)

These must match V1 signature before V3 is production-ready.

---

## 7. Missing Indexes

| Index | Table | Columns | Reason |
|---|---|---|---|
| `idx_runs_parent_run_uuid` | `runs` | `parent_run_uuid` | `hasChildRuns()` does full scan |
| `idx_run_facets_run_uuid` | `run_facets` | `run_uuid, name` | Already in V104 |
| `idx_job_versions_io_is_current` | `job_versions_io_mapping` | `(is_current_job_version, job_uuid)` | V1 BFS scans entire table |
| `idx_dataset_versions_run_uuid` | `dataset_versions` | `run_uuid` | `getUpstreamRuns` recursive join |
| `idx_runs_input_mapping_run_uuid` | `runs_input_mapping` | `run_uuid` | `getUpstreamRuns` initial case |
| `idx_run_lineage_summary_gin_in` | `run_lineage_summary` | `GIN(input_version_uuids)` | New schema array overlap |
| `idx_run_lineage_summary_gin_out` | `run_lineage_summary` | `GIN(output_version_uuids)` | New schema array overlap |

---

## 8. Implementation Phases

### Phase 1 — Immediate (Days 1–3, zero schema change)

| # | Change | File | Impact |
|---|---|---|---|
| P1-1 | Fix `dvf.run_uuid = dvf.run_uuid` tautology | `LineageDao.java:651` | Correct facets data |
| P1-2 | Change `log.info` → `log.debug` on hot path | `LineageService.java:537,587-590` | CPU reduction |
| P1-3 | Gate `populateDenormalizedEntitiesForEvent` on eventType | `OpenLineageService.java:179` | 200× write IOPS reduction |
| P1-4 | Add `statement_timeout='30s'` to lineage DAO calls | `LineageDao.java` | Prevent runaway queries |
| P1-5 | Add depth cap (max 10 V1, max 20 V2) in service layer | `LineageService.java` | Prevent OOM |

### Phase 2 — Short-term (Weeks 1–2, schema additive)

| # | Change | File | Impact |
|---|---|---|---|
| P2-1 | Add `DEFAULT` partition to run_lineage_denormalized | V106 migration | Prevent hard insert errors |
| P2-2 | Add missing indexes (runs.parent_run_uuid, etc.) | V107 migration | Read performance |
| P2-3 | Fix OR join → UNION ALL in recursive CTE | `LineageDao.java:307-313` | Index utilization |
| P2-4 | Fix V2 run-type node fallback | `LineageService.java:202` | V2 parity |
| P2-5 | Remove silent V2→V1 dataset fallback | `LineageService.java:253` | V2 correctness |
| P2-6 | Partition `run_facets` table | V108 migration | 7.3B row problem |
| P2-7 | Upsert only on state change (`WHERE ... IS DISTINCT FROM`) | `DenormalizedLineageService.java` | WAL reduction |

### Phase 3 — Medium-term (Month 1, schema replacement)

| # | Change | Impact |
|---|---|---|
| P3-1 | Create `run_lineage_summary` (1 row/run, array-based) | 30× storage reduction, simpler recursive CTE |
| P3-2 | Migrate V1 recursive CTE to use new schema | Query simplification |
| P3-3 | Implement partition detach/archive job | 2-year retention enforcement |
| P3-4 | Add `job_denormalized.namespace_name` column | V2 correctness |
| P3-5 | Make AGE writes async | V3 write latency |

### Phase 4 — Long-term (Month 2+, API parity)

| # | Change |
|---|---|
| P4-1 | V3 `aggregateToParentRun` support |
| P4-2 | V3 `includeFacets` support |
| P4-3 | V3 configurable depth |
| P4-4 | Pagination for lineage responses (cursor-based) |
| P4-5 | Field projection (`?fields=uuid,job_name,state`) |
| P4-6 | Async lineage API (webhook/polling) for depth > 5 |

---

## 9. Monitoring Queries

```sql
-- Check if any rows landed in DEFAULT partition (partition management failure)
SELECT COUNT(*) FROM run_lineage_denormalized_default;

-- Find the top write amplifiers (runs with most denorm rows)
SELECT run_uuid, COUNT(*) AS row_count
FROM run_lineage_denormalized
GROUP BY run_uuid
ORDER BY row_count DESC
LIMIT 20;

-- Long-running lineage queries (requires pg_stat_statements)
SELECT query, calls, mean_exec_time, max_exec_time
FROM pg_stat_statements
WHERE query ILIKE '%run_lineage_denormalized%'
ORDER BY max_exec_time DESC
LIMIT 10;

-- Partition sizes
SELECT
    c.relname AS partition,
    pg_size_pretty(pg_total_relation_size(c.oid)) AS size,
    pg_stat_user_tables.n_live_tup AS live_rows
FROM pg_class c
JOIN pg_inherits i ON i.inhrelid = c.oid
JOIN pg_class p ON p.oid = i.inhparent
JOIN pg_stat_user_tables ON pg_stat_user_tables.relname = c.relname
WHERE p.relname = 'run_lineage_denormalized'
ORDER BY c.relname;

-- Upsert conflict rate (high = write churn from RUNNING events)
SELECT n_tup_upd, n_tup_ins, n_tup_upd::float / NULLIF(n_tup_ins + n_tup_upd, 0) AS churn_ratio
FROM pg_stat_user_tables
WHERE relname LIKE 'run_lineage_denormalized%';
```

---

## 10. Configuration Recommendations

```yaml
# config.yml — HikariCP tuning for 1M events/day write load
database:
  maxSize: 30                    # Default is ~10; write path needs more connections
  minSize: 10
  maxWaitForConnection: 5s
  validationQuery: "SELECT 1"
  connectionTimeout: 5000
  idleTimeout: 600000
  maxLifetime: 1800000

# PostgreSQL postgresql.conf recommendations
shared_buffers: 4GB              # 25% of RAM
effective_cache_size: 12GB       # 75% of RAM
work_mem: 64MB                   # per-sort; recursive CTEs use multiple sorts
maintenance_work_mem: 1GB        # for VACUUM, index builds
max_parallel_workers_per_gather: 4
wal_buffers: 64MB
checkpoint_completion_target: 0.9
```

---

## Appendix: Spark OpenLineage Event Profile

A typical Spark application on this system:

```
Event 1:  type=START,    inputs=[],           outputs=[]          ← no datasets yet
Events 2–198: type=RUNNING, inputs=[same],   outputs=[same]      ← metric updates only
Event 199: type=RUNNING, inputs=[10 tables], outputs=[3 tables]  ← datasets appear at stage end
Event 200: type=COMPLETE, inputs=[10 tables], outputs=[3 tables] ← terminal
```

Only events 199 and 200 carry actionable lineage data. The current system processes all 200 identically.

With Phase 1 change P1-3, events 2–198 skip the denorm write entirely. This is safe because:
- The denorm table is populated from the normalized tables (`runs`, `runs_input_mapping`, `dataset_versions`)
- The normalized tables are updated on every event (required for run state tracking)
- The denorm write is a redundant cache of normalized data
