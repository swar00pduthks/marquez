# Marquez Deployment and Testing Tools

This directory contains comprehensive tools for deploying, testing, and validating Marquez installations.

## 📁 Contents

### Documentation

- **[DEPLOYMENT.md](../DEPLOYMENT.md)** - Complete deployment guide for all environments
- **[LOAD_TESTING.md](LOAD_TESTING.md)** - Comprehensive load testing guide with k6
- **[MIGRATION_TESTING.md](MIGRATION_TESTING.md)** - Database migration testing guide

### Scripts

- **[validate-deployment.sh](validate-deployment.sh)** - Validates deployed Marquez instance
- **[run-load-test.sh](run-load-test.sh)** - Automated load testing with k6
- **[test-migrations.sh](test-migrations.sh)** - Database migration testing

## 🚀 Quick Start

### 1. Deploy Marquez

```bash
# Simple deployment with Docker
cd ..
./docker/up.sh

# With sample data
./docker/up.sh --seed

# Build from source
./docker/up.sh --build
```

### 2. Validate Deployment

```bash
cd deployment
./validate-deployment.sh
```

Expected output:
```
======================================
  Marquez Deployment Validation
======================================

=== Validating Docker Services ===
✓ Marquez API container is running
✓ PostgreSQL database container is running

=== Validating API Health ===
✓ Health check endpoint is responding (HTTP 200)
✓ PostgreSQL health check: HEALTHY

=== Validating API Endpoints ===
✓ Namespaces endpoint is responding (HTTP 200)

✓ All validation checks passed!
```

### 3. Run Load Tests

```bash
# Baseline test (10 users, 2 minutes)
./run-load-test.sh --scenario baseline

# Peak load test (100 users, 5 minutes)
./run-load-test.sh --scenario peak

# Custom test
./run-load-test.sh --vus 50 --duration 10m
```

### 4. Test Migrations

```bash
# Test migrations with sample data
./test-migrations.sh --with-data

# Test version upgrade
./test-migrations.sh --from-version 0.51.1 --to-version current

# Validate existing database
./test-migrations.sh --validate-only
```

## 📊 Deployment Validation

### What is Validated

The validation script checks:

- ✅ Docker services are running
- ✅ API health endpoints respond correctly
- ✅ Database connectivity works
- ✅ All API endpoints are accessible
- ✅ Web UI is running (optional)
- ✅ Lineage endpoint accepts events

### Usage Examples

```bash
# Basic validation (all checks)
./validate-deployment.sh

# Custom ports
./validate-deployment.sh --api-port 9000 --web-port 8080

# Skip Web UI check
./validate-deployment.sh --skip-web

# Get help
./validate-deployment.sh --help
```

### Validation Checklist

- [ ] Docker services running
- [ ] Health check returns 200 OK
- [ ] PostgreSQL health check passes
- [ ] API namespaces endpoint accessible
- [ ] Metrics endpoint responding
- [ ] Database connection successful
- [ ] Flyway migrations applied
- [ ] Web UI accessible (if enabled)
- [ ] Lineage endpoint accepts events

## 🔥 Load Testing

### Test Scenarios

| Scenario | Users | Duration | Purpose |
|----------|-------|----------|---------|
| **baseline** | 10 | 2m | Normal operating conditions |
| **peak** | 100 | 5m | Heavy load testing |
| **stress** | 200 | 10m | Find breaking point |
| **soak** | 50 | 30m | Long-term stability |
| **spike** | Variable | ~4m | Sudden traffic spikes |

### Usage Examples

```bash
# Predefined scenarios
./run-load-test.sh --scenario baseline
./run-load-test.sh --scenario peak
./run-load-test.sh --scenario stress
./run-load-test.sh --scenario soak
./run-load-test.sh --scenario spike

# Custom configuration
./run-load-test.sh --vus 75 --duration 15m --runs 200

# With custom event size
./run-load-test.sh --scenario peak --event-size 32768

# Skip metadata generation (use existing)
./run-load-test.sh --scenario baseline --skip-metadata-gen
```

### Understanding Results

Load test results are saved in `./results/` directory:

```
results/
├── load-test-baseline-20231222_103045.json  # Raw k6 results
└── load-test-baseline-20231222_103045.txt   # Summary report
```

Key metrics to monitor:
- **http_req_duration**: Request response time (p95 should be < 500ms)
- **http_req_failed**: Error rate (should be < 1%)
- **http_reqs**: Total requests and throughput
- **errors**: Custom error rate

### Prerequisites for Load Testing

1. **k6 installed**:
   ```bash
   # macOS
   brew install k6
   
   # Linux
   # See LOAD_TESTING.md for instructions
   ```

2. **Marquez running**:
   ```bash
   ./docker/up.sh
   ```

3. **Java 17** (for metadata generation):
   ```bash
   java -version
   ```

## 🗄️ Migration Testing

### Test Types

1. **Fresh Installation**: Test all migrations on empty database
2. **Version Upgrade**: Test upgrade from previous version
3. **Data Integrity**: Verify existing data remains valid
4. **Schema Validation**: Check all tables and constraints

### Usage Examples

