#!/bin/bash
#
# Automated Load Testing Script for Marquez
# This script automates the entire load testing process including setup, execution, and reporting
#
# Usage: ./run-load-test.sh [OPTIONS]
#
# OPTIONS:
#   --scenario TYPE         Test scenario: baseline, peak, stress, soak, spike (default: baseline)
#   --vus NUM              Number of virtual users (overrides scenario default)
#   --duration TIME        Test duration, e.g., 30s, 5m, 1h (overrides scenario default)
#   --runs NUM             Number of runs to generate metadata for (default: 100)
#   --event-size BYTES     Size of each event in bytes (default: 16384)
#   --api-port PORT        Marquez API port (default: 5000)
#   --skip-metadata-gen    Skip metadata generation (use existing metadata.json)
#   --output-dir DIR       Directory for test results (default: ./results)
#   --help                 Show this help message

set -e

# Default configuration
SCENARIO="baseline"
VUS=""
DURATION=""
RUNS=100
EVENT_SIZE=16384
API_PORT=5000
SKIP_METADATA=false
OUTPUT_DIR="./results"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
LOAD_TEST_DIR="${SCRIPT_DIR}/../api/load-testing"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --scenario)
      SCENARIO="$2"
      shift 2
      ;;
    --vus)
      VUS="$2"
      shift 2
      ;;
    --duration)
      DURATION="$2"
      shift 2
      ;;
    --runs)
      RUNS="$2"
      shift 2
      ;;
    --event-size)
      EVENT_SIZE="$2"
      shift 2
      ;;
    --api-port)
      API_PORT="$2"
      shift 2
      ;;
    --skip-metadata-gen)
      SKIP_METADATA=true
      shift
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
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
  echo -e "${BLUE}$1${NC}"
  echo -e "${BLUE}========================================${NC}"
}

check_command() {
  if ! command -v "$1" &> /dev/null; then
    log_error "Command '$1' not found. Please install it first."
    return 1
  fi
  return 0
}

# Set scenario parameters
set_scenario_params() {
  case $SCENARIO in
    baseline)
      VUS=${VUS:-10}
      DURATION=${DURATION:-2m}
      log_info "Scenario: Baseline Performance Test"
      ;;
    peak)
      VUS=${VUS:-100}
      DURATION=${DURATION:-5m}
      log_info "Scenario: Peak Load Test"
      ;;
    stress)
      VUS=${VUS:-200}
      DURATION=${DURATION:-10m}
      log_info "Scenario: Stress Test"
      ;;
    soak)
      VUS=${VUS:-50}
      DURATION=${DURATION:-30m}
      log_info "Scenario: Soak Test (Long Duration)"
      ;;
    spike)
      VUS=""
      DURATION=""
      log_info "Scenario: Spike Test (Variable Load)"
      ;;
    *)
      log_error "Unknown scenario: $SCENARIO"
      echo "Available scenarios: baseline, peak, stress, soak, spike"
      exit 1
      ;;
  esac
}

# Verify prerequisites
check_prerequisites() {
  log_section "Checking Prerequisites"
  
  local failed=0
  
  if ! check_command k6; then
    log_error "k6 is required. Install it from https://k6.io/docs/getting-started/installation/"
    ((failed++))
  fi
  
  if ! check_command curl; then
    log_error "curl is required"
    ((failed++))
  fi
  
  if ! check_command java && [[ "$SKIP_METADATA" == false ]]; then
    log_error "Java is required for metadata generation"
    ((failed++))
  fi
  
  if [[ $failed -gt 0 ]]; then
    log_error "Prerequisites check failed"
    exit 1
  fi
  
  log_info "All prerequisites satisfied"
}

# Check if Marquez is running
check_marquez() {
  log_section "Verifying Marquez API"
  
  local api_url="http://localhost:${API_PORT}/api/v1/namespaces"
  log_info "Testing API endpoint: ${api_url}"
  
  if curl -s -f "${api_url}" > /dev/null 2>&1; then
    log_info "Marquez API is responding"
  else
    log_error "Marquez API is not responding at ${api_url}"
    log_error "Please start Marquez with: ./docker/up.sh"
    exit 1
  fi
}

# Generate test metadata
generate_metadata() {
  if [[ "$SKIP_METADATA" == true ]]; then
    log_section "Skipping Metadata Generation"
    
    if [[ ! -f "${LOAD_TEST_DIR}/metadata.json" ]]; then
      log_error "metadata.json not found and --skip-metadata-gen was specified"
      exit 1
    fi
    
    log_info "Using existing metadata.json"
    return 0
  fi
  
  log_section "Generating Test Metadata"
  
  # Find marquez-api jar
  local jar_file=$(find "${SCRIPT_DIR}/../api/build/libs" -name "marquez-api-*.jar" 2>/dev/null | head -n1)
  
  if [[ -z "$jar_file" ]]; then
    log_error "marquez-api jar not found. Please build first: ./gradlew build"
    exit 1
  fi
  
  log_info "Using JAR: $(basename "$jar_file")"
  log_info "Generating ${RUNS} runs with ${EVENT_SIZE} bytes per event"
  
  cd "${LOAD_TEST_DIR}"
  
  if java -jar "$jar_file" metadata --runs "$RUNS" --bytes-per-event "$EVENT_SIZE" --output metadata.json; then
    log_info "Metadata generated successfully"
  else
    log_error "Failed to generate metadata"
    exit 1
  fi
  
  cd - > /dev/null
}

