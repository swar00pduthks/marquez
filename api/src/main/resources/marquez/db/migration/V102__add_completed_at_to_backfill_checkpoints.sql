-- SPDX-License-Identifier: Apache-2.0
-- V102: Add completed_at to backfill_checkpoints so jobs mark themselves permanently done.
--
-- Once a backfill job drains all historical rows (batch returns empty), it sets completed_at.
-- On future restarts the job skips immediately if completed_at IS NOT NULL.
-- This prevents the backfill from re-scanning live events that are already handled by real-time ingestion.

ALTER TABLE backfill_checkpoints
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

COMMENT ON COLUMN backfill_checkpoints.completed_at IS
    'Set to now() when the backfill job processes its last batch and finds no more rows. '
    'NULL means in-progress or not yet started. Non-NULL means permanently finished — skip on restart.';
