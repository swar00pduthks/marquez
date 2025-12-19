-- Remove columns that were added for DAO support but are not needed for lineage queries
-- This migration removes extra columns from denormalized tables since we're only using them for lineage

-- Remove DAO-specific columns from run_lineage_denormalized
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS run_args_uuid;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS run_args;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS job_version;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS duration_ms;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS nominal_start_time;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS nominal_end_time;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS tags;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS description;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS location;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS parent_job_name;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS source_name;
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS source_type;

-- Remove DAO-specific columns from run_parent_lineage_denormalized
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS run_args_uuid;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS run_args;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS job_version;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS duration_ms;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS nominal_start_time;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS nominal_end_time;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS tags;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS description;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS location;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS parent_job_name;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS source_name;
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS source_type;