```bash
# Full migration test with data
./test-migrations.sh --with-data --cleanup

# Test specific version upgrade
./test-migrations.sh --from-version 0.50.0 --to-version current

# Validate current state only
./test-migrations.sh --validate-only

# Test and cleanup automatically
./test-migrations.sh --with-data --cleanup
```

### What is Tested

- ✅ Migrations apply successfully
- ✅ All tables created correctly
- ✅ Foreign key constraints valid
- ✅ Data integrity maintained
- ✅ No null values in required columns
- ✅ Migration history tracked properly

### Prerequisites for Migration Testing

1. **Docker** (for test database)
2. **Java 17** (for running migrations)
3. **PostgreSQL client** (optional, for manual inspection)

## 🔧 Troubleshooting

### Validation Failures

**Issue**: Health check fails
```bash
# Check if services are running
docker ps

# Check logs
docker compose logs api db

# Restart services
./docker/down.sh
./docker/up.sh
```

**Issue**: Database connection fails
```bash
# Check database is accessible
docker exec marquez-db psql -U marquez -c "SELECT 1;"

# Check connection settings
cat docker-compose.yml | grep -A 5 "db:"
```

### Load Test Issues

**Issue**: k6 not found
```bash
# Install k6
brew install k6  # macOS
# See LOAD_TESTING.md for other platforms
```

**Issue**: High error rates
- Increase system resources
- Reduce virtual users
- Check API and database logs
- Verify database connection pool settings

**Issue**: Metadata generation fails
```bash
# Build Marquez first
cd ..
./gradlew build

# Verify JAR exists
ls -la api/build/libs/marquez-api-*.jar
```

### Migration Test Issues

**Issue**: Database container fails to start
```bash
# Check Docker is running
docker ps

# Remove old container
docker rm -f marquez-migration-test-db

# Check port availability
lsof -i :5432
```

**Issue**: Migration checksum mismatch
- Migration files may have been modified
- Check for line ending issues (CRLF vs LF)
- Review flyway_schema_history table

## 📈 Performance Benchmarks

### Expected Performance

**API Response Times** (p95):
- Lineage POST: < 200ms (baseline), < 500ms (peak load)
- GET endpoints: < 100ms
- Health check: < 50ms

**Throughput**:
- Baseline: 100+ req/s
- Peak: 500+ req/s (with adequate resources)

**Database**:
- Connection pool: 20-50 connections
- Query time: < 100ms (p95)

### Resource Requirements

**Minimum** (Development):
- CPU: 2 cores
- Memory: 4GB
- Disk: 10GB

**Recommended** (Production):
- CPU: 4-8 cores
- Memory: 8-16GB
- Disk: 50GB+ (depends on data volume)

**High Load** (Heavy Production):
- CPU: 8+ cores
- Memory: 16-32GB
- Disk: 100GB+ SSD
- Database: Separate server with replication

## 🔄 CI/CD Integration

### GitHub Actions Example

```yaml
name: Deployment Tests

on: [push, pull_request]

jobs:
  validate-deployment:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2
      
      - name: Start Marquez
        run: ./docker/up.sh --build
        
      - name: Validate Deployment
        run: ./deployment/validate-deployment.sh
        
      - name: Run Load Tests
        run: ./deployment/run-load-test.sh --scenario baseline
        
      - name: Test Migrations
        run: ./deployment/test-migrations.sh --with-data --cleanup
```

### Existing CI Integration

Marquez already has CI integration in `.circleci/`:

```bash
# Run existing migration test
./.circleci/db-migration.sh
```

## 📚 Additional Documentation

- [Main README](../README.md) - Project overview
- [Contributing Guide](../CONTRIBUTING.md) - How to contribute
- [API Documentation](https://marquezproject.github.io/marquez/openapi.html)
- [OpenLineage Specification](https://openlineage.io/spec/)

## 🤝 Support

For issues and questions:
- GitHub Issues: https://github.com/swar00pduthks/marquez/issues
- Documentation: https://marquezproject.github.io/marquez/

## 📝 Maintenance

### Regular Tasks

1. **Weekly**: Run validation and baseline load tests
2. **Before Release**: Run full migration test suite
3. **Monthly**: Run stress and soak tests
4. **After Updates**: Validate deployment and run smoke tests

### Monitoring Recommendations

- Set up automated health checks
- Monitor response times and error rates
- Track database performance metrics
- Set up alerts for failures
- Log analysis for trends

## 🔐 Security Notes

- Validation scripts check system health but don't expose sensitive data
- Load tests should run in isolated environments
- Migration tests use temporary databases that are cleaned up
- Never run load tests against production systems
- Review logs for sensitive information before sharing

---

**Note**: This is a custom fork of Marquez. For the official project, visit [MarquezProject/marquez](https://github.com/MarquezProject/marquez).

## Quick Reference

```bash
# Deploy
./docker/up.sh --build --seed

# Validate
./deployment/validate-deployment.sh

# Load Test
./deployment/run-load-test.sh --scenario peak

# Migration Test
./deployment/test-migrations.sh --with-data

# All-in-one validation
./deployment/validate-deployment.sh && \
  ./deployment/run-load-test.sh --scenario baseline && \
  ./deployment/test-migrations.sh --validate-only
```
