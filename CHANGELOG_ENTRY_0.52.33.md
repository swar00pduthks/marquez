## [0.52.33](https://github.com/MarquezProject/marquez/compare/0.52.30...0.52.33) - TBD

### Added

* API: **Partitioned denormalized lineage tables** for significant performance improvements on large datasets [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * Introduced `run_lineage_denormalized` and `run_parent_lineage_denormalized` tables partitioned by `run_date` with monthly partitions
  * Pre-computed lineage data replaces complex joins with simple table lookups, dramatically improving query performance for the Marquez UI
  * Expected performance improvements: 3-10x for medium datasets (10K-100K runs), 10-50x for large datasets (100K+ runs), 20-100x for very large datasets (1M+ runs)
* API: **New** `PartitionManagementService` for managing database partitions [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * Automatic creation of upcoming partitions (10 days ahead by default)
  * Automatic cleanup of old partitions based on retention policy (12 months default)
  * Partition statistics and monitoring capabilities
  * Partition analysis for better query planning
* API: Database migration **V79** - Drop facets column from denormalized tables (facets handled via joins) [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* API: Database migration **V80** - Java migration to repopulate denormalized tables after facets removal [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * Processes existing runs in chunks (default: 5000 runs per chunk)
  * Includes progress tracking for large datasets
  * Handles failures gracefully with detailed logging
  * Can be run manually: `java -jar marquez-api.jar db-migrate --version v80 --chunkSize 10000 ./marquez.yml`
* API: Database migration **V81** - Drop existing denormalized tables to prepare for partitioned version [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* API: Database migration **V82** - Create new partitioned denormalized tables with all necessary columns [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * Initial monthly partitions created for 2024 and 2025
  * Comprehensive indexes for common UI query patterns
* API: Database migration **V83** - Create partition management functions [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * `create_monthly_partition()`: Dynamically creates monthly partitions
  * `drop_old_partitions()`: Removes partitions older than retention period
* API: Database migration **V84** - Remove DAO-specific columns (cleanup for lineage-only usage) [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* API: Database migration **V85** - Fix partition management function to handle existing partitions gracefully [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* API: Event-driven population of denormalized tables when OpenLineage events are received [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * No manual intervention required for new installations
  * Tables populate automatically as new lineage events are processed
  * Supports both parent and child run lineage tracking
* API: Updated `DenormalizedLineageService` to work with partitioned tables [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
  * Integrated partition management
  * Event-driven population for runs
* API: Updated `LineageDao` to use partitioned denormalized tables [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* API: Updated `SearchResource` to leverage new denormalized tables [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)

### Fixed

* API: Fixed denormalized lineage queries to work correctly with partitioned tables [`fd84f5cb`](https://github.com/MarquezProject/marquez/commit/fd84f5cb)
* API: Fixed partition management function to handle existing partitions gracefully (prevents duplicate partition creation errors) [`1ea68405`](https://github.com/MarquezProject/marquez/commit/1ea68405)
* Build: Fixed build configuration issues [`bc03580d`](https://github.com/MarquezProject/marquez/commit/bc03580d)




