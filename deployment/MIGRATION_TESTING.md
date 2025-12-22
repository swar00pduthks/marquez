# Database Migration Testing Guide

This guide provides comprehensive instructions for testing database migrations in Marquez.

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Migration Testing Strategy](#migration-testing-strategy)
4. [Running Migration Tests](#running-migration-tests)
5. [Test Scenarios](#test-scenarios)
6. [Validation Checks](#validation-checks)
7. [Troubleshooting](#troubleshooting)

## Overview

Database migrations are critical for maintaining data integrity and application functionality across versions. This guide covers:

- **Forward Migration Testing**: Applying new schema changes
- **Data Integrity Validation**: Ensuring existing data remains valid
- **Schema Validation**: Verifying all tables and constraints are correct
- **Migration History**: Tracking applied migrations

### What is Tested

1. **Schema Changes**: New tables, columns, indexes, constraints
2. **Data Migrations**: Data transformations and updates
3. **Backward Compatibility**: Ensuring data from previous versions works
4. **Foreign Key Integrity**: Validating relationships between tables
5. **Migration Idempotency**: Migrations can be safely re-run

## Prerequisites

### Required Software

1. **Docker**: For running test databases
   ```bash
   docker --version
   ```

2. **PostgreSQL Client** (optional): For manual database inspection
   ```bash
   psql --version
   ```

3. **Java 17**: For running Marquez migrations
   ```bash
   java -version
   ```

### Required Access

- Docker daemon must be running
- Sufficient disk space for database containers
- Network access to Docker Hub (for PostgreSQL image)

## Migration Testing Strategy

### Testing Approach

Marquez uses [Flyway](https://flywaydb.org/) for database migrations. Our testing strategy includes:

1. **Clean State Testing**: Start with empty database
2. **Incremental Testing**: Apply migrations step by step
3. **Data Preservation Testing**: Verify existing data integrity
4. **Version Upgrade Testing**: Test upgrade paths from previous versions

### Migration Files Location

All migration files are located in:
```
api/src/main/resources/marquez/db/migration/
```

**Naming Convention:**
- `V{version}__{description}.sql` - Versioned migrations
- `R__{description}.sql` - Repeatable migrations (views, functions)

**Example:**
- `V1__initial_schema.sql`
- `V76__add_denormalized_lineage_tables.sql`
- `R__Jobs_view_and_rewrite_function.sql`

## Running Migration Tests

### Automated Testing Script

The easiest way to run migration tests is using the automated script:

```bash
cd deployment
./test-migrations.sh
```

### Test Options

#### Basic Migration Test

Test migrations from latest release to current build:

```bash
./test-migrations.sh
```

#### Test with Sample Data

Test migrations with generated sample data to verify data integrity:

```bash
./test-migrations.sh --with-data
```

#### Test Specific Version Upgrade

Test upgrade from a specific version:

```bash
./test-migrations.sh --from-version 0.50.0 --to-version current
```

#### Validate Existing Database

Validate migrations on already running database:

```bash
./test-migrations.sh --validate-only
```

#### Cleanup After Test

Automatically cleanup test database after completion:

```bash
./test-migrations.sh --cleanup
```

### Manual Testing

For manual testing or debugging:

1. **Start test database**:
   ```bash
   docker run -d \
     --name marquez-test-db \
     -e POSTGRES_USER=postgres \
     -e POSTGRES_PASSWORD=password \
     -e POSTGRES_DB=marquez_test \
     -p 5432:5432 \
     postgres:14
   ```

2. **Create Marquez configuration**:
   ```yaml
   # marquez-test.yml
   db:
     driverClass: org.postgresql.Driver
     url: jdbc:postgresql://localhost:5432/marquez_test
     user: postgres
     password: password
   ```

3. **Run migrations**:
   ```bash
   java -jar api/build/libs/marquez-api-*.jar db migrate marquez-test.yml
   ```

4. **Verify migrations**:
   ```bash
   docker exec marquez-test-db psql -U postgres -d marquez_test -c \
     "SELECT version, description, installed_on FROM flyway_schema_history ORDER BY installed_rank;"
   ```

## Test Scenarios

### Scenario 1: Fresh Installation

Test a fresh installation with all migrations:

```bash
./test-migrations.sh --cleanup
```

**Expected Results:**
- All migrations apply successfully
- Schema validation passes
- No data integrity issues

### Scenario 2: Version Upgrade

Test upgrading from a previous version:

```bash
./test-migrations.sh --from-version 0.51.1 --to-version current --with-data
```

**Expected Results:**
- Base version migrations apply successfully
- Sample data is created
- Upgrade migrations apply successfully
- Sample data remains valid after upgrade

### Scenario 3: Data Integrity

Test with existing data to ensure migrations preserve data:

```bash
# First, start with base version and create data
./test-migrations.sh --from-version 0.51.1 --with-data

# Then, apply new migrations
./test-migrations.sh --to-version current
```

**Expected Results:**
- All existing data remains accessible
- Foreign key relationships intact
- No null values in required columns

### Scenario 4: Idempotency

Test that migrations can be safely re-run:

```bash
# Apply migrations twice
./test-migrations.sh
./test-migrations.sh --validate-only
```

**Expected Results:**
- Second run detects migrations already applied
- No errors or duplicate data
- Schema state unchanged

## Validation Checks

### Schema Validation

The test script validates:

1. **Critical Tables Exist**:
   - `namespaces`
   - `sources`
   - `datasets`
   - `dataset_versions`
   - `jobs`
   - `job_versions`
   - `runs`
   - `run_args`
   - `run_states`
   - `flyway_schema_history`

2. **Indexes Created**:
   - Primary keys
   - Foreign keys
   - Performance indexes

3. **Constraints Applied**:
   - NOT NULL constraints
   - UNIQUE constraints
   - CHECK constraints

### Data Integrity Validation

The test script checks:

1. **Foreign Key Integrity**:
   ```sql
   -- Check datasets reference valid namespaces
   SELECT COUNT(*) FROM datasets d
   WHERE NOT EXISTS (
     SELECT 1 FROM namespaces n 
     WHERE n.uuid = d.namespace_uuid
   );
   ```

2. **Required Fields**:
   ```sql
   -- Check for null values in required columns
   SELECT COUNT(*) FROM jobs 
   WHERE name IS NULL OR namespace_uuid IS NULL;
   ```

3. **Data Consistency**:
   ```sql
   -- Check run states are valid
   SELECT COUNT(*) FROM runs 
   WHERE current_run_state NOT IN ('NEW', 'RUNNING', 'COMPLETED', 'ABORTED', 'FAILED');
   ```

### Migration History Validation

Check migration history is properly tracked:

```sql
SELECT 
  version,
  description,
  type,
  script,
  installed_rank,
  installed_on,
  execution_time,
  success
FROM flyway_schema_history
ORDER BY installed_rank;
```

## Advanced Testing

### Performance Testing

Test migration performance on large datasets:

```bash
# Generate large dataset first
docker exec marquez-test-db psql -U postgres -d marquez_test <<EOF
-- Insert large amount of test data
INSERT INTO namespaces (guid, name, description)
SELECT 
  gen_random_uuid(),
  'namespace-' || i,
  'Test namespace ' || i
FROM generate_series(1, 10000) i;
EOF

# Then run migration performance test
time ./test-migrations.sh --validate-only
```

### Concurrent Migration Testing

Test migration behavior under concurrent access:

```bash
# Terminal 1: Run migrations
./test-migrations.sh

# Terminal 2: Simultaneously query database
while true; do
  docker exec marquez-test-db psql -U postgres -d marquez_test -c "SELECT COUNT(*) FROM namespaces;"
  sleep 0.1
done
```

### Rollback Testing

While Flyway doesn't support automatic rollback, you can test manual rollback:

```bash
# 1. Take database backup before migration
docker exec marquez-test-db pg_dump -U postgres marquez_test > backup.sql

# 2. Apply migrations
./test-migrations.sh

# 3. Restore from backup (simulates rollback)
docker exec -i marquez-test-db psql -U postgres marquez_test < backup.sql

# 4. Validate restored state
./test-migrations.sh --validate-only
```

## CI/CD Integration

### GitHub Actions Example

```yaml
name: Migration Tests

on: [push, pull_request]

jobs:
  migration-test:
    runs-on: ubuntu-latest
    
    steps:
      - uses: actions/checkout@v2
      
      - name: Set up Java
        uses: actions/setup-java@v2
        with:
          java-version: '17'
      
      - name: Build Marquez
        run: ./gradlew build -x test
      
      - name: Run Migration Tests
        run: ./deployment/test-migrations.sh --with-data --cleanup
```

### Integration with Existing CI

The existing `.circleci/db-migration.sh` script tests migrations in CI:

```bash
# Run the existing CI migration test
./.circleci/db-migration.sh
```

This script:
1. Applies migrations on latest release
2. Takes database backup
3. Applies migrations on current build using backup
4. Validates migration history

## Troubleshooting

### Issue: Migration Checksum Mismatch

**Symptoms:**
```
Migration checksum mismatch for migration version X
```

**Solutions:**
1. Verify migration file hasn't been modified
2. Check for line ending differences (CRLF vs LF)
3. Clear flyway_schema_history and reapply if in development

### Issue: Foreign Key Constraint Violation

**Symptoms:**
```
ERROR: insert or update on table "X" violates foreign key constraint
```

**Solutions:**
1. Check migration order (dependencies)
2. Verify referenced data exists
3. Review migration scripts for data transformations

### Issue: Column Already Exists

**Symptoms:**
```
ERROR: column "X" of relation "Y" already exists
```

**Solutions:**
1. Use `IF NOT EXISTS` in DDL statements
2. Check if migration was partially applied
3. Verify flyway_schema_history state

### Issue: Migration Timeout

**Symptoms:**
- Migration hangs or times out
- Long execution time

**Solutions:**
1. Check for blocking locks
2. Review query performance
3. Add indexes before data transformations
4. Break large migrations into smaller chunks

## Best Practices

1. **Test Locally First**: Always test migrations locally before committing
2. **Use Transactions**: Wrap migrations in transactions when possible
3. **Backward Compatible**: Make migrations backward compatible when feasible
4. **Data Backup**: Always backup production data before migrations
5. **Version Control**: Keep migration files in version control
6. **Semantic Versioning**: Use clear, sequential version numbers
7. **Document Changes**: Add comments explaining complex migrations
8. **Test with Data**: Always test with realistic data volumes
9. **Monitor Performance**: Track migration execution times
10. **Plan Rollback**: Have a rollback strategy before applying migrations

## Additional Resources

- [Flyway Documentation](https://flywaydb.org/documentation/)
- [PostgreSQL Migration Guide](https://www.postgresql.org/docs/current/ddl-alter.html)
- [Marquez Database Schema](../marquez_data_model.md)
- [Existing Migration Scripts](../api/src/main/resources/marquez/db/migration/)

## Support

For migration issues:
- Check existing migrations for examples
- Review Flyway documentation
- Open an issue with migration error details
- Include flyway_schema_history state

---

**Important**: Always test migrations in a non-production environment first. Database migrations can be destructive and should be performed with caution.
