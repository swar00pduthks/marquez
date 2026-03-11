-- Add missing columns to job_denormalized table that JobMapper expects
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS namespace_name VARCHAR(255);
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS simple_name VARCHAR(255);
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS parent_job_uuid UUID;
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS parent_job_name VARCHAR(255);
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS current_location VARCHAR;
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS current_inputs JSONB;

-- Add missing columns to dataset_denormalized table that DatasetMapper expects
ALTER TABLE dataset_denormalized ADD COLUMN IF NOT EXISTS namespace_name VARCHAR(255);
ALTER TABLE dataset_denormalized ADD COLUMN IF NOT EXISTS source_name VARCHAR(255);
ALTER TABLE dataset_denormalized ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMP;
ALTER TABLE dataset_denormalized ADD COLUMN IF NOT EXISTS is_deleted BOOLEAN;
