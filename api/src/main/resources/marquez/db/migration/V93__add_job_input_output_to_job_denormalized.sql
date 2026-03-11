ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS input_uuids UUID[];
ALTER TABLE job_denormalized ADD COLUMN IF NOT EXISTS output_uuids UUID[];
