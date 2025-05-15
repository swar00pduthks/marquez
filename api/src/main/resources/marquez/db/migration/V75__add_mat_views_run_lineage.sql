CREATE MATERIALIZED VIEW IF NOT EXISTS run_lineage_view AS
SELECT
  r.uuid AS run_uuid,
  r.namespace_name,
  r.job_name,
  r.current_run_state AS state,
  r.created_at,
  r.updated_at,
  r.started_at,
  r.ended_at,
  r.job_uuid,
  r.job_version_uuid,
  rim.dataset_version_uuid AS input_version_uuid,
  dvin.dataset_uuid AS input_dataset_uuid,
  dvout.uuid AS output_version_uuid,
  dvout.dataset_uuid AS output_dataset_uuid,
  dvin.namespace_name AS input_dataset_namespace,
  dvin.dataset_name AS input_dataset_name,
  dvin.version AS input_dataset_version,
  dvin.uuid AS input_dataset_version_uuid,
  dvout.namespace_name AS output_dataset_namespace,
  dvout.dataset_name AS output_dataset_name,
  dvout.version AS output_dataset_version,
  dvout.uuid AS output_dataset_version_uuid,
  r.uuid as uuid,
  r.parent_run_uuid as parent_run_uuid,
  rf.facet as facets
FROM runs r
LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
LEFT JOIN run_facets_view rf ON rf.run_uuid = r.uuid;

CREATE MATERIALIZED VIEW IF NOT EXISTS run_parent_lineage_view AS
SELECT DISTINCT
    COALESCE(r.parent_run_uuid,r.uuid) AS run_uuid,
    rp.namespace_name,
    rp.job_name,
    rp.current_run_state AS state,
    rp.created_at,
    rp.updated_at,
    rp.started_at,
    rp.ended_at,
    rp.job_uuid,
    rp.job_version_uuid,
    rim.dataset_version_uuid AS input_version_uuid,
    dvin.dataset_uuid AS input_dataset_uuid,
    dvout.uuid AS output_version_uuid,
    dvout.dataset_uuid AS output_dataset_uuid,
	  dvin.namespace_name AS input_dataset_namespace,
    dvin.dataset_name AS input_dataset_name,
    dvin.version AS input_dataset_version,
    dvin.uuid AS input_dataset_version_uuid,
	  dvout.namespace_name AS output_dataset_namespace,
    dvout.dataset_name AS output_dataset_name,
    dvout.version AS output_dataset_version,
    dvout.uuid AS output_dataset_version_uuid,
    r.uuid as uuid,
    r.parent_run_uuid as parent_run_uuid,
    rf.facet as facets
  FROM runs r
  LEFT JOIN runs_input_mapping rim ON rim.run_uuid = r.uuid
  LEFT JOIN dataset_versions dvin ON dvin.uuid = rim.dataset_version_uuid
  LEFT JOIN dataset_versions dvout ON dvout.run_uuid = r.uuid
  LEFT JOIN runs rp ON rp.uuid=r.parent_run_uuid
  LEFT JOIN run_facets_view rf ON rf.run_uuid=r.uuid
  where r.parent_run_uuid is not null;

  CREATE INDEX IF NOT EXISTS idx_rpl_view_input_version_uuid ON run_parent_lineage_view USING btree (input_version_uuid);
  CREATE INDEX IF NOT EXISTS idx_rpl_view_output_version_uuid ON run_parent_lineage_view USING btree (output_version_uuid);
  CREATE INDEX IF NOT EXISTS idx_rpl_view_run_uuid ON run_parent_lineage_view USING btree (run_uuid);
  CREATE INDEX IF NOT EXISTS idx_rpl_view_parent_run_uuid ON run_parent_lineage_view USING btree (parent_run_uuid);


  CREATE INDEX IF NOT EXISTS idx_rl_view_input_version_uuid ON run_lineage_view USING btree (input_version_uuid);
  CREATE INDEX IF NOT EXISTS idx_rl_view_output_version_uuid ON run_lineage_view USING btree (output_version_uuid);
  CREATE INDEX IF NOT EXISTS idx_rl_view_run_uuid ON run_lineage_view USING btree (run_uuid);
  CREATE INDEX IF NOT EXISTS idx_rl_view_parent_run_uuid ON run_lineage_view USING btree (parent_run_uuid);
