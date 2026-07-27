-- SPDX-License-Identifier: Apache-2.0
-- V111: Partition dataset_facets by event time + namespace (online, startup-safe)
--
-- Same composite scheme + online cutover as run_facets (V108):
--   RANGE (lineage_event_time, monthly) -> HASH (namespace, 8) + hash-subpartitioned DEFAULT
-- Size-branched so it never blocks startup:
--   * Small / empty (<= 1 GiB): copy + atomic rename swap INLINE (instant on an empty table).
--   * Large (> 1 GiB): arm an online cutover (empty shadow + AFTER INSERT mirror trigger for rows
--     with created_at >= cutover, state='DUAL_WRITE'); the DATASET_FACETS_PARTITION_V1 backfill job
--     copies history (created_at < cutover) and performs the count-verified swap off the startup path.
--
-- dataset_facets has no PRIMARY KEY (INSERT-only) and three FKs (dataset_uuid -> datasets,
-- dataset_version_uuid -> dataset_versions, run_uuid -> runs), all restored at swap. Its only
-- dependent is the trivial dataset_facets_view. namespace is denormalized from the run's
-- namespace_name (the facet's producing run); NULL where run_uuid is NULL (HASH routes NULL to a
-- fixed bucket). created_at is the app write-time Instant, the correct cutover boundary.

-- 1. namespace column (nullable, instant).
ALTER TABLE dataset_facets ADD COLUMN IF NOT EXISTS namespace TEXT;

-- 2. Mirror trigger function (copies one row into the shadow, resolving namespace).
CREATE OR REPLACE FUNCTION dataset_facets_mirror_to_partition() RETURNS trigger AS $$
BEGIN
    INSERT INTO dataset_facets_p (
        created_at, dataset_uuid, dataset_version_uuid, run_uuid, lineage_event_time,
        lineage_event_type, type, name, facet, namespace)
    VALUES (
        NEW.created_at, NEW.dataset_uuid, NEW.dataset_version_uuid, NEW.run_uuid,
        NEW.lineage_event_time, NEW.lineage_event_type, NEW.type, NEW.name, NEW.facet,
        COALESCE(NEW.namespace, (SELECT namespace_name FROM runs WHERE uuid = NEW.run_uuid)));
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- 3. Empty partitioned shadow (same shape).
CREATE TABLE IF NOT EXISTS dataset_facets_p (LIKE dataset_facets INCLUDING DEFAULTS INCLUDING INDEXES)
    PARTITION BY RANGE (lineage_event_time);

DO $$
DECLARE
    d     date := '2026-01-01';
    mname text;
    h     int;
BEGIN
    WHILE d < '2027-01-01' LOOP
        mname := 'dataset_facets_p_y' || to_char(d, 'YYYY') || 'm' || to_char(d, 'MM');
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF dataset_facets_p '
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

CREATE TABLE IF NOT EXISTS dataset_facets_p_default
    PARTITION OF dataset_facets_p DEFAULT PARTITION BY HASH (namespace);
DO $$
DECLARE h int;
BEGIN
    FOR h IN 0..7 LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS dataset_facets_p_default_h%s '
            || 'PARTITION OF dataset_facets_p_default FOR VALUES WITH (MODULUS 8, REMAINDER %s)',
            h, h);
    END LOOP;
END $$;

-- 4. Cutover state marker.
CREATE TABLE IF NOT EXISTS dataset_facets_partition_state (
    singleton  boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    state      text        NOT NULL,
    cutover_at timestamptz NOT NULL DEFAULT now()
);

-- 5. Size branch: small/empty -> inline swap now; large -> online (trigger + defer).
DO $$
DECLARE
    v_bytes   bigint;
    v_cutover timestamptz := clock_timestamp();
BEGIN
    v_bytes := pg_total_relation_size('dataset_facets');

    IF v_bytes <= 1073741824 THEN
        UPDATE dataset_facets df
           SET namespace = r.namespace_name
          FROM runs r
         WHERE r.uuid = df.run_uuid AND df.namespace IS NULL;

        INSERT INTO dataset_facets_p (
            created_at, dataset_uuid, dataset_version_uuid, run_uuid, lineage_event_time,
            lineage_event_type, type, name, facet, namespace)
        SELECT created_at, dataset_uuid, dataset_version_uuid, run_uuid, lineage_event_time,
               lineage_event_type, type, name, facet, namespace
          FROM dataset_facets;

        DROP VIEW IF EXISTS dataset_facets_view;
        ALTER TABLE dataset_facets   RENAME TO dataset_facets_old;
        ALTER TABLE dataset_facets_p RENAME TO dataset_facets;
        ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_dataset_uuid_fkey
            FOREIGN KEY (dataset_uuid) REFERENCES datasets (uuid) ON DELETE CASCADE;
        ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_dataset_version_uuid_fkey
            FOREIGN KEY (dataset_version_uuid) REFERENCES dataset_versions (uuid) ON DELETE CASCADE;
        ALTER TABLE dataset_facets ADD CONSTRAINT dataset_facets_run_uuid_fkey
            FOREIGN KEY (run_uuid) REFERENCES runs (uuid) ON DELETE CASCADE;
        EXECUTE 'CREATE VIEW dataset_facets_view AS '
             || 'SELECT created_at, dataset_uuid, dataset_version_uuid, run_uuid, lineage_event_time, '
             || 'lineage_event_type, type, name, facet FROM dataset_facets';
        DROP TABLE dataset_facets_old;

        INSERT INTO dataset_facets_partition_state (singleton, state, cutover_at)
             VALUES (true, 'SWAPPED', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'SWAPPED', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'dataset_facets partitioned inline (% bytes <= 1 GiB).', v_bytes;
    ELSE
        EXECUTE format(
            'CREATE TRIGGER dataset_facets_mirror_trg AFTER INSERT ON dataset_facets '
            || 'FOR EACH ROW WHEN (NEW.created_at >= %L::timestamptz) '
            || 'EXECUTE FUNCTION dataset_facets_mirror_to_partition()', v_cutover);

        INSERT INTO dataset_facets_partition_state (singleton, state, cutover_at)
             VALUES (true, 'DUAL_WRITE', v_cutover)
        ON CONFLICT (singleton) DO UPDATE SET state = 'DUAL_WRITE', cutover_at = EXCLUDED.cutover_at;

        RAISE NOTICE 'dataset_facets online cutover armed (% bytes > 1 GiB); DATASET_FACETS_PARTITION_V1 will copy + swap.', v_bytes;
    END IF;
END $$;
