-- SPDX-License-Identifier: Apache-2.0
-- V110: Lifecycle helpers for the composite RANGE(date)->HASH(namespace) tables
--
-- V107 (lineage_edges) and V108 (run_facets) introduced two-level partitioning:
--   PARTITION BY RANGE (date) -> each month PARTITION BY HASH (namespace, 8).
-- The pre-existing create_monthly_partition()/drop_old_partitions() helpers only
-- understand the single-level denormalized tables (they create flat RANGE
-- partitions and denorm-specific indexes), so they cannot manage these tables.
-- Two new helpers create the monthly RANGE->HASH subtree and retire whole months.
--
-- Naming note: run_facets was partitioned via shadow-table swap, so its monthly
-- partitions keep the shadow prefix (run_facets_p_y2026m01) even though the parent
-- is now `run_facets`. The create helper therefore takes the partition name prefix
-- separately from the parent table; the drop helper discovers children via
-- pg_inherits (prefix-agnostic) so it works regardless of naming.

-- create_monthly_hash_partition: create one monthly RANGE partition that is itself
-- HASH(namespace)-subpartitioned into `hash_modulus` buckets. Indexes are NOT
-- created here — Postgres propagates the parent's partitioned indexes to every new
-- partition automatically. Advisory-locked + exception-guarded so concurrent
-- schedulers and re-runs are safe.
CREATE OR REPLACE FUNCTION create_monthly_hash_partition(
    parent_table text, partition_prefix text, start_date date, hash_modulus integer)
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

    -- Serialize concurrent creation of the same month.
    lock_key := ('x' || substr(md5(mname), 1, 15))::bit(60)::bigint;
    PERFORM pg_advisory_xact_lock(lock_key);

    BEGIN
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
            || 'FOR VALUES FROM (%L) TO (%L) PARTITION BY HASH (namespace)',
            mname, parent_table, start_date, end_date);

        FOR h IN 0..(hash_modulus - 1) LOOP
            EXECUTE format(
                'CREATE TABLE IF NOT EXISTS %I PARTITION OF %I '
                || 'FOR VALUES WITH (MODULUS %s, REMAINDER %s)',
                mname || '_h' || h, mname, hash_modulus, h);
        END LOOP;
    EXCEPTION
        WHEN OTHERS THEN
            -- Overlap / already-exists ⇒ the work is already done.
            RAISE NOTICE 'Skipping partition % (already exists or overlaps): %', mname, SQLERRM;
    END;
END;
$$ LANGUAGE plpgsql;

-- drop_old_hash_partitions: detach+drop whole monthly partitions of `parent_table`
-- whose month is older than `retention_months`. Children are discovered via
-- pg_inherits (so the run_facets `_p` prefix and the DEFAULT partition are handled
-- correctly: DEFAULT has no y####m## suffix and is skipped). DROP ... CASCADE
-- removes the month's hash sub-partitions with it.
CREATE OR REPLACE FUNCTION drop_old_hash_partitions(
    parent_table regclass, retention_months integer)
RETURNS void AS $$
DECLARE
    cutoff_date date;
    child       record;
    part_date   date;
BEGIN
    -- First day of the oldest month we keep.
    cutoff_date := (date_trunc('month', CURRENT_DATE) - (retention_months || ' months')::interval)::date;

    FOR child IN
        SELECT inhrelid::regclass::text AS child_name
        FROM pg_inherits
        WHERE inhparent = parent_table
    LOOP
        -- Monthly partitions end in y####m##; DEFAULT and anything else is skipped.
        -- Capture year and month separately (substring(... from pattern) only returns
        -- the first capture group, so a single y(####)m(##) group would drop the month)
        -- and concatenate to YYYYMM for an exact, month-precise date.
        IF child.child_name ~ 'y[0-9]{4}m[0-9]{2}$' THEN
            part_date := to_date(
                substring(child.child_name from 'y([0-9]{4})m[0-9]{2}$')
                || substring(child.child_name from 'y[0-9]{4}m([0-9]{2})$'),
                'YYYYMM');
            IF part_date < cutoff_date THEN
                EXECUTE format('DROP TABLE IF EXISTS %s CASCADE', child.child_name);
                RAISE NOTICE 'Dropped partition % (month %, cutoff %)',
                    child.child_name, part_date, cutoff_date;
            END IF;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
