#!/bin/bash
#
# Copyright 2018-2023 contributors to the Marquez project
# SPDX-License-Identifier: Apache-2.0
#
# Runs the V1 vs V2 GET lineage benchmark (azure-comprehensive-get-benchmark.js)
# against the already-running Marquez API started by api-load-test.sh.
#
# Usage: $ ./get-v2-benchmark.sh

set -e

readonly MARQUEZ_HOST="localhost"
readonly MARQUEZ_API_URL="http://${MARQUEZ_HOST}:8080"
readonly RESULTS_DIR="k6/v2-benchmark-results"

readonly BENCH_VUS="${BENCH_VUS:-20}"
readonly BENCH_DURATION="${BENCH_DURATION:-2m}"
readonly LINEAGE_DEPTH="${LINEAGE_DEPTH:-20}"

log() {
  echo -e "\033[1m>>\033[0m ${1}"
}

# Change working directory to project root
project_root=$(git rev-parse --show-toplevel)
cd "${project_root}"

log "V1 vs V2 GET Lineage Benchmark Configuration:"
log "  Marquez URL:    ${MARQUEZ_API_URL}"
log "  Virtual Users:  ${BENCH_VUS}"
log "  Duration:       ${BENCH_DURATION}"
log "  Lineage Depth:  ${LINEAGE_DEPTH}"

# (1) Verify API is reachable (started by api-load-test.sh)
log "Verifying API is up (${MARQUEZ_API_URL})..."
MAX_RETRIES=12
count=0
until curl --output /dev/null --silent --fail "${MARQUEZ_API_URL}/api/v1/namespaces"; do
  if [ $count -ge $MAX_RETRIES ]; then
    echo "Timeout: Marquez API not reachable at ${MARQUEZ_API_URL}"
    exit 1
  fi
  count=$((count + 1))
  sleep 5
done
log "✓ API is up"

# (2) Run V1 vs V2 benchmark
log "Running V1 vs V2 GET lineage benchmark..."
mkdir -p "${RESULTS_DIR}"
cd api/load-testing

export MARQUEZ_URL="${MARQUEZ_API_URL}"
export LINEAGE_DEPTH

k6 run \
  --vus "${BENCH_VUS}" \
  --duration "${BENCH_DURATION}" \
  --out json="../../${RESULTS_DIR}/results.json" \
  -e MARQUEZ_URL="${MARQUEZ_API_URL}" \
  -e LINEAGE_DEPTH="${LINEAGE_DEPTH}" \
  -e BENCH_VUS="${BENCH_VUS}" \
  -e BENCH_DURATION="${BENCH_DURATION}" \
  azure-comprehensive-get-benchmark.js 2>&1 | tee "../../${RESULTS_DIR}/summary.txt"

EXIT_CODE=${PIPESTATUS[0]}
cd ../..

if [ ${EXIT_CODE} -ne 0 ]; then
  echo "Error: V1 vs V2 benchmark failed"
  exit 1
fi

log "✓ V1 vs V2 benchmark completed. Results in ${RESULTS_DIR}/"
echo "DONE!"
