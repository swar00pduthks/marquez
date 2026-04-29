-- SPDX-License-Identifier: Apache-2.0
-- V89: Add unique constraints to denormalized lineage tables to support idempotent upserts
-- This allows DenormalizedLineageService to use ON CONFLICT instead of DELETE+INSERT
--
-- For run_lineage_denormalized: a unique edge is defined by run, input/output versions, and date
-- Note: COALESCE is not possible in a UNIQUE constraint directly, so we use a partial index or just assume NULLs are distinct
-- However, for partitioning, we must include run_date.
-- Since multiple rows per run are expected (multi-input), we include the version UUIDs.
--
-- Pre-step: TRUNCATE both tables before adding the UNIQUE constraint.
-- Rationale: existing rows accumulated under the old delete-then-insert write path
-- contain duplicate edges (race window between DELETE and INSERT) that violate the
-- new constraint and cause the ALTER TABLE to fail. Truncating is safe because:
--   * These tables are pure denormalizations of runs/runs_input_mapping/dataset_versions
--     — all data is recoverable from the normalized source of truth.
--   * The OpenLineage write path (DenormalizedLineageService) will repopulate rows
--     on the next event for each run.
--   * Historical runs that are never POSTed again will be missing from V2/V3 lineage
--     denorm — operators who need full backfill should run a one-shot rebuild job
--     against the normalized tables after this migration completes.

TRUNCATE TABLE run_lineage_denormalized;
TRUNCATE TABLE run_parent_lineage_denormalized;

ALTER TABLE run_lineage_denormalized
  ADD CONSTRAINT unique_run_lineage_edge
  UNIQUE (run_uuid, input_version_uuid, output_version_uuid, run_date);

ALTER TABLE run_parent_lineage_denormalized
  ADD CONSTRAINT unique_run_parent_lineage_edge
  UNIQUE (run_uuid, input_version_uuid, output_version_uuid, run_date);
