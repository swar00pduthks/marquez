-- SPDX-License-Identifier: Apache-2.0
-- Migration: Remove VARCHAR(255) length restriction from denormalized lineage tables
-- This migration changes all VARCHAR(255) columns in run_lineage_denormalized and run_parent_lineage_denormalized to unbounded VARCHAR (TEXT)

ALTER TABLE run_lineage_denormalized
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN job_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN input_dataset_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_version TYPE VARCHAR,
  ALTER COLUMN output_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN output_dataset_name TYPE VARCHAR,
  ALTER COLUMN output_dataset_version TYPE VARCHAR,
  ALTER COLUMN location TYPE VARCHAR,
  ALTER COLUMN parent_job_name TYPE VARCHAR,
  ALTER COLUMN source_name TYPE VARCHAR;

ALTER TABLE run_parent_lineage_denormalized
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN job_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN input_dataset_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_version TYPE VARCHAR,
  ALTER COLUMN output_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN output_dataset_name TYPE VARCHAR,
  ALTER COLUMN output_dataset_version TYPE VARCHAR,
  ALTER COLUMN location TYPE VARCHAR,
  ALTER COLUMN parent_job_name TYPE VARCHAR,
  ALTER COLUMN source_name TYPE VARCHAR;
