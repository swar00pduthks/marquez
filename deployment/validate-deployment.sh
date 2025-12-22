#!/bin/bash
#
# Deployment Validation Script for Marquez
# This script validates that all components of Marquez are properly deployed and functioning
#
# Usage: ./validate-deployment.sh [OPTIONS]
#
# OPTIONS:
#   --api-port PORT         API port (default: 5000)
#   --admin-port PORT       Admin API port (default: 5001)
#   --web-port PORT         Web UI port (default: 3000)
#   --db-port PORT          Database port (default: 5432)
#   --skip-web              Skip Web UI validation
#   --help                  Show this help message

set -e

# Default configuration
API_PORT=${API_PORT:-5000}
ADMIN_PORT=${ADMIN_PORT:-5001}
WEB_PORT=${WEB_PORT:-3000}
DB_PORT=${DB_PORT:-5432}
SKIP_WEB=false

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --api-port)
      API_PORT="$2"
      shift 2
      ;;
    --admin-port)
      ADMIN_PORT="$2"
      shift 2
      ;;
    --web-port)
      WEB_PORT="$2"
      shift 2
      ;;
    --db-port)
      DB_PORT="$2"
      shift 2
      ;;
    --skip-web)
      SKIP_WEB=true
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
  echo -e "${GREEN}✓${NC} $1"
}

log_error() {
  echo -e "${RED}✗${NC} $1"
}

log_warn() {
  echo -e "${YELLOW}⚠${NC} $1"
}

check_command() {
  if ! command -v "$1" &> /dev/null; then
    log_error "Command '$1' not found. Please install it first."
    return 1
  fi
  return 0
}

# Validation functions
validate_docker_services() {
  echo ""
  echo "=== Validating Docker Services ==="
  
  # Check if docker is available
  if ! check_command docker; then
    log_error "Docker is not available"
    return 1
  fi
  
  # Check if containers are running
  local api_running=$(docker ps --filter "name=marquez-api" --format "{{.Names}}" | grep -c "marquez-api" || echo "0")
  local db_running=$(docker ps --filter "name=marquez-db" --format "{{.Names}}" | grep -c "marquez-db" || echo "0")
  
  if [[ $api_running -eq 1 ]]; then
    log_info "Marquez API container is running"
  else
    log_warn "Marquez API container not found (may be running outside Docker)"
  fi
  
  if [[ $db_running -eq 1 ]]; then
    log_info "PostgreSQL database container is running"
  else
    log_warn "PostgreSQL database container not found (may be running outside Docker)"
  fi
}

validate_api_health() {
  echo ""
  echo "=== Validating API Health ==="
  
  # Check if curl is available
  if ! check_command curl; then
    log_error "curl is not available"
    return 1
  fi
  
  # Check admin health endpoint
  local health_url="http://localhost:${ADMIN_PORT}/healthcheck"
  echo "Checking health endpoint: ${health_url}"
  
  local response=$(curl -s -w "\n%{http_code}" "${health_url}" 2>/dev/null || echo "000")
  local http_code=$(echo "$response" | tail -n1)
  local body=$(echo "$response" | head -n-1)
  
  if [[ "$http_code" == "200" ]]; then
    log_info "Health check endpoint is responding (HTTP ${http_code})"
    
    # Check if response contains expected fields
    if echo "$body" | grep -q "healthy"; then
      log_info "Health check response contains expected fields"
      
      # Check PostgreSQL health
      if echo "$body" | grep -q '"postgresql".*"healthy".*true' || echo "$body" | grep -q '"postgresql":{"healthy":true'; then
        log_info "PostgreSQL health check: HEALTHY"
      else
        log_error "PostgreSQL health check: UNHEALTHY"
        return 1
      fi
      
      # Check deadlocks
      if echo "$body" | grep -q '"deadlocks".*"healthy".*true' || echo "$body" | grep -q '"deadlocks":{"healthy":true'; then
        log_info "Deadlocks health check: HEALTHY"
      else
        log_warn "Deadlocks health check: UNHEALTHY (may be acceptable)"
      fi
    else
      log_warn "Health check response format unexpected"
    fi
  else
    log_error "Health check endpoint returned HTTP ${http_code}"
    return 1
  fi
}

