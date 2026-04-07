-- Drop facets column from denormalized tables and repopulate
-- This removes the redundant facets column since facets are now handled via joins

-- Step 1: Drop facets column from run_lineage_denormalized table
ALTER TABLE run_lineage_denormalized DROP COLUMN IF EXISTS facets;

-- Step 2: Drop facets column from run_parent_lineage_denormalized table
ALTER TABLE run_parent_lineage_denormalized DROP COLUMN IF EXISTS facets;

-- Step 3: Clear existing data to ensure clean repopulation
DELETE FROM run_lineage_denormalized;
DELETE FROM run_parent_lineage_denormalized;
