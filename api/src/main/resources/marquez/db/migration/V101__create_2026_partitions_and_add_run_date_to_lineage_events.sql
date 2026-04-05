-- SPDX-License-Identifier: Apache-2.0
-- V101: Create missing 2026 monthly partitions and add run_date to lineage_events
--
-- NOTE: The content of this migration was originally intended for V97, but V97-V100
-- were already applied on production databases with different content. V101 applies
-- the same idempotent changes safely.
--
-- WHY THIS IS NEEDED:
--   V82 created partitions only through 2025-12 (TO 2026-01-01).
--   PartitionManagementJob creates partitions from LocalDate.now() forward,
--   so 2026-01 and 2026-02 were never created if the server started in or after March 2026.
--   Any run event with run_date in Jan/Feb 2026 would fail with:
--     "no partition of relation run_lineage_denormalized found for row"
--
-- create_monthly_partition() (V85) is idempotent - checks existence before creating.

-- ============================================================
-- PART 1: Create all 2026 monthly partitions
-- ============================================================

-- run_lineage_denormalized
SELECT create_monthly_partition('run_lineage_denormalized', '2026-01-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-02-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-03-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-04-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-05-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-06-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-07-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-08-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-09-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-10-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-11-01'::date);
SELECT create_monthly_partition('run_lineage_denormalized', '2026-12-01'::date);

-- run_parent_lineage_denormalized
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-01-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-02-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-03-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-04-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-05-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-06-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-07-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-08-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-09-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-10-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-11-01'::date);
SELECT create_monthly_partition('run_parent_lineage_denormalized', '2026-12-01'::date);

-- ============================================================
-- PART 2: Add run_date to lineage_events
-- ============================================================
-- Enables efficient date-range filtering and future partitioning of lineage_events.
-- GENERATED ALWAYS AS STORED means Postgres computes it on insert/update automatically
-- with zero application-layer changes required.

ALTER TABLE lineage_events
  ADD COLUMN IF NOT EXISTS run_date DATE
    GENERATED ALWAYS AS (event_time::date) STORED;

CREATE INDEX IF NOT EXISTS idx_lineage_events_run_date
  ON lineage_events (run_date);

-- Composite index for the most common query pattern:
-- "give me all events for namespace X on date Y"
CREATE INDEX IF NOT EXISTS idx_lineage_events_namespace_run_date
  ON lineage_events (job_namespace, run_date DESC);
