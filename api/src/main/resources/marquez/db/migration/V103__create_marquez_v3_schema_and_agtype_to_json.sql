-- SPDX-License-Identifier: Apache-2.0
-- V103: Create marquez_v3 schema and agtype_to_json() helper function.
--
-- WHY: V3 Graph API query paths (OpenLineageResourceV3, RunResourceV3, etc.) call
-- agtype_to_json(agtype) to convert AGE query results into JSON. This function was
-- historically created manually in Azure's marquez_v3 schema (see GraphDao.java
-- which sets search_path = ag_catalog, marquez_v3, "$user", public) but was never
-- shipped as a Flyway migration. On fresh databases (CI, local testcontainers, and
-- any new deployment) the function is missing and all V3 query endpoints fail with
-- "function ag_catalog.agtype_to_json(agtype) does not exist".
--
-- This migration ships the exact same definition that lives in the Azure database,
-- so every environment has it automatically.
--
-- IDEMPOTENCY: CREATE SCHEMA IF NOT EXISTS and CREATE OR REPLACE FUNCTION are both
-- safe to re-run. On Azure where the function already exists the definitions match
-- and the replace is a no-op.
--
-- DEPENDENCY: AGE must be installed (V95) and ag_catalog accessible. If AGE is not
-- available this migration is a safe no-op.

DO $$
BEGIN
  -- Outer guard: skip entirely when ageEnabled=false (Flyway placeholder).
  IF '${ageEnabled}' = 'false' THEN
    RAISE NOTICE 'V103: ageEnabled=false — skipping marquez_v3 schema and agtype_to_json.';
    RETURN;
  END IF;

  -- Guard: AGE extension must be installed.
  IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'age') THEN
    RAISE NOTICE 'V103: AGE extension not installed — skipping.';
    RETURN;
  END IF;

  BEGIN
    -- 1. Schema ----------------------------------------------------------
    CREATE SCHEMA IF NOT EXISTS marquez_v3;
    RAISE NOTICE 'V103: ensured marquez_v3 schema exists.';

    -- 2. agtype_to_json function ----------------------------------------
    -- Definition copied verbatim from production Azure (marquez-test-5mlj).
    -- Strips AGE metadata annotations (::vertex, ::edge, ::path, ::agtype) so
    -- Java-side JSON parsing works without custom agtype handling.
    CREATE OR REPLACE FUNCTION marquez_v3.agtype_to_json(val ag_catalog.agtype)
    RETURNS json
    LANGUAGE plpgsql
    IMMUTABLE
    AS $func$
    DECLARE
        txt text;
    BEGIN
        IF val IS NULL THEN RETURN NULL; END IF;

        -- Get the string representation from AGE
        txt := ag_catalog.agtype_out(val)::text;

        -- Strip AGE metadata annotations (::vertex, ::edge, ::path, ::agtype)
        -- Using regex to remove any '::' followed by letters at the end of JSON objects or arrays
        txt := regexp_replace(txt, '::[a-z]+', '', 'g');

        RETURN txt::json;
    EXCEPTION WHEN OTHERS THEN
        -- Fallback to basic text casting if regex fails
        BEGIN
            RETURN (val::text)::json;
        EXCEPTION WHEN OTHERS THEN
            RETURN NULL;
        END;
    END;
    $func$;

    RAISE NOTICE 'V103: created/replaced marquez_v3.agtype_to_json(agtype).';

    -- 3. Grants ---------------------------------------------------------
    -- Allow the marquez application role (and PUBLIC, to match Azure) to use the
    -- schema and call the function.
    GRANT USAGE ON SCHEMA marquez_v3 TO PUBLIC;
    GRANT EXECUTE ON FUNCTION marquez_v3.agtype_to_json(ag_catalog.agtype) TO PUBLIC;

  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'V103: could not create marquez_v3 schema/function (likely missing '
                 'ag_catalog access): % — skipping.', SQLERRM;
  END;
END;
$$;