# Run load test
run_load_test() {
  log_section "Running Load Test"
  
  cd "${LOAD_TEST_DIR}"
  
  # Create output directory
  mkdir -p "${OUTPUT_DIR}"
  local timestamp=$(date +"%Y%m%d_%H%M%S")
  local result_file="${OUTPUT_DIR}/load-test-${SCENARIO}-${timestamp}.json"
  
  log_info "Test configuration:"
  log_info "  Scenario: ${SCENARIO}"
  [[ -n "$VUS" ]] && log_info "  Virtual Users: ${VUS}"
  [[ -n "$DURATION" ]] && log_info "  Duration: ${DURATION}"
  log_info "  API Port: ${API_PORT}"
  log_info "  Results: ${result_file}"
  
  echo ""
  log_info "Starting load test..."
  echo ""
  
  # Build k6 command
  local k6_cmd="k6 run"
  
  if [[ "$SCENARIO" == "spike" ]]; then
    # Spike test uses stages instead of vus/duration
    k6_cmd+=" --stage 1m:10,10s:100,1m:100,10s:10,1m:10"
  else
    [[ -n "$VUS" ]] && k6_cmd+=" --vus ${VUS}"
    [[ -n "$DURATION" ]] && k6_cmd+=" --duration ${DURATION}"
  fi
  
  k6_cmd+=" --out json=${result_file}"
  k6_cmd+=" http.js"
  
  # Run k6
  if eval "$k6_cmd"; then
    log_info "Load test completed successfully"
  else
    log_error "Load test failed"
    cd - > /dev/null
    exit 1
  fi
  
  cd - > /dev/null
  
  # Store result file path for reporting
  echo "$result_file" > "${OUTPUT_DIR}/.last-test-result"
}

# Generate test report
generate_report() {
  log_section "Generating Test Report"
  
  local result_file
  if [[ -f "${OUTPUT_DIR}/.last-test-result" ]]; then
    result_file=$(cat "${OUTPUT_DIR}/.last-test-result")
  else
    log_warn "No test result file found"
    return 0
  fi
  
  if [[ ! -f "$result_file" ]]; then
    log_warn "Result file not found: ${result_file}"
    return 0
  fi
  
  local report_file="${result_file%.json}.txt"
  
  log_info "Generating report: ${report_file}"
  
  {
    echo "======================================"
    echo "Marquez Load Test Report"
    echo "======================================"
    echo ""
    echo "Test Configuration:"
    echo "  Scenario: ${SCENARIO}"
    [[ -n "$VUS" ]] && echo "  Virtual Users: ${VUS}"
    [[ -n "$DURATION" ]] && echo "  Duration: ${DURATION}"
    echo "  Runs: ${RUNS}"
    echo "  Event Size: ${EVENT_SIZE} bytes"
    echo "  Timestamp: $(date)"
    echo ""
    echo "Results file: ${result_file}"
    echo ""
    
    # Try to extract summary if jq is available
    if command -v jq &> /dev/null; then
      echo "Test Summary:"
      echo ""
      
      # Count metrics
      local total_requests=$(grep -c '"metric":"http_reqs"' "$result_file" 2>/dev/null || echo "0")
      echo "  Total HTTP requests: ${total_requests}"
      
      # This is simplified - full analysis would require processing the JSON
      echo ""
      echo "For detailed metrics, analyze the JSON file with k6's analysis tools"
      echo "or import into your preferred analytics platform."
    else
      echo "Install 'jq' for detailed result analysis"
    fi
    
    echo ""
    echo "======================================"
  } > "$report_file"
  
  log_info "Report generated: ${report_file}"
  
  # Display report
  cat "$report_file"
}

# Main execution
main() {
  log_section "Marquez Load Testing"
  
  set_scenario_params
  check_prerequisites
  check_marquez
  generate_metadata
  run_load_test
  generate_report
  
  log_section "Load Test Complete"
  log_info "Results saved to: ${OUTPUT_DIR}"
  
  echo ""
  log_info "Next steps:"
  echo "  - Review test results in ${OUTPUT_DIR}"
  echo "  - Compare with baseline metrics"
  echo "  - Monitor system resources and database performance"
  echo "  - Run additional scenarios as needed"
}

# Run main function
main
