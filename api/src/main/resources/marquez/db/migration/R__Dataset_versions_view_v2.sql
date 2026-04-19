-- dataset_versions_view_v2: V2 read path for dataset versions.
-- Primary source: dataset_version_denormalized (immutable per-version rows; has fields,
-- schema_location, lifecycle_state pre-computed — eliminates stream_versions join).
-- INNER JOIN dataset_versions: always safe (every denorm row was created from a normalized
--   row); needed for run_uuid (createdByRun) and dataset_schema_version_uuid not in denorm.
-- LEFT JOIN dataset_denormalized: for dataset-level metadata + pre-aggregated tags[].
--   Avoids datasets_tag_mapping subquery and datasets_view symlink resolution overhead.
-- LEFT JOIN datasets: fallback correctness when dataset_denormalized row is missing.
CREATE OR REPLACE VIEW dataset_versions_view_v2 AS
SELECT
    dvd.uuid,
    dvd.dataset_uuid,
    dvd.namespace_uuid,
    dvd.version,
    dvd.created_at,
    COALESCE(dvd.fields,          dv.fields)          AS fields,
    dvd.schema_location,
    COALESCE(dvd.lifecycle_state, dv.lifecycle_state) AS lifecycle_state,
    -- Dataset-level metadata: denorm first, fallback to normalized datasets table
    COALESCE(dd.type,          d.type)                AS type,
    COALESCE(dd.name,          d.name)                AS name,
    COALESCE(dd.physical_name, d.physical_name)       AS physical_name,
    COALESCE(dd.namespace_name,d.namespace_name)      AS namespace_name,
    COALESCE(dd.source_name,   d.source_name)         AS source_name,
    COALESCE(dd.description,   d.description)         AS description,
    -- Tags pre-aggregated in dataset_denormalized; fall back to empty array if missing
    COALESCE(dd.tags, ARRAY[]::TEXT[])                AS tags,
    -- These columns only exist in dataset_versions (not in any denorm table)
    dv.run_uuid                                       AS run_uuid,
    dv.dataset_schema_version_uuid
FROM dataset_version_denormalized dvd
INNER JOIN dataset_versions dv      ON dv.uuid = dvd.uuid
LEFT  JOIN dataset_denormalized dd  ON dd.uuid = dvd.dataset_uuid
                                    AND dd.namespace_uuid = dvd.namespace_uuid
LEFT  JOIN datasets d               ON d.uuid  = dvd.dataset_uuid;
