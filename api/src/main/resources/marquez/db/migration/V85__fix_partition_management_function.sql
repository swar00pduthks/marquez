-- Fix partition management function to check if partition already exists
-- This migration updates the create_monthly_partition function to handle existing partitions gracefully

CREATE OR REPLACE FUNCTION create_monthly_partition(table_name text, start_date date)
RETURNS void AS $$
DECLARE
    partition_name text;
    end_date date;
    partition_exists boolean;
BEGIN
    end_date := start_date + INTERVAL '1 month';
    partition_name := table_name || '_y' || to_char(start_date, 'YYYY') || 'm' || to_char(start_date, 'MM');
    
    -- Check if partition already exists
    SELECT EXISTS (
        SELECT 1 FROM pg_tables 
        WHERE tablename = partition_name 
        AND schemaname = current_schema()
    ) INTO partition_exists;
    
    -- Only create partition if it doesn't exist
    IF NOT partition_exists THEN
        EXECUTE format('CREATE TABLE %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                       partition_name, table_name, start_date, end_date);
        
        -- Create indexes on the new partition
        EXECUTE format('CREATE INDEX %I ON %I (run_date)',
                       'idx_' || partition_name || '_run_date', partition_name);
        EXECUTE format('CREATE INDEX %I ON %I (namespace_name, job_name)',
                       'idx_' || partition_name || '_namespace_job', partition_name);
        EXECUTE format('CREATE INDEX %I ON %I (state, created_at DESC)',
                       'idx_' || partition_name || '_state_created', partition_name);
    END IF;
END;
$$ LANGUAGE plpgsql;
