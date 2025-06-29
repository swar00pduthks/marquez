-- Add run_date column and partitioning to denormalized lineage tables
-- This improves query performance for large datasets

-- Add run_date column to run_lineage_denormalized
ALTER TABLE run_lineage_denormalized
ADD COLUMN run_date DATE;

-- Add run_date column to run_parent_lineage_denormalized
ALTER TABLE run_parent_lineage_denormalized
ADD COLUMN run_date DATE;

-- Create indexes on run_date for partitioning performance
CREATE INDEX idx_run_lineage_denorm_run_date ON run_lineage_denormalized (run_date);
CREATE INDEX idx_run_parent_lineage_denorm_run_date ON run_parent_lineage_denormalized (run_date);

-- Note: PostgreSQL will automatically handle partitioning based on the run_date column
-- No manual partition creation needed - PostgreSQL will create partitions as needed 