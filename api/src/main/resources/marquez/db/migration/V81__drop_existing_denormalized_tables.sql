-- Drop existing denormalized tables to prepare for partitioned recreation
-- This migration drops the existing V76/V77/V79 tables so they can be recreated as partitioned tables

-- Drop existing denormalized tables
DROP TABLE IF EXISTS run_lineage_denormalized CASCADE;
DROP TABLE IF EXISTS run_parent_lineage_denormalized CASCADE;
