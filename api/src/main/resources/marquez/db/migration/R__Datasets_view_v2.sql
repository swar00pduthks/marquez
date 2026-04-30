-- datasets_view_v2: V2 read path for datasets.
-- Primary source: dataset_denormalized (hash-partitioned by namespace_uuid — fast namespace scans,
-- pre-aggregated tags[] avoiding datasets_tag_mapping join).
-- Fallback: LEFT JOIN datasets for correctness when denorm row is missing or columns are NULL.
-- Unlike datasets_view, symlink resolution is NOT performed; only canonical primary datasets
-- are exposed. The V2 API contract does not surface symlink aliases.
CREATE OR REPLACE VIEW datasets_view_v2 AS
SELECT
    dd.uuid,
    COALESCE(dd.type,          d.type)                     AS type,
    COALESCE(dd.created_at,    d.created_at)               AS created_at,
    COALESCE(dd.updated_at,    d.updated_at)               AS updated_at,
    dd.namespace_uuid,
    COALESCE(dd.source_uuid,   d.source_uuid)              AS source_uuid,
    COALESCE(dd.name,          d.name)                     AS name,
    COALESCE(dd.physical_name, d.physical_name)            AS physical_name,
    COALESCE(dd.description,   d.description)              AS description,
    dd.current_version_uuid,
    dd.last_modified_at,
    COALESCE(dd.namespace_name, d.namespace_name)          AS namespace_name,
    COALESCE(dd.source_name,   d.source_name)              AS source_name,
    -- is_deleted in denorm maps to is_hidden in datasets; fallback to normalized is_hidden
    COALESCE(d.is_hidden, dd.is_deleted, false)            AS is_deleted,
    COALESCE(dd.tags, ARRAY[]::TEXT[])                     AS tags,
    dd.schema_location,
    dd.lifecycle_state
FROM dataset_denormalized dd
LEFT JOIN datasets d ON d.uuid = dd.uuid
WHERE COALESCE(d.is_hidden, dd.is_deleted, false) IS FALSE;
