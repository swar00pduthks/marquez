-- Add indexes to denormalized lineage tables for improved query performance
-- These indexes dramatically improve performance when querying by run_uuid and traversing lineage via dataset versions

-- Indexes on run_uuid for fast lookup by run ID
-- This is the primary query pattern: WHERE run_uuid IN (...)
CREATE INDEX IF NOT EXISTS idx_run_lineage_denorm_run_uuid
    ON run_lineage_denormalized (run_uuid);

CREATE INDEX IF NOT EXISTS idx_run_parent_lineage_denorm_run_uuid
    ON run_parent_lineage_denormalized (run_uuid);

-- Indexes on input_version_uuid for lineage traversal
-- Used in JOINs: ON io.input_version_uuid = l.output_version_uuid
CREATE INDEX IF NOT EXISTS idx_run_lineage_denorm_input_version
    ON run_lineage_denormalized (input_version_uuid);

CREATE INDEX IF NOT EXISTS idx_run_parent_lineage_denorm_input_version
    ON run_parent_lineage_denormalized (input_version_uuid);

-- Indexes on output_version_uuid for lineage traversal
-- Used in JOINs: ON io.output_version_uuid = l.input_version_uuid
CREATE INDEX IF NOT EXISTS idx_run_lineage_denorm_output_version
    ON run_lineage_denormalized (output_version_uuid);

CREATE INDEX IF NOT EXISTS idx_run_parent_lineage_denorm_output_version
    ON run_parent_lineage_denormalized (output_version_uuid);

-- Composite index on (run_uuid, run_date) for partition pruning when date is known
-- This enables efficient queries when both run_uuid and date range are specified
CREATE INDEX IF NOT EXISTS idx_run_lineage_denorm_run_uuid_date
    ON run_lineage_denormalized (run_uuid, run_date);

CREATE INDEX IF NOT EXISTS idx_run_parent_lineage_denorm_run_uuid_date
    ON run_parent_lineage_denormalized (run_uuid, run_date);

-- Note: These indexes are created on the parent partitioned table, which automatically
-- creates corresponding indexes on all child partitions (existing and future)
