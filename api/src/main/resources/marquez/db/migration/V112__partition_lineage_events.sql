-- SPDX-License-Identifier: Apache-2.0
-- V112: Partition lineage_events by event time + job_namespace (online, startup-safe)
--
-- lineage_events is the raw event store (~730M rows / large JSONB over 2 years). Partition it with
-- the composite scheme, HASH-keyed on the EXISTING job_namespace column (no new column needed):
--   RANGE (event_time, monthly) -> HASH (job_namespace, 8) + hash-subpartitioned DEFAULT
--
-- Same size-branched online cutover as run_facets (V108) / dataset_facets (V111): inline swap when
-- <= 1 GiB, otherwise a dual-write trigger + LINEAGE_EVENTS_PARTITION_V1 background copy + verified
-- swap that never blocks startup. lineage_events has NO foreign keys. Its dependent is the actively
-- refreshed matview lineage_events_by_type_hourly_view, dropped before the rename and recreated
-- after (WITH NO DATA in the online path; the backfill job REFRESHes it once the swap commits).
--
-- Boundary: created_at (DEFAULT now() at UTC) is the write-time clock, so new rows are always
-- non-null and >= cutover. Historical rows predating the created_at column may be NULL, so the
-- backfill treats NULL as historical (created_at < cutover OR created_at IS NULL); the two sides
-- stay disjoint and exactly-once.

-- ============================================================================
-- 1. Generalize the composite create helper to take the HASH column (V110 hardcoded 'namespace';
--    lineage_events hashes on job_namespace). Runtime callers pass it explicitly.
-- ============================================================================
DROP FUNCTION IF EXISTS create_monthly_hash_partition(text, text, date, integer);

CREATE OR REPLACE FUNCTION create_monthly_hash_partition(
    parent_table text, partition_prefix text, start_date date, hash_modulus integer,
    hash_column text)
RETURNS void AS $$
DECLARE
    mname    text;
    end_date date;
    h        int;
    lock_key bigint;
BEGIN
    start_date := date_trunc('month', start_date)::date;
    end_date   := (start_date + INTERVAL '1 month')::date;
    mname      := partition_prefix || '_y' || to_char(start_date, 'YYYY') || 'm' || to_char(start_date, 'MM');

    lock_key := ('x' || substr(md5(mname), 1, 15))::bit(60)::bigint;
    PERFORM pg_advisory_xact_lock(lock_key);

    BEGIN
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
            || 'FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (%I)',
            mname, parent_table, start_date, end_date, hash_column);

        FOR h IN 0..(hash_modulus - 1) LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                || 'FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                mname || '_h' || h, mname, hash_modulus, h);
        END LOOP;
    EXCEPTION
        WHEN OTHERS THEN
            RAISE NOTICE 'Skipping partition % (already exists or overlaps): %', mname, SQLERRM;
    END;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 2. lineage_events online cutover
-- ============================================================================

-- Mirror trigger function: copy one row into the shadow (job_namespace already present).
CREATE OR REPLACE FUNCTION lineage_events_mirror_to_partition() RETURNS trigger AS $$
BEGIN
    INSERT INTO lineage_events_p (
        event_time, event, event_type, job_name, job_namespace, producer, run_uuid,
        created_at, _event_type, run_date)
    VALUES (
        NEW.event_time, NEW.event, NEW.event_type, NEW.job_name, NEW.job_namespace, NEW.producer,
        NEW.run_uuid, NEW.created_at, NEW._event_type, NEW.run_date);
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Empty partitioned shadow: RANGE(event_time) -> HASH(job_namespace).
CREATE TABLE IF NOT EXISTS lineage_events_p (LIKE lineage_events INCLUDING DEFAULTS INCLUDING INDEXES)
    PARTITION BY RANGE (event_time);

DO $$
DECLARE
    d     date := '2026-01-01';
    mname text;
    h     int;
BEGIN
    WHILE d < '2027-01-01' LOOP
        mname := 'lineage_events_p_y' || to_char(d, 'YYYY') || 'm' || to_char(d, 'MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF lineage_events_p '
            || 'FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (job_namespace)',
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

CREATE TABLE IF NOT EXISTS lineage_events_p_default
    PARTITION OF lineage_events_p DEFAULT PARTITION BY HASH (job_namespace);
DO $$
DECLARE h int;
BEGIN
    FOR h IN 0..7 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS lineage_events_p_default_h%s '
            || 'PARTITION OF lineage_events_p_default FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
            h, h);
    END LOOP;
END $$;

CREATE TABLE IF NOT EXISTS lineage_events_partition_state (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    state      text        NOT NULL,
    cutover_at timestamptz NOT NULL DEFAULT now()
);

DO $$
DECLARE
    v_bytes   bigint;
    v_cutover timestamptz := clock_timestamp();
BEGIN
    v_bytes := pg_total_relation_size('lineage_events');

    IF v_bytes <= 1073741824 THEN
        -- SMALL / EMPTY: copy + swap synchronously; recreate the matview WITH DATA (cheap when small).
        INSERT INTO lineage_events_p (
            event_time, event, event_type, job_name, job_namespace, producer, run_uuid,
            created_at, _event_type, run_date)
        SELECT event_time, event, event_type, job_name, job_namespace, producer, run_uuid,
               created_at, _event_type, run_date
          FROM lineage_events;

        DROP MATERIALIZED VIEW IF EXISTS lineage_events_by_type_hourly_view;
        ALTER TABLE lineage_events   RENAME TO lineage_events_old;
        ALTER TABLE lineage_events_p RENAME TO lineage_events;
        DROP TABLE lineage_events_old;
        EXECUTE 'CREATE MATERIALIZED VIEW lineage_events_by_type_hourly_view AS '
             || 'SELECT date_trunc(''hour'', event_time) AS start_interval, '
             || 'count(*) FILTER (WHERE event_type = ''FAIL'') AS fail, '
             || 'count(*) FILTER (WHERE event_type = ''START'') AS start, '
             || 'count(*) FILTER (WHERE event_type = ''COMPLETE'') AS complete, '
             || 'count(*) FILTER (WHERE event_type = ''ABORT'') AS abort '
             || 'FROM lineage_events GROUP BY date_trunc(''hour'', event_time)';

        INSERT INTO lineage_events_partition_state (singleton, state, cutover_at)
             VALUES (true, 'SWAPPED', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'SWAPPED', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'lineage_events partitioned inline (% bytes <= 1 GiB).', v_bytes;
    ELSE
        EXECUTE format(
            'CREATE TRIGGER lineage_events_mirror_trg AFTER INSERT ON lineage_events '
            || 'FOR EACH ROW WHEN (NEW.created_at >= %L::timestamptz) '
            || 'EXECUTE FUNCTION lineage_events_mirror_to_partition()', v_cutover);

        INSERT INTO lineage_events_partition_state (singleton, state, cutover_at)
             VALUES (true, 'DUAL_WRITE', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'DUAL_WRITE', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'lineage_events online cutover armed (% bytes > 1 GiB); LINEAGE_EVENTS_PARTITION_V1 will copy + swap.', v_bytes;
    END IF;
END $$;
