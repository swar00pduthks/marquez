-- SPDX-License-Identifier: Apache-2.0
-- V109: Generalize lineage_edges to job<->dataset edges (job & dataset lineage)
--
-- lineage_edges already stores run<->dataset_version edges (run / dataset-version
-- lineage). Extend the same adjacency table + BFS pattern to JOB and DATASET
-- lineage by adding job<->dataset edges:
--   INPUT  (job reads dataset):  dataset --CONSUMES--> job
--   OUTPUT (job writes dataset): job    --PRODUCES--> dataset
-- A BFS over these reproduces the job-lineage adjacency (two jobs are connected
-- when they share ANY dataset): job -> all its datasets -> all jobs touching them.
--
-- Job edges have no run, so run_uuid is relaxed to NULL. The partition key run_date
-- is the date the job version became current (made_current_at), and namespace is the
-- job's namespace — so job edges get the same RANGE(run_date)->HASH(namespace)
-- tenant isolation + retention as run edges. node-id spaces are disjoint (job/dataset
-- UUIDs never collide with run/dataset_version UUIDs), so the two edge families
-- coexist without ambiguity.

-- 1. Job edges carry no run.
ALTER TABLE lineage_edges ALTER COLUMN run_uuid DROP NOT NULL;

-- 2. Backfill current job<->dataset edges from job_versions_io_mapping.
--    CONSUMES: input dataset -> job
INSERT INTO lineage_edges (
    from_node_id, from_type, to_node_id, to_type, edge_type,
    namespace, run_uuid, run_date, created_at
)
SELECT DISTINCT
    jvio.dataset_uuid AS from_node_id,
    'dataset'         AS from_type,
    jvio.job_uuid     AS to_node_id,
    'job'             AS to_type,
    'CONSUMES'        AS edge_type,
    j.namespace_name  AS namespace,
    NULL::uuid        AS run_uuid,
    DATE(COALESCE(jvio.made_current_at, j.updated_at, j.created_at)) AS run_date,
    NOW()             AS created_at
FROM job_versions_io_mapping jvio
INNER JOIN jobs j ON j.uuid = jvio.job_uuid
WHERE jvio.is_current_job_version = TRUE
  AND jvio.io_type = 'INPUT'
ON CONFLICT (from_node_id, to_node_id, edge_type, run_date, namespace) DO NOTHING;

--    PRODUCES: job -> output dataset
INSERT INTO lineage_edges (
    from_node_id, from_type, to_node_id, to_type, edge_type,
    namespace, run_uuid, run_date, created_at
)
SELECT DISTINCT
    jvio.job_uuid     AS from_node_id,
    'job'             AS from_type,
    jvio.dataset_uuid AS to_node_id,
    'dataset'         AS to_type,
    'PRODUCES'        AS edge_type,
    j.namespace_name  AS namespace,
    NULL::uuid        AS run_uuid,
    DATE(COALESCE(jvio.made_current_at, j.updated_at, j.created_at)) AS run_date,
    NOW()             AS created_at
FROM job_versions_io_mapping jvio
INNER JOIN jobs j ON j.uuid = jvio.job_uuid
WHERE jvio.is_current_job_version = TRUE
  AND jvio.io_type = 'OUTPUT'
ON CONFLICT (from_node_id, to_node_id, edge_type, run_date, namespace) DO NOTHING;
