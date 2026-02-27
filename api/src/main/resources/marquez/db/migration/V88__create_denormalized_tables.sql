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

-- Create hash partitions for dataset_denormalized
CREATE TABLE dataset_denormalized_p0 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE dataset_denormalized_p1 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE dataset_denormalized_p2 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE dataset_denormalized_p3 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE dataset_denormalized_p4 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE dataset_denormalized_p5 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE dataset_denormalized_p6 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE dataset_denormalized_p7 PARTITION OF dataset_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 7);

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

-- Create hash partitions for dataset_version_denormalized
CREATE TABLE dataset_version_denormalized_p0 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE dataset_version_denormalized_p1 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE dataset_version_denormalized_p2 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE dataset_version_denormalized_p3 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE dataset_version_denormalized_p4 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE dataset_version_denormalized_p5 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE dataset_version_denormalized_p6 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE dataset_version_denormalized_p7 PARTITION OF dataset_version_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 7);

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
    PRIMARY KEY (uuid, namespace_uuid),
    UNIQUE (namespace_uuid, name)
) PARTITION BY HASH (namespace_uuid);

-- Create hash partitions for job_denormalized
CREATE TABLE job_denormalized_p0 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 0);
CREATE TABLE job_denormalized_p1 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 1);
CREATE TABLE job_denormalized_p2 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 2);
CREATE TABLE job_denormalized_p3 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 3);
CREATE TABLE job_denormalized_p4 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 4);
CREATE TABLE job_denormalized_p5 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 5);
CREATE TABLE job_denormalized_p6 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 6);
CREATE TABLE job_denormalized_p7 PARTITION OF job_denormalized FOR VALUES WITH (MODULUS 8, REMAINDER 7);

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
