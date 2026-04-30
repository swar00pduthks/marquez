-- V105: Index to support fast "latest run for a given job" lookup against
-- run_lineage_denormalized.
--
-- Used by RunDao.findLatestRunByJobFromDenorm, which replaces the per-job
-- BASE_FIND_RUN_SQL sub-call (5 LEFT JOIN + GROUP BY across runs/run_facets/
-- run_args/job_versions/runs_input_mapping/dataset_versions/dataset_facets)
-- in JobDao.findAllWithRun. With this index in place, locating the latest
-- run_uuid for a job is an index-only scan on the current monthly partition.
--
-- Note: PostgreSQL does NOT support CREATE INDEX CONCURRENTLY on partitioned
-- tables (run_lineage_denormalized is RANGE partitioned by run_date). The
-- non-concurrent form takes a SHARE lock on the partitioned table, which
-- briefly blocks writers — acceptable here because Flyway runs at startup
-- before the API begins accepting OpenLineage POSTs. PostgreSQL automatically
-- creates a matching index on every existing and future child partition.

CREATE INDEX IF NOT EXISTS idx_run_lineage_denorm_job_uuid_created
    ON run_lineage_denormalized (job_uuid, created_at DESC);
