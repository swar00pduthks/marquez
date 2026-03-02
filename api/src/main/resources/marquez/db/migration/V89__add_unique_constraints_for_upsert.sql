-- SPDX-License-Identifier: Apache-2.0
-- V89: Add unique constraints to denormalized lineage tables to support idempotent upserts
-- This allows DenormalizedLineageService to use ON CONFLICT instead of DELETE+INSERT

-- For run_lineage_denormalized: a unique edge is defined by run, input/output versions, and date
-- Note: COALESCE is not possible in a UNIQUE constraint directly, so we use a partial index or just assume NULLs are distinct
-- However, for partitioning, we must include run_date.
-- Since multiple rows per run are expected (multi-input), we include the version UUIDs.

ALTER TABLE run_lineage_denormalized
  ADD CONSTRAINT unique_run_lineage_edge
  UNIQUE (run_uuid, input_version_uuid, output_version_uuid, run_date);

ALTER TABLE run_parent_lineage_denormalized
  ADD CONSTRAINT unique_run_parent_lineage_edge
  UNIQUE (run_uuid, input_version_uuid, output_version_uuid, run_date);
