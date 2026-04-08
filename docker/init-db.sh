#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Usage: $ ./init-db.sh

set -eu

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" > /dev/null <<-EOSQL
  CREATE USER ${MARQUEZ_USER};
  ALTER USER ${MARQUEZ_USER} WITH PASSWORD '${MARQUEZ_PASSWORD}';
  CREATE DATABASE ${MARQUEZ_DB};
  GRANT ALL PRIVILEGES ON DATABASE ${MARQUEZ_DB} TO ${MARQUEZ_USER};
EOSQL

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname="${MARQUEZ_DB}" > /dev/null <<-EOSQL
  CREATE EXTENSION IF NOT EXISTS age;
  -- Grant the marquez app user access to ag_catalog so Flyway migrations and
  -- the application can query AGE catalog tables and call AGE functions.
  GRANT USAGE ON SCHEMA ag_catalog TO ${MARQUEZ_USER};
  GRANT SELECT ON ALL TABLES IN SCHEMA ag_catalog TO ${MARQUEZ_USER};
  GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA ag_catalog TO ${MARQUEZ_USER};
  ALTER DEFAULT PRIVILEGES IN SCHEMA ag_catalog GRANT SELECT ON TABLES TO ${MARQUEZ_USER};
  ALTER DEFAULT PRIVILEGES IN SCHEMA ag_catalog GRANT EXECUTE ON FUNCTIONS TO ${MARQUEZ_USER};
EOSQL
