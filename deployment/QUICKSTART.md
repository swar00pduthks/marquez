# Quick Start: Deploy and Test Marquez

This guide will help you quickly deploy Marquez and validate the deployment with comprehensive testing.

## ⚡ Fast Track (5 Minutes)

### 1. Deploy Marquez

```bash
./docker/up.sh --build --seed
```

This will:
- Build Marquez from source
- Start all services (API, Database, Web UI)
- Load sample data for testing

### 2. Validate Everything

```bash
cd deployment
./run-all-tests.sh --skip-load-test --skip-migration
```

This runs basic validation in ~1 minute.

### 3. Access Marquez

- **Web UI**: http://localhost:3000
- **API**: http://localhost:5000/api/v1/namespaces
- **Health Check**: http://localhost:5001/healthcheck

---

## 📋 Standard Testing (30 Minutes)

### Deploy and Test Everything

```bash
# From project root
./docker/up.sh --build

# Run all tests
cd deployment
./run-all-tests.sh --load-scenario baseline
```

This includes:
- ✅ Deployment validation
- ✅ Load testing (baseline scenario)
- ✅ Migration testing with data

---

## 🔬 Comprehensive Testing (1+ Hour)

### Full Test Suite

```bash
# Deploy from scratch
./docker/up.sh --build --seed

# Run comprehensive tests
cd deployment

# 1. Validate deployment
./validate-deployment.sh

# 2. Run multiple load test scenarios
./run-load-test.sh --scenario baseline
./run-load-test.sh --scenario peak
./run-load-test.sh --scenario stress

# 3. Test migrations
./test-migrations.sh --with-data --cleanup

# 4. Validate again
./validate-deployment.sh
```

---

## 🎯 Scenario-Based Quick Starts

### Scenario 1: Developer Setup

**Goal**: Set up development environment and verify it works

```bash
# 1. Deploy with sample data
./docker/up.sh --build --seed

# 2. Quick validation
cd deployment
./validate-deployment.sh

# Done! Start developing
```

### Scenario 2: Pre-Production Testing

**Goal**: Validate before deploying to production

```bash
# 1. Deploy production-like setup
./docker/up.sh --build --no-volumes

# 2. Full validation
cd deployment
./run-all-tests.sh --load-scenario peak

# 3. If all pass, proceed with production deployment
```

### Scenario 3: Performance Testing

**Goal**: Understand system performance under load

```bash
# 1. Deploy Marquez
./docker/up.sh --build

# 2. Run escalating load tests
cd deployment
./run-load-test.sh --scenario baseline
./run-load-test.sh --scenario peak
./run-load-test.sh --scenario stress
./run-load-test.sh --scenario soak

# 3. Review results
ls -la results/
```

### Scenario 4: Migration Validation

**Goal**: Test database migrations before upgrade

```bash
# 1. Test version upgrade
cd deployment
./test-migrations.sh --from-version 0.51.1 --to-version current --with-data

# 2. Validate migrated database
./test-migrations.sh --validate-only

# 3. Deploy with new version
cd ..
./docker/up.sh --build
```

### Scenario 5: CI/CD Pipeline

**Goal**: Automated testing in CI/CD

```bash
# Single command for CI
cd deployment
./run-all-tests.sh --deploy --load-scenario baseline --cleanup
```

---

## 📊 Understanding Test Results

### Deployment Validation Results

**Success Output:**
```
======================================
✓ All validation checks passed!
======================================
```

**What was checked:**
- Docker services running
- API health endpoint responding
- Database connectivity
- API endpoints accessible
- Web UI accessible
- Lineage endpoint accepting events

### Load Test Results

**Results Location:**
```
deployment/results/
├── load-test-baseline-YYYYMMDD_HHMMSS.json
└── load-test-baseline-YYYYMMDD_HHMMSS.txt
```

**Key Metrics:**
- `http_req_duration`: Response time (p95 < 500ms is good)
- `http_req_failed`: Error rate (< 1% is good)
- `http_reqs`: Total requests and throughput

### Migration Test Results

**Success Output:**
```
======================================
Migration Test Complete
======================================

Summary:
  - Database schema validated
  - Data integrity checked
  - Migration state verified
```

---

## 🔧 Troubleshooting Quick Start

### Issue: Port Already in Use

```bash
# Use different ports
./docker/up.sh --api-port 9000 --web-port 8080 --db-port 15432

# Update validation to match
cd deployment
./validate-deployment.sh --api-port 9000 --web-port 8080
```

### Issue: Docker Not Running

```bash
# Start Docker
# macOS: Open Docker Desktop
# Linux: sudo systemctl start docker

# Verify
docker ps
```

### Issue: Build Failures

```bash
# Clean and rebuild
./docker/down.sh
docker system prune -f
./gradlew clean build
./docker/up.sh --build
```

### Issue: Tests Failing

```bash
# Check logs
docker compose logs -f

# Check specific service
docker compose logs api
docker compose logs db

# Restart services
./docker/down.sh
./docker/up.sh
```

---

## 📝 Checklist for Production Deployment

Before deploying to production, ensure:

- [ ] All validation tests pass
- [ ] Load tests meet performance requirements
- [ ] Migration tests pass with production-like data
- [ ] Database backups are configured
- [ ] Monitoring and alerting are set up
- [ ] Security configurations are applied
- [ ] Resource limits are appropriate
- [ ] Disaster recovery plan is documented

---

## 🎓 Next Steps

### Learn More

1. **Read Documentation**:
   - [Deployment Guide](../DEPLOYMENT.md)
   - [Load Testing Guide](LOAD_TESTING.md)
   - [Migration Testing Guide](MIGRATION_TESTING.md)

2. **Explore Advanced Features**:
   - Custom load test scenarios
   - Database performance tuning
   - High availability setup
   - Kubernetes deployment

3. **Set Up Monitoring**:
   - Configure health check alerts
   - Set up performance monitoring
   - Enable database query logging
   - Track API metrics

### Get Help

- **Documentation**: Check the `deployment/` directory
- **Issues**: https://github.com/swar00pduthks/marquez/issues
- **Logs**: `docker compose logs -f`

---

## 🚀 Commands Cheat Sheet

```bash
# Deploy
./docker/up.sh --build --seed

# Validate
cd deployment && ./validate-deployment.sh

# Load Test
./run-load-test.sh --scenario baseline

# Migration Test
./test-migrations.sh --with-data

# All Tests
./run-all-tests.sh

# Stop
cd .. && ./docker/down.sh

# View Logs
docker compose logs -f

# Database Access
docker exec -it marquez-db psql -U marquez

# Check Status
docker ps
curl http://localhost:5001/healthcheck
```

---

**Time Estimates:**

| Task | Time |
|------|------|
| Initial deployment | 5-10 min |
| Basic validation | 1-2 min |
| Load testing (baseline) | 5-10 min |
| Migration testing | 5-10 min |
| Full test suite | 30-60 min |

**Pro Tip**: Run tests in parallel terminals to save time!

```bash
# Terminal 1
./run-load-test.sh --scenario baseline

# Terminal 2 (while load test runs)
./test-migrations.sh --with-data
```

---

**Happy Testing! 🎉**
