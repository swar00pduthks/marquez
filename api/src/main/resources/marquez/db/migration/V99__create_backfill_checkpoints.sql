-- Copyright 2018-2024 contributors to the Marquez project
-- SPDX-License-Identifier: Apache-2.0

-- Tracks per-version backfill progress so that async backfill jobs can be resumed after a
-- restart without re-processing events that were already written to the AGE graph.
--
-- Keyset cursor design: we store (last_cursor_time, last_run_id) rather than an offset so that
-- pagination on the multi-billion-row lineage_events table does not degrade over time (OFFSET
-- scans are O(n) on large tables; keyset pagination is O(log n) with the right index).

CREATE TABLE IF NOT EXISTS backfill_checkpoints (
    version          TEXT        NOT NULL,
    last_cursor_time TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01 00:00:00+00',
    last_run_id      TEXT        NOT NULL DEFAULT '',
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT backfill_checkpoints_pkey PRIMARY KEY (version)
);

COMMENT ON TABLE backfill_checkpoints IS
    'Resumable keyset cursor for each async backfill job version (e.g. GRAPH_V1).';

COMMENT ON COLUMN backfill_checkpoints.version IS
    'Backfill job identifier matching BackfillConfig.enabledVersions entries (e.g. GRAPH_V1).';

COMMENT ON COLUMN backfill_checkpoints.last_cursor_time IS
    'event_time of the last lineage_events row successfully committed in this backfill run.';

COMMENT ON COLUMN backfill_checkpoints.last_run_id IS
    'run_id of the last row within the same event_time bucket, used for tie-breaking.';
