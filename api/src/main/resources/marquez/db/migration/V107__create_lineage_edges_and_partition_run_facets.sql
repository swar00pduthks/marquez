-- SPDX-License-Identifier: Apache-2.0
-- V107: Pre-materialized lineage edge table + run_facets partitioning advisory
--
-- PART 1: lineage_edges — replaces recursive CTE BFS with pre-computed adjacency
--   Written once per run at COMPLETE/FAIL time. Read as N simple indexed lookups
--   (one per depth level) instead of a single recursive CTE that grows exponentially.
--   This matches the OpenMetadata pattern but stays inside PostgreSQL.
--
--   Design note: lineage_edges is intentionally NOT partitioned.
--   It stores unique logical edges (one row per physical dataset→dataset connection,
--   regardless of how many runs traverse that edge). At ~30K new edges/day the table
--   reaches ~2 GB over 2 years — no partitioning needed. Partitioning on run_date
--   would also require including run_date in the PRIMARY KEY, which breaks the
--   global uniqueness constraint on (from_node_id, to_node_id, edge_type).
--
-- PART 2: run_facets — advisory comment only
--   run_facets reaches 10M rows/day at 10 facets × 1M events/day.
--   At 2 years that is 7.3 billion rows. It must be range-partitioned.
--   The actual migration requires a maintenance window and is tracked separately
--   to allow scheduling — see the TODO below.

-- ============================================================
-- PART 1: lineage_edges pre-materialized adjacency table
-- ============================================================

CREATE TABLE IF NOT EXISTS lineage_edges (
    from_node_id   UUID        NOT NULL,
    from_type      TEXT        NOT NULL,  -- 'dataset_version' | 'run' | 'job'
    to_node_id     UUID        NOT NULL,
    to_type        TEXT        NOT NULL,
    edge_type      TEXT        NOT NULL,  -- 'PRODUCES' | 'CONSUMES'
    run_uuid       UUID        NOT NULL,  -- run that first established this edge
    run_date       DATE        NOT NULL,  -- informational: date edge was first written
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Global uniqueness: one row per physical edge regardless of which run produced it
    CONSTRAINT lineage_edges_pk PRIMARY KEY (from_node_id, to_node_id, edge_type)
);

-- Indexes for bidirectional BFS from application code:
-- downstream traversal: SELECT to_node_id WHERE from_node_id IN (...)
-- upstream traversal:   SELECT from_node_id WHERE to_node_id IN (...)
-- Note: CREATE INDEX without CONCURRENTLY is required inside Flyway migrations.
-- For production systems with millions of existing rows, these can be recreated
-- with CONCURRENTLY after deployment:
--   DROP INDEX idx_lineage_edges_downstream;
--   CREATE INDEX CONCURRENTLY idx_lineage_edges_downstream ON lineage_edges (from_node_id, to_type, run_date DESC);
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
-- Populate historical dataset_version → run edges from runs_input_mapping.
-- This is a one-time backfill; new edges are written by DenormalizedLineageService
-- at COMPLETE/FAIL event time.

INSERT INTO lineage_edges (
    from_node_id,
    from_type,
    to_node_id,
    to_type,
    edge_type,
    run_uuid,
    run_date,
    created_at
)
SELECT
    rim.dataset_version_uuid  AS from_node_id,
    'dataset_version'         AS from_type,
    r.uuid                    AS to_node_id,
    'run'                     AS to_type,
    'CONSUMES'                AS edge_type,
    r.uuid                    AS run_uuid,
    r.created_at::date        AS run_date,
    r.created_at              AS created_at
FROM runs_input_mapping rim
INNER JOIN runs r ON r.uuid = rim.run_uuid
WHERE r.current_run_state IN ('COMPLETE', 'FAILED', 'ABORTED')
ON CONFLICT (from_node_id, to_node_id, edge_type) DO NOTHING;

-- Output (run) → dataset_version edges
INSERT INTO lineage_edges (
    from_node_id,
    from_type,
    to_node_id,
    to_type,
    edge_type,
    run_uuid,
    run_date,
    created_at
)
SELECT
    dv.run_uuid               AS from_node_id,
    'run'                     AS from_type,
    dv.uuid                   AS to_node_id,
    'dataset_version'         AS to_type,
    'PRODUCES'                AS edge_type,
    dv.run_uuid               AS run_uuid,
    dv.created_at::date       AS run_date,
    dv.created_at             AS created_at
FROM dataset_versions dv
INNER JOIN runs r ON r.uuid = dv.run_uuid
WHERE dv.run_uuid IS NOT NULL
  AND r.current_run_state IN ('COMPLETE', 'FAILED', 'ABORTED')
ON CONFLICT (from_node_id, to_node_id, edge_type) DO NOTHING;

-- ============================================================
-- TODO: run_facets partitioning
-- ============================================================
-- run_facets grows at 10M rows/day (10 facets × 1M events/day).
-- At this rate it reaches 7.3 billion rows in 2 years (~1.1 TB).
-- Partitioning requires:
--   1. CREATE TABLE run_facets_new (...) PARTITION BY RANGE (lineage_event_time)
--   2. Create monthly partitions 2024-01 through 2026-12
--   3. COPY data from run_facets to run_facets_new in batches
--   4. Rename tables atomically
-- This migration is tracked separately as V108 and requires a maintenance window.
-- Run the following to estimate current table size before scheduling:
--   SELECT pg_size_pretty(pg_total_relation_size('run_facets'));
--   SELECT count(*) FROM run_facets;
