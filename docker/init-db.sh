#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./init-db.sh

set -eu

# Schema the Marquez application and Flyway migrations use. Defaults to "public"
# so existing deployments are unaffected; override with MARQUEZ_SCHEMA to isolate
# Marquez in a dedicated schema. Must match the currentSchema JDBC parameter.
MARQUEZ_SCHEMA="${MARQUEZ_SCHEMA:-public}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" > /dev/null <<-EOSQL
  CREATE USER ${MARQUEZ_USER};
  ALTER USER ${MARQUEZ_USER} WITH PASSWORD '${MARQUEZ_PASSWORD}';
  CREATE DATABASE ${MARQUEZ_DB};
  GRANT ALL PRIVILEGES ON DATABASE ${MARQUEZ_DB} TO ${MARQUEZ_USER};
EOSQL

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname="${MARQUEZ_DB}" > /dev/null <<-EOSQL
  -- PostgreSQL 15+ no longer grants CREATE on the public schema to non-owners.
  -- The Marquez app user must own (and create objects in) its schema, otherwise
  -- Flyway cannot create the schema-history table and migrations fail with
  -- "permission denied for schema public". Required now that the DB image is
  -- PostgreSQL 17. CREATE ... AUTHORIZATION is a no-op when the schema already
  -- exists (e.g. public), so the explicit ALTER OWNER handles that case.
  CREATE SCHEMA IF NOT EXISTS ${MARQUEZ_SCHEMA} AUTHORIZATION ${MARQUEZ_USER};
  ALTER SCHEMA ${MARQUEZ_SCHEMA} OWNER TO ${MARQUEZ_USER};
  GRANT ALL ON SCHEMA ${MARQUEZ_SCHEMA} TO ${MARQUEZ_USER};

  CREATE EXTENSION IF NOT EXISTS age;
  -- Grant the marquez app user access to ag_catalog so Flyway migrations and
  -- the application can query AGE catalog tables and call AGE functions.
  GRANT USAGE ON SCHEMA ag_catalog TO ${MARQUEZ_USER};
  GRANT SELECT ON ALL TABLES IN SCHEMA ag_catalog TO ${MARQUEZ_USER};
  GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA ag_catalog TO ${MARQUEZ_USER};
  ALTER DEFAULT PRIVILEGES IN SCHEMA ag_catalog GRANT SELECT ON TABLES TO ${MARQUEZ_USER};
  ALTER DEFAULT PRIVILEGES IN SCHEMA ag_catalog GRANT EXECUTE ON FUNCTIONS TO ${MARQUEZ_USER};
EOSQL
