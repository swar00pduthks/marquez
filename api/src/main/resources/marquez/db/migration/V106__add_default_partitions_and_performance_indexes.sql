-- SPDX-License-Identifier: Apache-2.0
-- V106: Safety-net DEFAULT partitions + missing indexes for read/write hotspots
--
-- PART 1 — DEFAULT partitions
--   Prevent hard "no partition found" errors if the PartitionManagementJob misses a month.
--   Rows landing here trigger the monitoring query (see design doc) to alert on-call.
--
-- PART 2 — Missing performance indexes
--   Five indexes identified as missing by the mesh lineage performance review:
--   a) runs.parent_run_uuid — hasChildRuns() currently seq-scans millions of rows
--   b) runs_input_mapping.run_uuid — getUpstreamRuns() base case missing index
--   c) dataset_versions.run_uuid — getUpstreamRuns() LEFT JOIN missing index
--   d) job_versions_io_mapping composite — V1 BFS CTE scans entire table
--   e) run_lineage_denormalized version pair — recursive CTE traversal join
--
-- All CREATE INDEX use IF NOT EXISTS so this migration is safe to re-run.

-- ============================================================
-- PART 1: DEFAULT partitions as safety nets
-- ============================================================

CREATE TABLE IF NOT EXISTS run_lineage_denormalized_default
    PARTITION OF run_lineage_denormalized DEFAULT;

CREATE TABLE IF NOT EXISTS run_parent_lineage_denormalized_default
    PARTITION OF run_parent_lineage_denormalized DEFAULT;

-- ============================================================
-- PART 2: Missing indexes
-- ============================================================

-- (a) Fast child-run existence check used by hasChildRuns()
--     Query: SELECT EXISTS (SELECT 1 FROM runs WHERE parent_run_uuid IN (...))
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_parent_run_uuid
    ON runs (parent_run_uuid)
    WHERE parent_run_uuid IS NOT NULL;

-- (b) getUpstreamRuns() initial case: LEFT JOIN runs_input_mapping ON run_uuid = r.uuid
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runs_input_mapping_run_uuid
    ON runs_input_mapping (run_uuid);

-- (c) getUpstreamRuns() recursive case: LEFT JOIN dataset_versions ON dv.uuid = rim.dataset_version_uuid
--     AND joining back via dv.run_uuid for the next recursion level
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_dataset_versions_run_uuid
    ON dataset_versions (run_uuid)
    WHERE run_uuid IS NOT NULL;

-- (d) V1 job lineage BFS: WHERE is_current_job_version = TRUE (partial index already exists
--     on job_uuid; this adds the dataset_uuid for covering the io_type filter)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_jvio_current_job_dataset
    ON job_versions_io_mapping (job_uuid, dataset_uuid, io_type)
    WHERE is_current_job_version = TRUE;

-- (e) Recursive CTE traversal join — two separate indexes replace the OR condition
--     (io.input_version_uuid = l.output_version_uuid): already exists as idx_run_lineage_denorm_input_version
--     (io.output_version_uuid = l.input_version_uuid): already exists as idx_run_lineage_denorm_output_version
--     Add covering composite for the most selective recursive join pattern:
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_lineage_denorm_out_to_in
    ON run_lineage_denormalized (output_version_uuid, input_version_uuid, run_uuid)
    WHERE output_version_uuid IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_lineage_denorm_in_to_out
    ON run_lineage_denormalized (input_version_uuid, output_version_uuid, run_uuid)
    WHERE input_version_uuid IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_parent_lineage_denorm_out_to_in
    ON run_parent_lineage_denormalized (output_version_uuid, input_version_uuid, run_uuid)
    WHERE output_version_uuid IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_run_parent_lineage_denorm_in_to_out
    ON run_parent_lineage_denormalized (input_version_uuid, output_version_uuid, run_uuid)
    WHERE input_version_uuid IS NOT NULL;
