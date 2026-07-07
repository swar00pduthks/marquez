-- SPDX-License-Identifier: Apache-2.0
-- V108: Partition run_facets by event time + namespace (multi-tenant, 2-year scale)
--
-- run_facets is the largest table at 1M events/day: ~10 facets/event -> ~10M rows/day
-- -> ~7.3B rows (~1.1 TB) over 2 years. Partition it with the same composite scheme as
-- lineage_edges and the denormalized tables:
--   RANGE (lineage_event_time, monthly) -> HASH (namespace, 8) + hash-subpartitioned DEFAULT
-- giving O(1) retention (DROP an old month) and tenant isolation.
--
-- ONLINE, SIZE-BRANCHED CUTOVER
-- ----------------------------
-- Converting a plain table to partitioned requires a shadow table + copy + swap. On a
-- large populated table that copy must NOT run inline at startup (it would block boot for
-- hours). So this migration branches on the CURRENT size of run_facets:
--
--   * Small / empty (<= 1 GiB — fresh installs, CI, dev): copy + swap INLINE now. Instant
--     on an empty table; keeps run_facets partitioned the moment migrations finish, so the
--     rest of the system (V110 lifecycle, tests) sees the final shape immediately.
--
--   * Large (> 1 GiB — real production): set up an ONLINE cutover instead. Create the empty
--     partitioned shadow and an AFTER INSERT trigger that mirrors every new row into it, and
--     record a cutover timestamp. The heavy historical copy + final swap are deferred to the
--     RUN_FACETS_PARTITION_V1 background backfill job (BackfillOrchestrator), which runs off
--     the startup path. run_facets stays the live table (source of truth) until the job does a
--     count-verified atomic swap — so nothing is lost even if the copy is interrupted.
--
-- Boundary (exactly-once, no primary key needed): the trigger mirrors only rows with
-- created_at >= cutover; the backfill copies only rows with created_at < cutover. Disjoint by
-- value, so no row is copied twice and none is missed, independent of commit ordering. The
-- backfill additionally count-verifies (old vs shadow) under a brief lock before swapping, and
-- the shadow is disposable until then, so a mismatch just rebuilds rather than losing data.
--
-- created_at is the app's write-time Instant (RunFacetsDao), i.e. when the row was written —
-- the correct quantity for this boundary (distinct from lineage_event_time, the event clock).

-- 1. Denormalize namespace onto run_facets (nullable, instant — no table rewrite).
ALTER TABLE run_facets ADD COLUMN IF NOT EXISTS namespace TEXT;

-- 2. Drop the DEAD matviews that pin run_facets by OID (their refresher
--    RunLineageMaterializeViewRefresherJob is disabled; the active MaterializeViewRefresherJob
--    only refreshes lineage_events_by_type_hourly_view). Not recreated. Removing them now keeps
--    the later rename swap (inline or deferred) unobstructed.
DROP MATERIALIZED VIEW IF EXISTS run_lineage_view;
DROP MATERIALIZED VIEW IF EXISTS run_parent_lineage_view;

-- 3. Mirror trigger function: copy one row into the partitioned shadow, resolving namespace
--    (already set by the app; subselect is a fallback for any other writer).
CREATE OR REPLACE FUNCTION run_facets_mirror_to_partition() RETURNS trigger AS $$
BEGIN
    INSERT INTO run_facets_p (
        created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet, namespace)
    VALUES (
        NEW.created_at, NEW.run_uuid, NEW.lineage_event_time, NEW.lineage_event_type,
        NEW.name, NEW.facet,
        COALESCE(NEW.namespace, (SELECT namespace_name FROM runs WHERE uuid = NEW.run_uuid)));
    RETURN NULL; -- AFTER trigger
END;
$$ LANGUAGE plpgsql;

-- 4. The empty partitioned shadow (always built; identical shape to run_facets).
CREATE TABLE IF NOT EXISTS run_facets_p (LIKE run_facets INCLUDING DEFAULTS INCLUDING INDEXES)
    PARTITION BY RANGE (lineage_event_time);

DO $$
DECLARE
    d     date := '2026-01-01';
    mname text;
    h     int;
BEGIN
    WHILE d < '2027-01-01' LOOP
        mname := 'run_facets_p_y' || to_char(d, 'YYYY') || 'm' || to_char(d, 'MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF run_facets_p '
            || 'FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (namespace)',
            mname, d, (d + INTERVAL '1 month')::date);
        FOR h IN 0..7 LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                || 'FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
                mname || '_h' || h, mname, h);
        END LOOP;
        d := (d + INTERVAL '1 month')::date;
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS run_facets_p_default
    PARTITION OF run_facets_p DEFAULT PARTITION BY HASH (namespace);
DO $$
DECLARE h int;
BEGIN
    FOR h IN 0..7 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS run_facets_p_default_h%s '
            || 'PARTITION OF run_facets_p_default FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
            h, h);
    END LOOP;
END $$;

-- 5. Cutover state marker (single row). state = 'SWAPPED' (done, inline) | 'DUAL_WRITE'
--    (online cutover in progress — the backfill job will copy + swap).
CREATE TABLE IF NOT EXISTS run_facets_partition_state (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    state      text        NOT NULL,
    cutover_at timestamptz NOT NULL DEFAULT now()
);

-- 6. Size branch: small/empty -> inline swap now; large -> online (trigger + defer).
DO $$
DECLARE
    v_bytes   bigint;
    v_cutover timestamptz := clock_timestamp();
BEGIN
    v_bytes := pg_total_relation_size('run_facets');

    IF v_bytes <= 1073741824 THEN
        -- SMALL / EMPTY: copy + swap synchronously (instant on an empty table).
        UPDATE run_facets rf
           SET namespace = r.namespace_name
          FROM runs r
         WHERE r.uuid = rf.run_uuid AND rf.namespace IS NULL;

        INSERT INTO run_facets_p (
            created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet, namespace)
        SELECT created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet, namespace
          FROM run_facets;

        DROP VIEW IF EXISTS run_facets_view;
        ALTER TABLE run_facets   RENAME TO run_facets_old;
        ALTER TABLE run_facets_p RENAME TO run_facets;
        ALTER TABLE run_facets
            ADD CONSTRAINT run_facets_run_uuid_fkey
            FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE;
        EXECUTE 'CREATE VIEW run_facets_view AS '
             || 'SELECT created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet '
             || 'FROM run_facets';
        DROP TABLE run_facets_old;

        INSERT INTO run_facets_partition_state (singleton, state, cutover_at)
             VALUES (true, 'SWAPPED', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'SWAPPED', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'run_facets partitioned inline (% bytes <= 1 GiB).', v_bytes;
    ELSE
        -- LARGE: online cutover. Mirror new rows (created_at >= cutover) into the shadow; the
        -- backfill job copies history (created_at < cutover) and performs the verified swap.
        EXECUTE format(
            'CREATE TRIGGER run_facets_mirror_trg AFTER INSERT ON run_facets '
            || 'FOR EACH ROW WHEN (NEW.created_at >= %L::timestamptz) '
            || 'EXECUTE FUNCTION run_facets_mirror_to_partition()', v_cutover);

        INSERT INTO run_facets_partition_state (singleton, state, cutover_at)
             VALUES (true, 'DUAL_WRITE', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'DUAL_WRITE', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'run_facets online cutover armed (% bytes > 1 GiB); RUN_FACETS_PARTITION_V1 will copy + swap.', v_bytes;
    END IF;
END $$;
