ALTER TABLE run_lineage_denormalized ADD COLUMN IF NOT EXISTS input_uuids UUID[];
ALTER TABLE run_lineage_denormalized ADD COLUMN IF NOT EXISTS output_uuids UUID[];

ALTER TABLE run_parent_lineage_denormalized ADD COLUMN IF NOT EXISTS input_uuids UUID[];
ALTER TABLE run_parent_lineage_denormalized ADD COLUMN IF NOT EXISTS output_uuids UUID[];

-- Backfill input_uuids and output_uuids for existing run_lineage_denormalized records
UPDATE run_lineage_denormalized rd
SET input_uuids = COALESCE(run_io.inputs, ARRAY[]::uuid[]),
    output_uuids = COALESCE(run_io.outputs, ARRAY[]::uuid[])
FROM (
  SELECT
    r.uuid AS run_uuid,
    ARRAY_AGG(DISTINCT dvin.dataset_uuid) FILTER (WHERE dvin.dataset_uuid IS NOT NULL) AS inputs,
    ARRAY_AGG(DISTINCT dv.dataset_uuid) FILTER (WHERE dv.dataset_uuid IS NOT NULL) AS outputs
  FROM runs r
  LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
  LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
  LEFT JOIN dataset_versions dv ON dv.run_uuid = r.uuid
  GROUP BY r.uuid
) run_io
WHERE rd.run_uuid = run_io.run_uuid;

-- Backfill input_uuids and output_uuids for existing run_parent_lineage_denormalized records
UPDATE run_parent_lineage_denormalized rd
SET input_uuids = COALESCE(run_io.inputs, ARRAY[]::uuid[]),
    output_uuids = COALESCE(run_io.outputs, ARRAY[]::uuid[])
FROM (
  SELECT
    r.uuid AS run_uuid,
    ARRAY_AGG(DISTINCT dvin.dataset_uuid) FILTER (WHERE dvin.dataset_uuid IS NOT NULL) AS inputs,
    ARRAY_AGG(DISTINCT dv.dataset_uuid) FILTER (WHERE dv.dataset_uuid IS NOT NULL) AS outputs
  FROM runs r
  LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
  LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
  LEFT JOIN dataset_versions dv ON dv.run_uuid = r.uuid
  GROUP BY r.uuid
) run_io
WHERE rd.run_uuid = run_io.run_uuid;
