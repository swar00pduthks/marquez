-- SPDX-License-Identifier: Apache-2.0
-- V88: Create denormalized tables for datasets, dataset versions, and jobs
-- This migration also removes VARCHAR(255) length restrictions from denormalized lineage tables

CREATE TABLE dataset_denormalized (
    uuid UUID NOT NULL,
    type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    namespace_uuid UUID NOT NULL,
    source_uuid UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    physical_name VARCHAR(255) NOT NULL,
    description TEXT,
    current_version_uuid UUID,
    tags TEXT[],
    schema_location VARCHAR(255),
    lifecycle_state VARCHAR(64),
    PRIMARY KEY (uuid, namespace_uuid),
    UNIQUE (namespace_uuid, source_uuid, name, physical_name)
) PARTITION BY HASH (namespace_uuid);

CREATE TABLE dataset_version_denormalized (
    uuid UUID NOT NULL,
    dataset_uuid UUID NOT NULL,
    namespace_uuid UUID NOT NULL,
    version UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    fields JSONB,
    facets JSONB,
    schema_location VARCHAR(255),
    lifecycle_state VARCHAR(64),
    PRIMARY KEY (uuid, namespace_uuid),
    UNIQUE (dataset_uuid, version, namespace_uuid)
) PARTITION BY HASH (namespace_uuid);

CREATE TABLE job_denormalized (
    uuid UUID NOT NULL,
    type VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    namespace_uuid UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    current_version_uuid UUID,
    tags TEXT[],
    lifecycle_state VARCHAR(64),
    PRIMARY KEY (uuid, namespace_uuid),
    UNIQUE (namespace_uuid, name)
) PARTITION BY HASH (namespace_uuid);

-- Remove VARCHAR(255) length restriction from denormalized lineage tables
ALTER TABLE run_lineage_denormalized
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN job_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN input_dataset_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_version TYPE VARCHAR,
  ALTER COLUMN output_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN output_dataset_name TYPE VARCHAR,
  ALTER COLUMN output_dataset_version TYPE VARCHAR;

ALTER TABLE run_parent_lineage_denormalized
  ALTER COLUMN namespace_name TYPE VARCHAR,
  ALTER COLUMN job_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN input_dataset_name TYPE VARCHAR,
  ALTER COLUMN input_dataset_version TYPE VARCHAR,
  ALTER COLUMN output_dataset_namespace TYPE VARCHAR,
  ALTER COLUMN output_dataset_name TYPE VARCHAR,
  ALTER COLUMN output_dataset_version TYPE VARCHAR;
