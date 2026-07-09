-- SPDX-License-Identifier: Apache-2.0
-- V106: Remove VARCHAR(255) length restrictions from dataset/job denormalized tables
-- and fix remaining columns on run lineage tables missed by V88

-- run_lineage_denormalized: location, parent_job_name, source_name were missed by V88
ALTER TABLE run_lineage_denormalized
  ALTER COLUMN location TYPE VARCHAR,
  ALTER COLUMN parent_job_name TYPE VARCHAR,
  ALTER COLUMN source_name TYPE VARCHAR;

-- run_parent_lineage_denormalized: same columns missed by V88
ALTER TABLE run_parent_lineage_denormalized
  ALTER COLUMN location TYPE VARCHAR,
  ALTER COLUMN parent_job_name TYPE VARCHAR,
  ALTER COLUMN source_name TYPE VARCHAR;

-- dataset_denormalized
ALTER TABLE dataset_denormalized
  ALTER COLUMN name TYPE VARCHAR,
  ALTER COLUMN physical_name TYPE VARCHAR,
  ALTER COLUMN schema_location TYPE VARCHAR,
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN source_name TYPE VARCHAR;

-- dataset_version_denormalized
ALTER TABLE dataset_version_denormalized
  ALTER COLUMN schema_location TYPE VARCHAR;

-- job_denormalized
ALTER TABLE job_denormalized
  ALTER COLUMN name TYPE VARCHAR,
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN simple_name TYPE VARCHAR,
  ALTER COLUMN parent_job_name TYPE VARCHAR;
