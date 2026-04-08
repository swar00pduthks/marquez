-- SPDX-License-Identifier: Apache-2.0
-- V97: Initialise the Apache AGE graph schema for V3 lineage.
--
-- WHY: The marquez_graph AGE graph and its vertex/edge label tables are created lazily by
-- GraphWriter.java only when the first OpenLineage event arrives. This means that on a fresh
-- database the label tables do not exist at migration time, so V98 (which creates BTREE indexes
-- on those tables) always skips — leaving the database without indexes until after the first
-- event is processed.
--
-- This migration eagerly creates the graph and all vertex/edge labels so that:
--   1. V98 can reliably create its indexes during migration (not just on first event).
--   2. The schema is deterministic and auditable via Flyway history.
--
-- IDEMPOTENCY: Every step is guarded — already-existing objects are skipped safely.
--
-- DEPENDENCY: AGE must be installed (CREATE EXTENSION age) and accessible to the marquez role.
--   - shared_preload_libraries = 'age' must be set in postgresql.conf (baked into our image
--     and in docker/postgresql.conf) so the AGE library is loaded at server start.
--   - init-db.sh grants USAGE + EXECUTE on ag_catalog to the marquez role.
--   If AGE is not available this migration is a safe no-op.

DO $$
DECLARE
  v_graph_oid  oid;
  v_label      text;
  e_label      text;
BEGIN
  -- Guard 1: AGE extension must be installed.
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'age') THEN
    RAISE NOTICE 'V97: AGE extension not installed — skipping graph initialisation.';
    RETURN;
  END IF;

  -- Guard 2: ag_catalog must be accessible (requires shared_preload_libraries = ''age'' and
  -- USAGE granted to marquez). Wrap in exception so missing permissions are a graceful skip,
  -- not a hard failure.
  BEGIN

    -- Create the graph if it does not already exist.
    IF NOT EXISTS (SELECT 1 FROM ag_catalog.ag_graph WHERE name = 'marquez_graph') THEN
      PERFORM ag_catalog.create_graph('marquez_graph');
      RAISE NOTICE 'V97: Created marquez_graph.';
    ELSE
      RAISE NOTICE 'V97: marquez_graph already exists — skipping create_graph.';
    END IF;

    -- Resolve graph OID for label existence checks.
    SELECT graphid INTO v_graph_oid FROM ag_catalog.ag_graph WHERE name = 'marquez_graph';

    -- Vertex labels -------------------------------------------------------
    FOREACH v_label IN ARRAY ARRAY[
      'Job',
      'Dataset',
      'DatasetField',
      'DatasetVersion',
      'JobVersion',
      'Namespace',
      'Run',
      'Source'
    ] LOOP
      IF NOT EXISTS (
        SELECT 1 FROM ag_catalog.ag_label
         WHERE graph = v_graph_oid AND name = v_label AND kind = 'v'
      ) THEN
        PERFORM ag_catalog.create_vlabel('marquez_graph', v_label);
        RAISE NOTICE 'V97: Created vertex label %.', v_label;
      END IF;
    END LOOP;

    -- Edge labels ---------------------------------------------------------
    FOREACH e_label IN ARRAY ARRAY[
      'CONTAINS',
      'DERIVED_FROM',
      'HAS_CHILD_RUN',
      'HAS_DATASET_VERSION',
      'HAS_FIELD',
      'HAS_JOB_VERSION',
      'HAS_NAMESPACE',
      'HAS_RUN',
      'INPUT_TO',
      'PRODUCES',
      'READS',
      'RUN_OF',
      'VERSION_OF',
      'WRITES'
    ] LOOP
      IF NOT EXISTS (
        SELECT 1 FROM ag_catalog.ag_label
         WHERE graph = v_graph_oid AND name = e_label AND kind = 'e'
      ) THEN
        PERFORM ag_catalog.create_elabel('marquez_graph', e_label);
        RAISE NOTICE 'V97: Created edge label %.', e_label;
      END IF;
    END LOOP;

    RAISE NOTICE 'V97: marquez_graph initialised with all vertex and edge labels.';

  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'V97: ag_catalog not accessible (AGE not in shared_preload_libraries or '
                 'marquez lacks USAGE/EXECUTE on ag_catalog): % — skipping.', SQLERRM;
  END;
END;
$$;
