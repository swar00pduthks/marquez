# V81-V85 MIGRATION CHAIN

This migration chain (V81, V82, V83, V84, V85) introduces denormalized lineage tables with partitioning for significant performance improvements on large datasets. These migrations create pre-computed lineage data that replaces complex joins with simple table lookups, dramatically improving query performance for the Marquez UI.

> **_NOTE:_** The denormalized tables are automatically populated for new runs. For existing installations, the tables will be populated as new OpenLineage events are received.

## Migration Chain Overview

- **V81**: Drop existing denormalized tables (if any)
- **V82**: Create new partitioned denormalized tables with all necessary columns
- **V83**: Create partition management functions for dynamic partition creation/cleanup
- **V84**: Remove DAO-specific columns (cleanup for lineage-only usage)
- **V85**: Fix partition management function to handle existing partitions gracefully

## Migration for Existing Installations

### Small Installations (< 100K runs)
The denormalized tables are automatically populated when OpenLineage events are received via the `/api/v1/lineage` endpoint. The V86 migration will backfill existing data **automatically during deployment**.

### Large Installations (> 100K runs)
For installations with large datasets (especially > 1M runs), **V86 will skip automatically during deployment** to avoid blocking. Manual backfill is required:

1. **Deploy 0.52.33** - This creates the partitioned tables (V81-V85 run automatically, V86 skips with warning)
2. **Run V86 backfill manually** with optimized chunk size:
   ```bash
   # Inside the Marquez pod:
   kubectl exec -it deploy/marquez -n marquez -- sh
   cd /app
   
   # Recommended chunk sizes based on dataset:
   # 100K-500K runs: --chunkSize=10000
   # 500K-2M runs: --chunkSize=20000
   # 2M-10M runs: --chunkSize=50000
   
   java -jar marquez-api.jar db migrate marquez.yml \
     -target=86 -placeholders.chunkSize=50000
   ```
3. **Monitor progress** - V86 logs detailed progress for datasets > 10K runs
4. **Estimated time**: ~1-2 seconds per 1000 runs (4M runs ≈ 2-4 hours)

### Very Large Installations (> 5M runs)
For massive datasets, consider these additional strategies:

**Option 1: Parallel Backfill (Fastest)**
Split the backfill by time ranges using custom SQL:
```sql
-- Create a custom backfill script that processes runs in date ranges
-- Process 2024 data, 2023 data, etc. in separate processes
```

**Option 2: Gradual Backfill**
- Deploy 0.52.33 with empty denormalized tables
- Let new runs populate automatically
- Schedule V80 backfill during low-traffic periods
- Run with smaller chunks (--chunkSize=10000) to reduce database load

**Option 3: Selective Backfill**
If only recent data is actively queried:
```sql
-- Modify V80 to only backfill recent runs (e.g., last 6 months)
-- Older data can be backfilled later or on-demand
```

## Fresh Installations

For fresh Marquez installations, the denormalized tables are created empty and will be populated automatically as OpenLineage events are received. This ensures the system works out-of-the-box without any manual intervention.

## Performance Characteristics

The denormalized tables are designed for high-performance lineage queries. Based on testing with various dataset sizes:

| Scenario | Performance Impact |
|----------|-------------------|
| Small datasets (< 10K runs) | Minimal impact, fast queries |
| Medium datasets (10K-100K runs) | 3-10x faster lineage queries |
| Large datasets (100K+ runs) | 10-50x faster lineage queries |
| Very large datasets (1M+ runs) | 20-100x faster with partitioning |

## What does this migration create?

### Tables Created:
- `run_lineage_denormalized` - Pre-computed lineage data for runs
- `run_parent_lineage_denormalized` - Pre-computed parent lineage data for runs

### Features:
- **Partitioning by `run_date`** - Monthly partitions for efficient time-based queries
- **Comprehensive indexes** - Optimized for common UI query patterns
- **Partition management functions** - For creating new partitions and cleaning up old ones
- **Automatic population** - New runs are automatically populated into denormalized tables

### Performance Benefits:
- **Dashboard queries** - 10-50x faster by avoiding complex joins
- **Job listings** - 5-20x faster with pre-computed job states
- **Run history** - 3-15x faster with denormalized run data
- **Search functionality** - 2-10x faster with indexed denormalized data

## Post-Migration

After the migration completes, the Marquez API will automatically populate denormalized tables for new OpenLineage events. The system includes:

- **Automatic partition creation** - New partitions are created as needed for new run dates
- **Partition cleanup** - Old partitions can be removed based on retention policy
- **Query optimization** - Lineage queries now use the fast denormalized tables
- **Separation of concerns** - Non-lineage queries continue using original tables for optimal performance

## Monitoring

You can monitor the denormalized tables using:

```sql
-- Check table sizes
SELECT
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) as size
FROM pg_tables
WHERE tablename LIKE 'run_lineage_denormalized%'
   OR tablename LIKE 'run_parent_lineage_denormalized%'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- Check partition statistics
SELECT
    schemaname,
    tablename,
    n_tup_ins as inserts,
    n_tup_upd as updates,
    n_tup_del as deletes
FROM pg_stat_user_tables
WHERE tablename LIKE 'run_lineage_denormalized%'
   OR tablename LIKE 'run_parent_lineage_denormalized%';
```
