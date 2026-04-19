-- Copyright 2018-2024 contributors to the Marquez project
-- SPDX-License-Identifier: Apache-2.0

-- Creates the backfill_checkpoints table used by async backfill jobs (DENORM_V1, GRAPH_V1).
--
-- NOTE: V99 was already applied to the deployed database with a different description
-- ("robust agtype to json"). This V100 migration creates the table that V99 was supposed to
-- create locally. Using IF NOT EXISTS ensures this is safe to re-run.
--
-- Keyset cursor design: (last_cursor_time, last_run_id) rather than OFFSET so that pagination
-- on multi-billion-row tables does not degrade over time.

CREATE TABLE IF NOT EXISTS backfill_checkpoints (
    version          TEXT        NOT NULL,
    last_cursor_time TIMESTAMPTZ NOT NULL DEFAULT '1970-01-01 00:00:00+00',
    last_run_id      TEXT        NOT NULL DEFAULT '',
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT backfill_checkpoints_pkey PRIMARY KEY (version)
);

COMMENT ON TABLE backfill_checkpoints IS
    'Resumable keyset cursor for each async backfill job version (e.g. GRAPH_V1, DENORM_V1).';

COMMENT ON COLUMN backfill_checkpoints.version IS
    'Backfill job identifier matching BackfillConfig.enabledVersions entries (e.g. GRAPH_V1).';

COMMENT ON COLUMN backfill_checkpoints.last_cursor_time IS
    'event_time of the last lineage_events row successfully committed in this backfill run.';

COMMENT ON COLUMN backfill_checkpoints.last_run_id IS
    'run_id of the last row within the same event_time bucket, used for tie-breaking.';
