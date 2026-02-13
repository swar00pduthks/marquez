#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# A script to run GET /lineage endpoint load tests locally
# Similar to api-load-test.sh but tests GET endpoints instead of POST
#
# Usage: $ ./get-lineage-load-test.sh

set -e

# Fully qualified path to marquez.jar (use wildcard to match version-less or versioned JAR)
readonly MARQUEZ_JAR=$(ls api/build/libs/marquez-api*.jar 2>/dev/null | head -n1)
if [ -z "${MARQUEZ_JAR}" ]; then
  echo "Error: JAR file not found in api/build/libs/"
  echo "Listing build directory:"
  ls -la api/build/libs/ || echo "Build directory does not exist"
  exit 1
fi
echo "Found JAR at: ${MARQUEZ_JAR}"
readonly MARQUEZ_HOST="localhost"
readonly MARQUEZ_ADMIN_PORT=8081
readonly MARQUEZ_URL="http://${MARQUEZ_HOST}:8080"
readonly MARQUEZ_DB="marquez-db"
export POSTGRES_PORT=5432

readonly LINEAGE_DEPTH="${LINEAGE_DEPTH:-20}"
readonly VUS="${VUS:-25}"
readonly DURATION="${DURATION:-5m}"
readonly METADATA_FILE="api/load-testing/metadata.json"
readonly RESULTS_DIR="k6/get-lineage-results"

log() {
  echo -e "\033[1m>>\033[0m ${1}"
}

# Change working directory to project root
project_root=$(git rev-parse --show-toplevel)
cd "${project_root}"

log "GET /lineage Load Test Configuration:"
log "  Marquez Version: ${MARQUEZ_VERSION}"
log "  Marquez URL: ${MARQUEZ_URL}"
log "  Lineage Depth: ${LINEAGE_DEPTH}"
log "  Virtual Users: ${VUS}"
log "  Duration: ${DURATION}"

# Create marquez.yml config
cat > marquez.yml <<EOF
server:
  applicationConnectors:
  - type: http
    port: 8080
    httpCompliance: RFC7230_LEGACY
  adminConnectors:
  - type: http
    port: 8081

db:
  driverClass: org.postgresql.Driver
  url: jdbc:postgresql://localhost:5432/marquez
  user: marquez
  password: marquez

migrateOnStartup: true
EOF

# (1) Start db
log "Starting database..."
docker-compose -f docker-compose.db.yml up --detach

# Wait for database
log "Waiting for database..."
sleep 10

# (2) Build if JAR doesn't exist
if [ ! -f "${MARQUEZ_JAR}" ]; then
    log "Building HTTP API server..."
    ./gradlew --no-daemon :api:build -x test
fi

# (3) Start HTTP API server
log "Starting HTTP API server..."
mkdir -p marquez
java -jar "${MARQUEZ_JAR}" server marquez.yml > marquez/http.log 2>&1 &
SERVER_PID=$!

# (4) Wait for HTTP API server
log "Waiting for HTTP API server (${MARQUEZ_URL})..."
MAX_RETRIES=30
count=0
while true; do
    if curl --output /dev/null --silent --head --fail "${MARQUEZ_URL}/api/v1/namespaces"; then
        break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo "Server died unexpectedly!"
        cat marquez/http.log
        exit 1
    fi
    if [ $count -ge $MAX_RETRIES ]; then
        echo "Timeout waiting for server"
        cat marquez/http.log
        exit 1
    fi
    count=$((count+1))
    sleep 5
done
log "✓ HTTP API server is ready!"

# (5) Generate metadata if needed
if [ ! -f "${METADATA_FILE}" ]; then
    log "Generating load test metadata..."
    java -jar "${MARQUEZ_JAR}" metadata \
        --runs 500 \
        --bytes-per-event 8192 \
        --output "${METADATA_FILE}"
    log "✓ Generated metadata"
fi

# (6) Load data into Marquez using POST /lineage
log "Loading test data into Marquez..."
cd api/load-testing
k6 run --vus 25 --duration 2m http.js
cd ../..

log "✓ Test data loaded"

# (7) Create results directory
mkdir -p "${RESULTS_DIR}"

# (8) Run GET lineage load test - WITHOUT facets
log "Running GET /lineage load test WITHOUT facets..."
cd api/load-testing

export MARQUEZ_URL
export LINEAGE_DEPTH

k6 run --vus ${VUS} --duration ${DURATION} \
    --out json=../../${RESULTS_DIR}/results-without-facets.json \
    -e SCENARIO=without_facets \
    get-lineage-load-test.js 2>&1 | tee ../../${RESULTS_DIR}/summary-without-facets.txt

# (9) Run GET lineage load test - WITH facets
log "Running GET /lineage load test WITH facets..."

k6 run --vus ${VUS} --duration ${DURATION} \
    --out json=../../${RESULTS_DIR}/results-with-facets.json \
    -e SCENARIO=with_facets \
    get-lineage-load-test.js 2>&1 | tee ../../${RESULTS_DIR}/summary-with-facets.txt

cd ../..

# Copy summary.json if it exists
if [ -f "api/load-testing/summary.json" ]; then
    cp api/load-testing/summary.json "${RESULTS_DIR}/"
fi

log "✓ Load test completed successfully!"
log "Results saved to: ${RESULTS_DIR}/"

# Parse and display key metrics
if [ -f "${RESULTS_DIR}/summary.json" ] && command -v jq &> /dev/null; then
    log ""
    log "Key Metrics:"
    log "  Total Requests: $(jq -r '.metrics.http_reqs.values.count' ${RESULTS_DIR}/summary.json)"
    log "  Request Rate: $(jq -r '.metrics.http_reqs.values.rate' ${RESULTS_DIR}/summary.json)/s"
    log "  Avg Response Time: $(jq -r '.metrics.http_req_duration.values.avg' ${RESULTS_DIR}/summary.json)ms"
    log "  P95 Response Time: $(jq -r '.metrics.http_req_duration.values."p(95)"' ${RESULTS_DIR}/summary.json)ms"
    log "  Error Rate: $(jq -r '.metrics.http_req_failed.values.rate * 100' ${RESULTS_DIR}/summary.json)%"
    
    # Compare with/without facets
    WITH_FACETS=$(jq -r '.metrics.response_time_with_facets.values.avg // 0' ${RESULTS_DIR}/summary.json)
    WITHOUT_FACETS=$(jq -r '.metrics.response_time_without_facets.values.avg // 0' ${RESULTS_DIR}/summary.json)
    
    if [ "$WITH_FACETS" != "0" ] && [ "$WITHOUT_FACETS" != "0" ]; then
        IMPROVEMENT=$(echo "scale=2; (($WITHOUT_FACETS - $WITH_FACETS) / $WITHOUT_FACETS) * 100" | bc)
        log ""
        log "Performance Comparison:"
        if (( $(echo "$IMPROVEMENT > 0" | bc -l) )); then
            log "  ✓ WITH facets is ${IMPROVEMENT}% faster than WITHOUT facets"
        else
            IMPROVEMENT=$(echo "$IMPROVEMENT * -1" | bc)
            log "  ⚠ WITHOUT facets is ${IMPROVEMENT}% faster than WITH facets"
        fi
    fi
fi

log "=========================================="
log "GET Lineage Load Test Completed!"
log "=========================================="

echo "DONE!"
