-- Create denormalized tables to replace materialized views
-- This allows for event-driven updates instead of scheduled refreshes

-- Table for run lineage data (replaces run_lineage_view)
CREATE TABLE run_lineage_denormalized (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_uuid UUID NOT NULL,
    namespace_name VARCHAR(255),
    job_name VARCHAR(255),
    state VARCHAR(64),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    job_uuid UUID,
    job_version_uuid UUID,
    input_version_uuid UUID,
    input_dataset_uuid UUID,
    output_version_uuid UUID,
    output_dataset_uuid UUID,
    input_dataset_namespace VARCHAR(255),
    input_dataset_name VARCHAR(255),
    input_dataset_version VARCHAR(255),
    input_dataset_version_uuid UUID,
    output_dataset_namespace VARCHAR(255),
    output_dataset_name VARCHAR(255),
    output_dataset_version VARCHAR(255),
    output_dataset_version_uuid UUID,
    uuid UUID,
    parent_run_uuid UUID,
    facets JSONB,
    created_at_denormalized TIMESTAMPTZ DEFAULT NOW()
);

-- Table for parent run lineage data (replaces run_parent_lineage_view)
CREATE TABLE run_parent_lineage_denormalized (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_uuid UUID NOT NULL,
    namespace_name VARCHAR(255),
    job_name VARCHAR(255),
    state VARCHAR(64),
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ,
    job_uuid UUID,
    job_version_uuid UUID,
    input_version_uuid UUID,
    input_dataset_uuid UUID,
    output_version_uuid UUID,
    output_dataset_uuid UUID,
    input_dataset_namespace VARCHAR(255),
    input_dataset_name VARCHAR(255),
    input_dataset_version VARCHAR(255),
    input_dataset_version_uuid UUID,
    output_dataset_namespace VARCHAR(255),
    output_dataset_name VARCHAR(255),
    output_dataset_version VARCHAR(255),
    output_dataset_version_uuid UUID,
    uuid UUID,
    parent_run_uuid UUID,
    facets JSONB,
    created_at_denormalized TIMESTAMPTZ DEFAULT NOW()
);

-- Create indexes for performance
CREATE INDEX idx_run_lineage_denorm_run_uuid ON run_lineage_denormalized (run_uuid);
CREATE INDEX idx_run_lineage_denorm_input_version_uuid ON run_lineage_denormalized (input_version_uuid);
CREATE INDEX idx_run_lineage_denorm_output_version_uuid ON run_lineage_denormalized (output_version_uuid);
CREATE INDEX idx_run_lineage_denorm_parent_run_uuid ON run_lineage_denormalized (parent_run_uuid);

CREATE INDEX idx_run_parent_lineage_denorm_run_uuid ON run_parent_lineage_denormalized (run_uuid);
CREATE INDEX idx_run_parent_lineage_denorm_input_version_uuid ON run_parent_lineage_denormalized (input_version_uuid);
CREATE INDEX idx_run_parent_lineage_denorm_output_version_uuid ON run_parent_lineage_denormalized (output_version_uuid);
CREATE INDEX idx_run_parent_lineage_denorm_parent_run_uuid ON run_parent_lineage_denormalized (parent_run_uuid); 