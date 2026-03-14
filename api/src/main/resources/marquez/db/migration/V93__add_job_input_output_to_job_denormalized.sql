ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS input_uuids UUID[];
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS output_uuids UUID[];

-- Backfill input_uuids and output_uuids for existing job_denormalized records
UPDATE job_denormalized jd
SET input_uuids = COALESCE(job_io.inputs, ARRAY[]::uuid[]),
    output_uuids = COALESCE(job_io.outputs, ARRAY[]::uuid[])
FROM (
  SELECT
    j.uuid AS job_uuid,
    ARRAY_AGG(DISTINCT io.dataset_uuid) FILTER (WHERE io.io_type = 'INPUT' AND io.dataset_uuid IS NOT NULL) AS inputs,
    ARRAY_AGG(DISTINCT io.dataset_uuid) FILTER (WHERE io.io_type = 'OUTPUT' AND io.dataset_uuid IS NOT NULL) AS outputs
  FROM jobs j
  LEFT JOIN job_versions_io_mapping io ON io.job_uuid = j.uuid AND io.is_current_job_version = TRUE
  GROUP BY j.uuid
) job_io
WHERE jd.uuid = job_io.job_uuid;
