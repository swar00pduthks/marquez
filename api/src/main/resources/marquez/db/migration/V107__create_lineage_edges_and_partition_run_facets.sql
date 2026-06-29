-- SPDX-License-Identifier: Apache-2.0
-- V107: Pre-materialized lineage edge table (multi-tenant partitioned)
--
-- PART 1: lineage_edges — replaces recursive CTE BFS with pre-computed adjacency
--   Written once per run at COMPLETE/FAIL time. Read as N simple indexed lookups
--   (one per depth level) instead of a single recursive CTE that grows exponentially.
--   This matches the OpenMetadata pattern but stays inside PostgreSQL.
--
--   Partitioning: composite RANGE (run_date, monthly) -> HASH (namespace, 8),
--   consistent with the denormalized tables (run_lineage_denormalized is
--   RANGE-by-run_date; dataset_denormalized is HASH-by-namespace). This gives:
--     - O(1) retention: DROP an old monthly partition instead of mass DELETE.
--     - Tenant isolation: each namespace hashes into its own physical sub-partition
--       so one high-volume tenant's writes/bloat/vacuum do not impact others.
--   At ~100K new edges/day this reaches ~73M rows over 2 years.
--
--   run_date and namespace are part of the PK. This is safe: every edge has a run
--   endpoint (run_uuid) whose date and namespace are fixed, so both are
--   functionally determined by the edge — adding them to the key never admits a
--   duplicate physical edge. The write path and backfill therefore use
--   ON CONFLICT (from_node_id, to_node_id, edge_type, run_date, namespace).

-- ============================================================
-- PART 1: lineage_edges pre-materialized adjacency table
-- ============================================================

CREATE TABLE IF NOT EXISTS lineage_edges (
    from_node_id   UUID        NOT NULL,
    from_type      TEXT        NOT NULL,  -- 'dataset_version' | 'run' | 'job'
    to_node_id     UUID        NOT NULL,
    to_type        TEXT        NOT NULL,
    edge_type      TEXT        NOT NULL,  -- 'PRODUCES' | 'CONSUMES'
    namespace      TEXT        NOT NULL,  -- namespace of the run endpoint (tenant)
    run_uuid       UUID        NOT NULL,  -- run endpoint of this edge
    run_date       DATE        NOT NULL,  -- range partition key; date of the run
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT lineage_edges_pk
        PRIMARY KEY (from_node_id, to_node_id, edge_type, run_date, namespace)
) PARTITION BY RANGE (run_date);

-- Monthly partitions, each sub-partitioned by HASH(namespace) into 8 buckets.
-- 2026 is pre-created here (consistent with V101 for the denormalized tables);
-- PartitionManagementService creates future months on the 1st. Any run_date
-- outside the created range lands in the DEFAULT partition below.
DO $$
DECLARE
    d     date := '2026-01-01';
    mname text;
    h     int;
BEGIN
    WHILE d < '2027-01-01' LOOP
        mname := 'lineage_edges_y' || to_char(d, 'YYYY') || 'm' || to_char(d, 'MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF lineage_edges '
            || 'FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (namespace)',
            mname, d, (d + INTERVAL '1 month')::date);
        FOR h IN 0..7 LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                || 'FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
                mname || '_h' || h, mname, h);
        END LOOP;
        d := (d + INTERVAL '1 month')::date;
    END LOOP;
END $$;

-- DEFAULT safety-net month, also HASH(namespace)-subpartitioned. Catches any
-- run_date outside the created range (alert when rows land here — it means a
-- monthly partition is missing).
CREATE TABLE IF NOT EXISTS lineage_edges_default
    PARTITION OF lineage_edges DEFAULT PARTITION BY HASH (namespace);
DO $$
DECLARE h int;
BEGIN
    FOR h IN 0..7 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS lineage_edges_default_h%s '
            || 'PARTITION OF lineage_edges_default FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
            h, h);
    END LOOP;
END $$;

-- Indexes on the parent propagate to every partition (existing + future):
-- downstream traversal: SELECT to_node_id WHERE from_node_id IN (...)
-- upstream traversal:   SELECT from_node_id WHERE to_node_id IN (...)
CREATE INDEX IF NOT EXISTS idx_lineage_edges_downstream
    ON lineage_edges (from_node_id, to_type, run_date DESC);

CREATE INDEX IF NOT EXISTS idx_lineage_edges_upstream
    ON lineage_edges (to_node_id, from_type, run_date DESC);

-- Covering index for run-scoped lookups (find all edges for a run)
CREATE INDEX IF NOT EXISTS idx_lineage_edges_run
    ON lineage_edges (run_uuid, run_date DESC);

-- ============================================================
-- PART 2: Backfill lineage_edges from existing normalized data
-- ============================================================
-- One-time backfill of historical edges; new edges are written by
-- DenormalizedLineageService at COMPLETE/FAIL/ABORT event time.

-- Input dataset_version -> run edges (CONSUMES)
INSERT INTO lineage_edges (
    from_node_id, from_type, to_node_id, to_type, edge_type,
    namespace, run_uuid, run_date, created_at
)
SELECT
    rim.dataset_version_uuid  AS from_node_id,
    'dataset_version'         AS from_type,
    r.uuid                    AS to_node_id,
    'run'                     AS to_type,
    'CONSUMES'                AS edge_type,
    r.namespace_name          AS namespace,
    r.uuid                    AS run_uuid,
    r.created_at::date        AS run_date,
    r.created_at              AS created_at
FROM runs_input_mapping rim
INNER JOIN runs r ON r.uuid = rim.run_uuid
WHERE r.current_run_state IN ('COMPLETED', 'FAILED', 'ABORTED')
ON CONFLICT (from_node_id, to_node_id, edge_type, run_date, namespace) DO NOTHING;

-- Output run -> dataset_version edges (PRODUCES)
INSERT INTO lineage_edges (
    from_node_id, from_type, to_node_id, to_type, edge_type,
    namespace, run_uuid, run_date, created_at
)
SELECT
    dv.run_uuid               AS from_node_id,
    'run'                     AS from_type,
    dv.uuid                   AS to_node_id,
    'dataset_version'         AS to_type,
    'PRODUCES'                AS edge_type,
    r.namespace_name          AS namespace,
    dv.run_uuid               AS run_uuid,
    dv.created_at::date       AS run_date,
    dv.created_at             AS created_at
FROM dataset_versions dv
INNER JOIN runs r ON r.uuid = dv.run_uuid
WHERE dv.run_uuid IS NOT NULL
  AND r.current_run_state IN ('COMPLETED', 'FAILED', 'ABORTED')
ON CONFLICT (from_node_id, to_node_id, edge_type, run_date, namespace) DO NOTHING;

-- ============================================================
-- TODO: large raw-table partitioning (V108)
-- ============================================================
-- run_facets (~7.3B rows/2yr), dataset_facets, and lineage_events use the same
-- composite RANGE(event_date) -> HASH(namespace) scheme. They are populated, so
-- V108 partitions them via shadow-table + batched copy + atomic rename swap in a
-- maintenance window, and adds a namespace column to run_facets/dataset_facets
-- (lineage_events already has job_namespace). Estimate before scheduling:
--   SELECT pg_size_pretty(pg_total_relation_size('run_facets'));
--   SELECT count(*) FROM run_facets;
