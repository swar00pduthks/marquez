-- jobs_view_v2: V2 read path for jobs.
-- Primary source: job_denormalized (hash-partitioned by namespace_uuid — fast namespace scans,
-- pre-aggregated tags[] and pre-computed input_uuids/output_uuids arrays).
-- LEFT JOIN jobs for is_hidden and symlink_target_uuid correctness checks (these columns
-- are not stored in job_denormalized but we must exclude hidden/symlink jobs).
CREATE OR REPLACE VIEW jobs_view_v2 AS
SELECT
    jd.uuid,
    jd.type,
    jd.created_at,
    jd.updated_at,
    jd.namespace_uuid,
    jd.name,
    jd.namespace_name,
    jd.simple_name,
    jd.parent_job_uuid,
    jd.parent_job_name,
    jd.description,
    jd.current_version_uuid,
    jd.current_location,
    jd.current_inputs,
    COALESCE(jd.tags, ARRAY[]::TEXT[]) AS tags,
    jd.input_uuids,
    jd.output_uuids
FROM job_denormalized jd
LEFT JOIN jobs j ON j.uuid = jd.uuid
WHERE COALESCE(j.is_hidden, false) IS FALSE
  AND (j.symlink_target_uuid IS NULL OR j.uuid IS NULL);
