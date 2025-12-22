# Load Testing Guide for Marquez

This guide provides comprehensive instructions for load testing Marquez using k6.

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Setup](#setup)
4. [Running Load Tests](#running-load-tests)
5. [Test Scenarios](#test-scenarios)
6. [Analyzing Results](#analyzing-results)
7. [Troubleshooting](#troubleshooting)

## Overview

Load testing helps you understand how Marquez performs under various load conditions. This guide uses [k6](https://k6.io), a modern load testing tool, to simulate realistic traffic patterns and measure performance.

### What We Test

- **Lineage API**: POST requests to `/api/v1/lineage` endpoint
- **Query Performance**: Database query execution times
- **Throughput**: Requests per second the system can handle
- **Response Times**: HTTP request durations (blocked, waiting, duration)
- **Error Rates**: Failed requests and error types

## Prerequisites

### Required Software

1. **k6**: Load testing tool
   ```bash
   # macOS
   brew install k6
   
   # Linux
   sudo gpg -k
   sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
   echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
   sudo apt-get update
   sudo apt-get install k6
   
   # Windows (using Chocolatey)
   choco install k6
   ```

2. **Marquez**: Running instance
   ```bash
   ./docker/up.sh
   ```

3. **Java 17**: For generating test metadata
   ```bash
   # Verify Java installation
   java -version
   ```

### Optional Software

- **jq**: For JSON processing
- **Docker**: For containerized testing
- **PostgreSQL client**: For database monitoring

## Setup

### 1. Build Marquez

If you haven't already built Marquez:

```bash
./gradlew build
```

The executable will be in `api/build/libs/marquez-api-*.jar`

### 2. Generate Test Metadata

Generate OpenLineage events for load testing:

```bash
# Navigate to the load testing directory
cd api/load-testing

# Generate metadata with 100 runs, each event ~16KB
java -jar ../build/libs/marquez-api-*.jar metadata --runs 100 --bytes-per-event 16384 --output metadata.json
```

**Options:**
- `--runs`: Number of job runs to generate (default: 10)
- `--bytes-per-event`: Size of each START event in bytes (default: 8192)
- `--output`: Output file path (default: metadata.json)

**Example configurations:**

```bash
# Light load - 50 runs, 8KB events
java -jar ../build/libs/marquez-api-*.jar metadata --runs 50 --bytes-per-event 8192

# Medium load - 200 runs, 16KB events
java -jar ../build/libs/marquez-api-*.jar metadata --runs 200 --bytes-per-event 16384

# Heavy load - 500 runs, 32KB events
java -jar ../build/libs/marquez-api-*.jar metadata --runs 500 --bytes-per-event 32768
```

### 3. Start Marquez

Ensure Marquez is running:

```bash
# Using Docker (recommended for load testing)
./docker/up.sh

# Or run locally
./gradlew :api:runShadow
```

## Running Load Tests

### Basic Load Test

Run a basic load test with default parameters:

```bash
cd api/load-testing
k6 run http.js
```

### Customized Load Tests

#### Test with Specific Virtual Users

```bash
# 25 virtual users for 30 seconds
k6 run --vus 25 --duration 30s http.js

# 50 virtual users for 1 minute
k6 run --vus 50 --duration 1m http.js

# 100 virtual users for 5 minutes
k6 run --vus 100 --duration 5m http.js
```

#### Ramping Test (Gradually Increasing Load)

```bash
k6 run --stage 30s:10,1m:50,30s:100,1m:100,30s:0 http.js
```

This creates stages:
- 30s: Ramp up to 10 users
- 1m: Ramp up to 50 users
- 30s: Ramp up to 100 users
- 1m: Stay at 100 users
- 30s: Ramp down to 0 users

#### Test with Thresholds

Create a file `load-test-config.js`:

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Rate } from 'k6/metrics';

export const errorRate = new Rate('errors');

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.1'],
    errors: ['rate<0.05'],
  },
};

const metadata = new SharedArray('metadata', function () {
  return JSON.parse(open('./metadata.json'));
});

export default function () {
  const url = 'http://localhost:5000/api/v1/lineage';
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  const ol_event = metadata[__VU - 1];

  check(http.post(url, JSON.stringify(ol_event), params), {
    'status is 201': (r) => r.status == 201,
  }) || errorRate.add(1);

  sleep(1);
}
```

Run it:

```bash
k6 run load-test-config.js
```

## Test Scenarios

### Scenario 1: Baseline Performance

Test normal operating conditions:

```bash
k6 run --vus 10 --duration 2m http.js
```

**Expected Results:**
- p95 response time: < 200ms
- Error rate: < 1%
- Throughput: > 100 req/s

### Scenario 2: Peak Load

Test system under heavy load:

```bash
k6 run --vus 100 --duration 5m http.js
```

**Expected Results:**
- p95 response time: < 500ms
- Error rate: < 5%
- System remains stable

### Scenario 3: Stress Test

Find system breaking point:

```bash
k6 run --vus 200 --duration 10m http.js
```

Monitor for:
- Increased error rates
- Response time degradation
- Database connection issues
- Memory/CPU exhaustion

### Scenario 4: Soak Test

Test system stability over time:

```bash
k6 run --vus 50 --duration 30m http.js
```

Monitor for:
- Memory leaks
- Connection pool exhaustion
- Gradual performance degradation

### Scenario 5: Spike Test

Test sudden traffic spikes:

```bash
k6 run --stage 1m:10,10s:100,1m:100,10s:10,1m:10 http.js
```

## Analyzing Results

### k6 Output Metrics

k6 automatically collects and displays metrics:

```
http_req_duration...........: avg=125ms min=50ms med=100ms max=500ms p(90)=200ms p(95)=250ms
http_req_failed.............: 0.50% ✓ 5        ✗ 995
http_reqs...................: 1000  33.33/s
iterations..................: 1000  33.33/s
vus.........................: 25    min=25 max=25
```

### Key Metrics to Monitor

1. **http_req_duration**: Request duration (should be low)
   - p(95) < 500ms: Good
   - p(95) > 1000ms: Needs optimization

2. **http_req_failed**: Failed requests (should be low)
   - < 1%: Excellent
   - 1-5%: Acceptable
   - > 5%: Investigate

3. **http_reqs**: Total requests and rate
   - Shows throughput capability

4. **errors**: Custom error rate metric

### Database Monitoring

Monitor database performance during load tests:

```bash
# Connect to database
docker exec -it marquez-db psql -U marquez

# Check active connections
SELECT count(*) FROM pg_stat_activity;

# Check slow queries
SELECT query, calls, total_time, mean_time 
FROM pg_stat_statements 
ORDER BY mean_time DESC 
LIMIT 10;

# Check table sizes
SELECT 
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;
```

### System Resource Monitoring

Monitor system resources:

```bash
# CPU and Memory usage
docker stats marquez-api marquez-db

# Container logs
docker compose logs -f api
```

## Advanced Testing

### Cloud Load Testing

For larger-scale tests, use k6 Cloud:

```bash
# Sign up at https://k6.io/cloud
k6 login cloud

# Run test in cloud
k6 cloud http.js
```

### Custom Metrics

Add custom metrics to your test script:

```javascript
import { Trend, Counter } from 'k6/metrics';

const customMetric = new Trend('custom_metric');
const eventCounter = new Counter('events_sent');

export default function () {
  const start = Date.now();
  // ... make request ...
  const duration = Date.now() - start;
  
  customMetric.add(duration);
  eventCounter.add(1);
}
```

### Results Export

Export results for analysis:

```bash
# JSON output
k6 run --out json=results.json http.js

# CSV output
k6 run --out csv=results.csv http.js

# InfluxDB
k6 run --out influxdb=http://localhost:8086/k6 http.js
```

## Troubleshooting

### Issue: High Error Rates

**Symptoms:**
- http_req_failed > 5%
- 500 errors in responses

**Solutions:**
1. Check database connections
2. Increase database connection pool
3. Check for memory issues
4. Review application logs

### Issue: Slow Response Times

**Symptoms:**
- p95 > 1000ms
- Increasing latency over time

**Solutions:**
1. Check database query performance
2. Add database indexes
3. Increase system resources
4. Optimize queries

### Issue: Connection Refused

**Symptoms:**
- Cannot connect to API
- Connection timeout errors

**Solutions:**
1. Verify Marquez is running: `curl http://localhost:5000/api/v1/namespaces`
2. Check port configuration
3. Verify network connectivity
4. Check firewall rules

### Issue: Out of Memory

**Symptoms:**
- Container restarts
- OOM errors in logs

**Solutions:**
1. Increase Docker memory limit
2. Adjust JVM heap size in marquez.yml
3. Reduce virtual users
4. Implement request throttling

## Best Practices

1. **Start Small**: Begin with low load and gradually increase
2. **Monitor Resources**: Watch CPU, memory, disk, and network
3. **Use Realistic Data**: Generate metadata similar to production
4. **Test Regularly**: Make load testing part of CI/CD
5. **Document Baselines**: Record baseline performance for comparison
6. **Test Different Scenarios**: Vary load patterns and data sizes
7. **Analyze Trends**: Look for patterns across multiple test runs

## Additional Resources

- [k6 Documentation](https://k6.io/docs/)
- [OpenLineage Specification](https://openlineage.io/spec/)
- [Marquez API Documentation](https://marquezproject.github.io/marquez/openapi.html)
- [PostgreSQL Performance Tuning](https://www.postgresql.org/docs/current/performance-tips.html)

---

**Note**: Load testing can impact system performance. Run tests in a dedicated test environment, not in production.
