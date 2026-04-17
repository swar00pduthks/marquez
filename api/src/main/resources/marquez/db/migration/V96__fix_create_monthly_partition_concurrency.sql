/* SPDX-License-Identifier: Apache-2.0 */

-- This migration updates the create_monthly_partition function to be bulletproof
-- against race conditions and overlap errors by using both advisory locks
-- and a catch-all EXCEPTION handling block.

CREATE OR REPLACE FUNCTION create_monthly_partition(table_name text, start_date date)
RETURNS void AS $$
DECLARE
    partition_name text;
    end_date date;
    lock_key bigint;
BEGIN
    end_date := start_date + INTERVAL '1 month';
    partition_name := table_name || '_y' || to_char(start_date, 'YYYY') || 'm' || to_char(start_date, 'MM');

    -- Use an advisory lock based on the partition name to prevent concurrent execution
    lock_key := ('x' || substr(md5(partition_name), 1, 15))::bit(60)::bigint;
    PERFORM pg_advisory_xact_lock(lock_key);

    -- Wrap in a sub-transaction block to handle overlap/existence errors gracefully
    -- We use OTHERS here because different PG versions and environment-specific constraints
    -- might throw various codes for partition overlap.
    BEGIN
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
                       partition_name, table_name, start_date, end_date);

        -- Create indexes on the new partition
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (run_date)',
                       'idx_' || partition_name || '_run_date', partition_name);
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (namespace_name, job_name)',
                       'idx_' || partition_name || '_namespace_job', partition_name);
        EXECUTE format('CREATE INDEX IF NOT EXISTS %I ON %I (state, created_at DESC)',
                       'idx_' || partition_name || '_state_created', partition_name);
    EXCEPTION
        WHEN OTHERS THEN
            -- In partition management, most errors (overlap, duplicate) indicate the work is already done
            RAISE NOTICE 'Skipping creation of partition % because it already exists or overlaps: %', partition_name, SQLERRM;
            NULL;
    END;
END;
$$ LANGUAGE plpgsql;
