#!/bin/bash
#
# Master Test Runner for Marquez
# This script runs all deployment and testing validations in sequence
#
# Usage: ./run-all-tests.sh [OPTIONS]
#
# OPTIONS:
#   --deploy              Deploy Marquez before testing (uses docker/up.sh)
#   --skip-deployment     Skip deployment validation
#   --skip-load-test      Skip load testing
#   --skip-migration      Skip migration testing
#   --load-scenario TYPE  Load test scenario: baseline, peak, stress (default: baseline)
#   --cleanup             Cleanup test resources after completion
#   --help                Show this help message

set -e

# Default configuration
DEPLOY=false
SKIP_DEPLOYMENT=false
SKIP_LOAD_TEST=false
SKIP_MIGRATION=false
LOAD_SCENARIO="baseline"
CLEANUP=false
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --deploy)
      DEPLOY=true
      shift
      ;;
    --skip-deployment)
      SKIP_DEPLOYMENT=true
      shift
      ;;
    --skip-load-test)
      SKIP_LOAD_TEST=true
      shift
      ;;
    --skip-migration)
      SKIP_MIGRATION=true
      shift
      ;;
    --load-scenario)
      LOAD_SCENARIO="$2"
      shift 2
      ;;
    --cleanup)
      CLEANUP=true
      shift
      ;;
    --help)
      grep '^#' "$0" | grep -v '#!/bin/bash' | sed 's/^# //'
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Helper functions
log_info() {
  echo -e "${GREEN}[INFO]${NC} $1"
}

log_error() {
  echo -e "${RED}[ERROR]${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}[WARN]${NC} $1"
}

log_section() {
  echo ""
  echo -e "${BLUE}========================================${NC}"
  echo -e "${BOLD}${BLUE}$1${NC}"
  echo -e "${BLUE}========================================${NC}"
}

log_success() {
  echo -e "${GREEN}✓ $1${NC}"
}

log_failure() {
  echo -e "${RED}✗ $1${NC}"
}

# Track test results
declare -A test_results
total_tests=0
passed_tests=0
failed_tests=0

run_test() {
  local test_name="$1"
  local test_command="$2"
  
  ((total_tests++))
  
  log_section "Running: $test_name"
  
  if eval "$test_command"; then
    log_success "$test_name PASSED"
    test_results["$test_name"]="PASSED"
    ((passed_tests++))
    return 0
  else
    log_failure "$test_name FAILED"
    test_results["$test_name"]="FAILED"
    ((failed_tests++))
    return 1
  fi
}

# Deploy Marquez if requested
deploy_marquez() {
  if [[ "$DEPLOY" == true ]]; then
    log_section "Deploying Marquez"
    
    cd "${SCRIPT_DIR}/.."
    
    log_info "Starting Marquez with Docker..."
    if ./docker/up.sh --build > /tmp/marquez-deploy.log 2>&1; then
      log_success "Marquez deployed successfully"
    else
      log_error "Failed to deploy Marquez"
      log_error "Check logs: /tmp/marquez-deploy.log"
      exit 1
    fi
    
    # Wait for services to be ready
    log_info "Waiting for services to be ready (30 seconds)..."
    sleep 30
    
    cd "${SCRIPT_DIR}"
  else
    log_section "Skipping Deployment"
    log_warn "Assuming Marquez is already running"
  fi
}

# Run all tests
main() {
  log_section "Marquez Deployment and Testing Suite"
  
  echo ""
  log_info "Test Configuration:"
  log_info "  Deploy: ${DEPLOY}"
  log_info "  Skip Deployment Test: ${SKIP_DEPLOYMENT}"
  log_info "  Skip Load Test: ${SKIP_LOAD_TEST}"
  log_info "  Skip Migration Test: ${SKIP_MIGRATION}"
  log_info "  Load Test Scenario: ${LOAD_SCENARIO}"
  log_info "  Cleanup: ${CLEANUP}"
  
  # Deploy if requested
  deploy_marquez
  
  # Run deployment validation
  if [[ "$SKIP_DEPLOYMENT" == false ]]; then
    run_test "Deployment Validation" \
      "${SCRIPT_DIR}/validate-deployment.sh" \
      || log_warn "Deployment validation failed, but continuing..."
  fi
  
  # Run load tests
  if [[ "$SKIP_LOAD_TEST" == false ]]; then
    run_test "Load Testing (${LOAD_SCENARIO})" \
      "${SCRIPT_DIR}/run-load-test.sh --scenario ${LOAD_SCENARIO}" \
      || log_warn "Load testing failed, but continuing..."
  fi
  
  # Run migration tests
  if [[ "$SKIP_MIGRATION" == false ]]; then
    local migration_flags="--with-data"
    [[ "$CLEANUP" == true ]] && migration_flags+=" --cleanup"
    
    run_test "Migration Testing" \
      "${SCRIPT_DIR}/test-migrations.sh ${migration_flags}" \
      || log_warn "Migration testing failed, but continuing..."
  fi
  
  # Display results summary
  log_section "Test Results Summary"
  
  echo ""
  for test_name in "${!test_results[@]}"; do
    local result="${test_results[$test_name]}"
    if [[ "$result" == "PASSED" ]]; then
      log_success "$test_name: $result"
    else
      log_failure "$test_name: $result"
    fi
  done
  
  echo ""
  log_info "Total Tests: ${total_tests}"
  log_info "Passed: ${passed_tests}"
  log_info "Failed: ${failed_tests}"
  
  # Final status
  echo ""
  log_section "Final Status"
  
  if [[ $failed_tests -eq 0 ]]; then
    echo ""
    log_success "All tests passed!"
    echo ""
    log_info "Your Marquez deployment is validated and ready for use."
    echo ""
    log_info "Next steps:"
    echo "  - Access Web UI: http://localhost:3000"
    echo "  - Access API: http://localhost:5000"
    echo "  - View metrics: http://localhost:5001/metrics"
    echo "  - Check health: http://localhost:5001/healthcheck"
    echo ""
    exit 0
  else
    echo ""
    log_failure "${failed_tests} test(s) failed"
    echo ""
    log_warn "Please review the failed tests and fix any issues."
    log_warn "Check logs in:"
    echo "  - Docker logs: docker compose logs"
    echo "  - Load test results: ./results/"
    echo "  - Deployment log: /tmp/marquez-deploy.log (if deployed)"
    echo ""
    exit 1
  fi
}

# Run main function
main