validate_api_endpoints() {
  echo ""
  echo "=== Validating API Endpoints ==="
  
  # Test namespaces endpoint
  local namespaces_url="http://localhost:${API_PORT}/api/v1/namespaces"
  echo "Testing endpoint: ${namespaces_url}"
  
  local response=$(curl -s -w "\n%{http_code}" "${namespaces_url}" 2>/dev/null || echo "000")
  local http_code=$(echo "$response" | tail -n1)
  
  if [[ "$http_code" == "200" ]]; then
    log_info "Namespaces endpoint is responding (HTTP ${http_code})"
  else
    log_error "Namespaces endpoint returned HTTP ${http_code}"
    return 1
  fi
  
  # Test metrics endpoint
  local metrics_url="http://localhost:${ADMIN_PORT}/metrics"
  echo "Testing endpoint: ${metrics_url}"
  
  local response=$(curl -s -w "\n%{http_code}" "${metrics_url}" 2>/dev/null || echo "000")
  local http_code=$(echo "$response" | tail -n1)
  
  if [[ "$http_code" == "200" ]]; then
    log_info "Metrics endpoint is responding (HTTP ${http_code})"
  else
    log_warn "Metrics endpoint returned HTTP ${http_code}"
  fi
}

validate_database() {
  echo ""
  echo "=== Validating Database Connectivity ==="
  
  # Check if we can connect to the database via docker
  if docker ps --filter "name=marquez-db" --format "{{.Names}}" | grep -q "marquez-db"; then
    echo "Attempting to connect to database..."
    
    if docker exec marquez-db psql -U marquez -d marquez -c "SELECT 1;" > /dev/null 2>&1; then
      log_info "Database connection successful"
      
      # Check for flyway migrations table
      if docker exec marquez-db psql -U marquez -d marquez -c "SELECT COUNT(*) FROM flyway_schema_history;" > /dev/null 2>&1; then
        local migration_count=$(docker exec marquez-db psql -U marquez -d marquez -t -c "SELECT COUNT(*) FROM flyway_schema_history;" | xargs)
        log_info "Database migrations table exists (${migration_count} migrations applied)"
      else
        log_error "Database migrations table not found"
        return 1
      fi
    else
      log_error "Failed to connect to database"
      return 1
    fi
  else
    log_warn "Database container not found - skipping direct database validation"
  fi
}

validate_web_ui() {
  if [[ "$SKIP_WEB" == true ]]; then
    echo ""
    echo "=== Skipping Web UI Validation ==="
    return 0
  fi
  
  echo ""
  echo "=== Validating Web UI ==="
  
  local web_url="http://localhost:${WEB_PORT}"
  echo "Checking Web UI: ${web_url}"
  
  local response=$(curl -s -w "\n%{http_code}" "${web_url}" 2>/dev/null || echo "000")
  local http_code=$(echo "$response" | tail -n1)
  
  if [[ "$http_code" == "200" ]]; then
    log_info "Web UI is responding (HTTP ${http_code})"
  else
    log_error "Web UI returned HTTP ${http_code}"
    return 1
  fi
}

validate_lineage_endpoint() {
  echo ""
  echo "=== Validating Lineage Endpoint ==="
  
  local lineage_url="http://localhost:${API_PORT}/api/v1/lineage"
  echo "Testing POST endpoint: ${lineage_url}"
  
  # Create a minimal valid OpenLineage event
  local test_event='{
    "eventType": "START",
    "eventTime": "'$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")'",
    "run": {
      "runId": "00000000-0000-0000-0000-000000000001"
    },
    "job": {
      "namespace": "test-validation",
      "name": "validation-job"
    },
    "producer": "validation-script",
    "schemaURL": "https://openlineage.io/spec/2-0-2/OpenLineage.json"
  }'
  
  local response=$(curl -s -w "\n%{http_code}" -X POST \
    -H "Content-Type: application/json" \
    -d "$test_event" \
    "${lineage_url}" 2>/dev/null || echo "000")
  local http_code=$(echo "$response" | tail -n1)
  
  if [[ "$http_code" == "201" || "$http_code" == "200" ]]; then
    log_info "Lineage endpoint is accepting events (HTTP ${http_code})"
  else
    log_error "Lineage endpoint returned HTTP ${http_code}"
    return 1
  fi
}

# Main validation
main() {
  echo "======================================"
  echo "  Marquez Deployment Validation"
  echo "======================================"
  echo ""
  echo "Configuration:"
  echo "  API Port: ${API_PORT}"
  echo "  Admin Port: ${ADMIN_PORT}"
  echo "  Web Port: ${WEB_PORT}"
  echo "  DB Port: ${DB_PORT}"
  
  local failed=0
  
  validate_docker_services || ((failed++))
  validate_api_health || ((failed++))
  validate_api_endpoints || ((failed++))
  validate_database || ((failed++))
  validate_web_ui || ((failed++))
  validate_lineage_endpoint || ((failed++))
  
  echo ""
  echo "======================================"
  if [[ $failed -eq 0 ]]; then
    echo -e "${GREEN}✓ All validation checks passed!${NC}"
    echo "======================================"
    exit 0
  else
    echo -e "${RED}✗ ${failed} validation check(s) failed${NC}"
    echo "======================================"
    exit 1
  fi
}

# Run main function
main
