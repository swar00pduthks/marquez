-- SPDX-License-Identifier: Apache-2.0
-- V98: Replace GIN indexes on AGE node labels with BTREE expression indexes
--
-- WHY: V97 (graph init) created GIN indexes on the `properties` column of each AGE
-- node label table. GIN is designed for containment queries (@>) and full-text search.
-- AGE translates Cypher MATCH equality predicates (e.g., {fqn: 'ns:job'}) to SQL
-- expressions like `properties->>'fqn' = 'value'`, which require BTREE indexes to
-- avoid full table scans at scale.
--
-- Each label table lives in the "marquez_graph" schema under Apache AGE.
-- We create one BTREE index per lookup key per label. IF NOT EXISTS makes this
-- idempotent and safe to re-run.
--
-- Labels and their primary match keys:
--   Job            -> fqn
--   Dataset        -> fqn
--   JobVersion     -> uuid
--   DatasetVersion -> uuid
--   Run            -> runId
--   Namespace      -> name
--   Source         -> name
--
-- NOTE: This migration is a no-op if the marquez_graph schema does not yet exist
-- (i.e., AGE extension is not installed). The DO block guards against that case.

DO $$
DECLARE
  graph_exists boolean;
BEGIN
  -- Guard 1: AGE extension must be installed (safe to check via pg_extension — no schema needed)
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'age') THEN
    RAISE NOTICE 'AGE extension not found - skipping index creation.';
    RETURN;
  END IF;

  -- NOTE: Do NOT call LOAD 'age' here. PostgreSQL restricts LOAD to superusers unless
  -- the library is in $libdir/plugins/. The marquez role is a non-superuser so it would
  -- get "access to library is not allowed". AGE is loaded at server start via
  -- shared_preload_libraries = 'age' in postgresql.conf — no explicit LOAD needed.

  -- Guard 2: marquez_graph must exist. ag_catalog.ag_graph is a plain PostgreSQL table
  -- accessible when AGE is loaded via shared_preload_libraries and marquez has USAGE on
  -- ag_catalog (granted in init-db.sh). The label tables (e.g. marquez_graph."Job") are
  -- created lazily on first vertex insert, so this migration is a no-op on fresh installs.
  BEGIN
    EXECUTE 'SELECT EXISTS(SELECT 1 FROM ag_catalog.ag_graph WHERE name = ''marquez_graph'')'
      INTO graph_exists;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'ag_catalog not accessible (AGE may not be in shared_preload_libraries or '
                 'marquez lacks USAGE on ag_catalog): % — skipping index creation.', SQLERRM;
    RETURN;
  END;
  IF NOT graph_exists THEN
    RAISE NOTICE 'marquez_graph not found - skipping index creation.';
    RETURN;
  END IF;

  BEGIN
    -- Drop old GIN indexes that won't be used for equality lookups
    DROP INDEX IF EXISTS marquez_graph.idx_age_job_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_dataset_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_jobversion_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_datasetversion_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_run_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_runstate_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_namespace_props;
    DROP INDEX IF EXISTS marquez_graph.idx_age_source_props;

    -- Job: match by fqn
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_job_fqn
             ON marquez_graph."Job" ((properties->>''fqn''))';

    -- Dataset: match by fqn
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_dataset_fqn
             ON marquez_graph."Dataset" ((properties->>''fqn''))';

    -- JobVersion: match by uuid
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_jobversion_uuid
             ON marquez_graph."JobVersion" ((properties->>''uuid''))';

    -- DatasetVersion: match by uuid; also index datasetFqn for reverse lookups
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_datasetversion_uuid
             ON marquez_graph."DatasetVersion" ((properties->>''uuid''))';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_datasetversion_dataset_fqn
             ON marquez_graph."DatasetVersion" ((properties->>''datasetFqn''))';

    -- Run: match by runId; also index state for aggregation queries
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_run_run_id
             ON marquez_graph."Run" ((properties->>''runId''))';
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_run_state
             ON marquez_graph."Run" ((properties->>''state''))';

    -- Namespace: match by name
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_namespace_name
             ON marquez_graph."Namespace" ((properties->>''name''))';

    -- Source: match by name
    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_age_source_name
             ON marquez_graph."Source" ((properties->>''name''))';

    RAISE NOTICE 'AGE BTREE indexes created successfully on marquez_graph labels.';
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'AGE index creation skipped or partial: %', SQLERRM;
  END;
END;
$$;
