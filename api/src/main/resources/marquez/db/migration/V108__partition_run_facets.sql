-- SPDX-License-Identifier: Apache-2.0
-- V108: Partition run_facets by event time + namespace (multi-tenant, 2-year scale)
--
-- run_facets is the largest table at 1M events/day: ~10 facets/event → ~10M rows/day
-- → ~7.3B rows (~1.1 TB) over 2 years. Plain and unbounded today. Partition it with
-- the same composite scheme as lineage_edges and the denormalized tables:
--   RANGE (lineage_event_time, monthly) -> HASH (namespace, 8) + hash-subpartitioned DEFAULT
-- giving O(1) retention (DROP an old month) and tenant isolation (a high-volume
-- namespace hashes into its own physical sub-partition).
--
-- run_facets has no PRIMARY KEY and is INSERT-only (no ON CONFLICT), so there is no
-- unique constraint to fold the partition keys into. namespace is denormalized from
-- the run's namespace; it is nullable (run_uuid is nullable) — HASH routes NULL to a
-- fixed bucket.
--
-- Tables are populated, so partition via shadow table + copy + atomic rename swap.
-- On a fresh/empty database (CI, new installs) the copy is a no-op. Validated end to
-- end on PostgreSQL (rolled-back transaction against the real schema).
--
-- Dependent objects: run_facets_view (trivial SELECT * — recreated below). The
-- matviews run_lineage_view and run_parent_lineage_view depend on run_facets but are
-- DEAD — their refresher (RunLineageMaterializeViewRefresherJob) is disabled and the
-- active MaterializeViewRefresherJob only refreshes lineage_events_by_type_hourly_view,
-- so they are dropped here rather than recreated.

-- 1. Denormalize namespace onto run_facets, then build the partitioned shadow.
ALTER TABLE run_facets ADD COLUMN IF NOT EXISTS namespace TEXT;
UPDATE run_facets rf
   SET namespace = r.namespace_name
  FROM runs r
 WHERE r.uuid = rf.run_uuid
   AND rf.namespace IS NULL;

CREATE TABLE run_facets_p (LIKE run_facets INCLUDING DEFAULTS INCLUDING INDEXES)
    PARTITION BY RANGE (lineage_event_time);

-- Monthly partitions for 2026 (consistent with V101/V107), each HASH(namespace) x8.
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

-- DEFAULT safety-net month, also HASH(namespace)-subpartitioned.
CREATE TABLE run_facets_p_default
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

-- 2. Copy existing rows into the partitioned table (no-op on a fresh DB).
INSERT INTO run_facets_p SELECT * FROM run_facets;

-- 3. Drop dependents that pin the old table by OID: the trivial view (recreated
--    below) and the two dead matviews (not recreated — see header).
DROP VIEW IF EXISTS run_facets_view;
DROP MATERIALIZED VIEW IF EXISTS run_lineage_view;
DROP MATERIALIZED VIEW IF EXISTS run_parent_lineage_view;

-- 4. Atomic swap, then restore the FK (LIKE does not copy FKs) and the view.
ALTER TABLE run_facets RENAME TO run_facets_old;
ALTER TABLE run_facets_p RENAME TO run_facets;

ALTER TABLE run_facets
    ADD CONSTRAINT run_facets_run_uuid_fkey
    FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE;

CREATE VIEW run_facets_view AS
    SELECT created_at, run_uuid, lineage_event_time, lineage_event_type, name, facet
    FROM run_facets;

DROP TABLE run_facets_old;
