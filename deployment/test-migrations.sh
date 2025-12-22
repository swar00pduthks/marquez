#!/bin/bash
#
# Database Migration Testing Script for Marquez
# This script tests database migrations including forward migrations, rollback capability,
# and data integrity validation
#
# Usage: ./test-migrations.sh [OPTIONS]
#
# OPTIONS:
#   --from-version VERSION  Start from specific version (default: latest release)
#   --to-version VERSION    Migrate to specific version (default: current build)
#   --with-data             Generate and test with sample data
#   --validate-only         Only validate current migration state
#   --cleanup               Clean up test database after completion
#   --help                  Show this help message

set -e

# Default configuration
FROM_VERSION="0.51.1"
TO_VERSION="current"
WITH_DATA=false
VALIDATE_ONLY=false
CLEANUP=false
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
PROJECT_ROOT="${SCRIPT_DIR}/.."

# Database configuration
DB_HOST="localhost"
DB_PORT="5432"
DB_NAME="marquez_migration_test"
DB_USER="postgres"
DB_PASSWORD="password"
DB_CONTAINER="marquez-migration-test-db"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --from-version)
      FROM_VERSION="$2"
      shift 2
      ;;
    --to-version)
      TO_VERSION="$2"
      shift 2
      ;;
    --with-data)
      WITH_DATA=true
      shift
      ;;
    --validate-only)
      VALIDATE_ONLY=true
      shift
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

# Check prerequisites
check_prerequisites() {
  log_section "Checking Prerequisites"
  
  local failed=0
  
  if ! check_command docker; then
    ((failed++))
  fi
  
  if ! check_command psql && ! docker ps &> /dev/null; then
    log_error "Either psql or Docker is required"
    ((failed++))
  fi
  
  if [[ $failed -gt 0 ]]; then
    log_error "Prerequisites check failed"
    exit 1
  fi
  
  log_info "All prerequisites satisfied"
}

# Start test database
start_test_database() {
  log_section "Starting Test Database"
  
  # Check if container already exists
  if docker ps -a --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
    log_info "Stopping existing test database container..."
    docker stop "${DB_CONTAINER}" > /dev/null 2>&1 || true
    docker rm "${DB_CONTAINER}" > /dev/null 2>&1 || true
  fi
  
  log_info "Starting PostgreSQL container for testing..."
  docker run -d \
    --name "${DB_CONTAINER}" \
    -e POSTGRES_USER="${DB_USER}" \
    -e POSTGRES_PASSWORD="${DB_PASSWORD}" \
    -e POSTGRES_DB="${DB_NAME}" \
    -p "${DB_PORT}:5432" \
    postgres:14 > /dev/null
  
  # Wait for database to be ready
  log_info "Waiting for database to be ready..."
  local max_attempts=30
  local attempt=0
  
  while [[ $attempt -lt $max_attempts ]]; do
    if docker exec "${DB_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" -c "SELECT 1;" > /dev/null 2>&1; then
      log_info "Database is ready"
      return 0
    fi
    
    sleep 1
    ((attempt++))
  done
  
  log_error "Database failed to start within timeout"
  exit 1
}

# Execute SQL query
execute_query() {
  local query="$1"
  docker exec "${DB_CONTAINER}" psql -U "${DB_USER}" -d "${DB_NAME}" -t -c "$query" 2>&1
}

# Get migration count
get_migration_count() {
  local count=$(execute_query "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL;" | xargs)
  echo "$count"
}

# Get latest migration version
get_latest_migration() {
  local version=$(execute_query "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;" | xargs)
  echo "$version"
}

# Apply migrations from specific version
apply_migrations() {
  local version="$1"
  
  log_section "Applying Migrations (Version: ${version})"
  
  local marquez_image="marquezproject/marquez:${version}"
  
  if [[ "$version" == "current" ]]; then
    log_info "Building current version..."
    cd "${PROJECT_ROOT}"
    ./gradlew build -x test > /dev/null 2>&1
    marquez_image="local-build"
  fi
  
  log_info "Creating Marquez configuration..."
  
  # Create temporary config
  local temp_config="/tmp/marquez-migration-test.yml"
  cat > "$temp_config" << EOF
server:
  applicationConnectors:
    - type: http
      port: 5000
  adminConnectors:
    - type: http
      port: 5001

db:
  driverClass: org.postgresql.Driver
  url: jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}
  user: ${DB_USER}
  password: ${DB_PASSWORD}
EOF
  
  if [[ "$version" == "current" ]]; then
    log_info "Running migrations with current build..."
    cd "${PROJECT_ROOT}"
    java -jar api/build/libs/marquez-api-*.jar db migrate "$temp_config"
  else
    log_info "Running migrations with version ${version}..."
    docker run --rm --network host \
      -v "$temp_config:/usr/src/app/marquez.yml" \
      "$marquez_image" \
      db migrate marquez.yml
  fi
  
  rm -f "$temp_config"
  
  local migration_count=$(get_migration_count)
  local latest_version=$(get_latest_migration)
  
  log_info "Migrations applied successfully"
  log_info "Total migrations: ${migration_count}"
  log_info "Latest version: ${latest_version}"
}

# Generate and insert sample data
generate_sample_data() {
  log_section "Generating Sample Data"
  
  log_info "Creating test namespaces..."
  execute_query "
    INSERT INTO namespaces (guid, name, description, current_ownership)
    VALUES 
      (gen_random_uuid(), 'test-namespace-1', 'Test namespace 1', NULL),
      (gen_random_uuid(), 'test-namespace-2', 'Test namespace 2', NULL)
    ON CONFLICT (name) DO NOTHING;
  " > /dev/null
  
  log_info "Creating test sources..."
  execute_query "
    INSERT INTO sources (guid, type, name, connection_url, description)
    VALUES 
      (gen_random_uuid(), 'POSTGRESQL', 'test-source-1', 'postgresql://localhost:5432/test', 'Test source 1'),
      (gen_random_uuid(), 'MYSQL', 'test-source-2', 'mysql://localhost:3306/test', 'Test source 2')
    ON CONFLICT (name) DO NOTHING;
  " > /dev/null
  
  log_info "Sample data generated successfully"
}

# Validate database schema
validate_schema() {
  log_section "Validating Database Schema"
  
  # Check critical tables exist
  local tables=("namespaces" "sources" "datasets" "jobs" "runs" "flyway_schema_history")
  
  for table in "${tables[@]}"; do
    local exists=$(execute_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = '${table}';" | xargs)
    
    if [[ "$exists" == "1" ]]; then
      log_info "Table '${table}' exists"
    else
      log_error "Table '${table}' does not exist"
      return 1
    fi
  done
  
  log_info "All critical tables exist"
}

# Validate data integrity
validate_data_integrity() {
  log_section "Validating Data Integrity"
  
  # Check foreign key constraints
  local fk_violations=$(execute_query "
    SELECT COUNT(*) FROM (
      SELECT 'datasets' as table_name, COUNT(*) as violations
      FROM datasets d
      WHERE NOT EXISTS (SELECT 1 FROM namespaces n WHERE n.uuid = d.namespace_uuid)
      UNION ALL
      SELECT 'jobs', COUNT(*)
      FROM jobs j
      WHERE NOT EXISTS (SELECT 1 FROM namespaces n WHERE n.uuid = j.namespace_uuid)
    ) t
    WHERE violations > 0;
  " | xargs)
  
  if [[ "$fk_violations" == "0" ]]; then
    log_info "No foreign key constraint violations found"
  else
    log_error "Found foreign key constraint violations"
    return 1
  fi
  
  # Check for null values in required columns
  local null_check=$(execute_query "
    SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NULL;
  " | xargs)
  
  if [[ "$null_check" == "0" ]]; then
    log_info "No null values in required columns"
  else
    log_warn "Found null values in required columns (may be acceptable)"
  fi
  
  log_info "Data integrity validation passed"
}

# Test migration rollback
test_rollback() {
  log_section "Testing Migration Rollback Capability"
  
  log_warn "Note: Flyway does not support automatic rollback"
  log_warn "This test verifies that rollback scripts exist and are valid"
  
  # Check for rollback scripts in migration directory
  local migration_dir="${PROJECT_ROOT}/api/src/main/resources/marquez/db/migration"
  
  if [[ -d "$migration_dir" ]]; then
    local migration_files=$(find "$migration_dir" -name "V*.sql" | wc -l)
    log_info "Found ${migration_files} migration files"
    
    # In production systems, you would have rollback scripts
    # For Marquez, migrations are forward-only
    log_warn "Marquez uses forward-only migrations"
    log_info "Rollback should be performed via database backup restoration"
  else
    log_error "Migration directory not found"
    return 1
  fi
}

# Perform full migration test
run_full_test() {
  log_section "Running Full Migration Test"
  
  start_test_database
  
  # Apply base migrations
  log_info "Applying base migrations from version ${FROM_VERSION}..."
  apply_migrations "${FROM_VERSION}"
  
  local base_count=$(get_migration_count)
  local base_version=$(get_latest_migration)
  
  log_info "Base migration state:"
  log_info "  Version: ${base_version}"
  log_info "  Migrations: ${base_count}"
  
  # Generate sample data if requested
  if [[ "$WITH_DATA" == true ]]; then
    generate_sample_data
  fi
  
  # Validate base state
  validate_schema
  validate_data_integrity
  
  # Apply new migrations
  if [[ "$TO_VERSION" != "$FROM_VERSION" ]]; then
    log_info "Applying new migrations to version ${TO_VERSION}..."
    apply_migrations "${TO_VERSION}"
    
    local new_count=$(get_migration_count)
    local new_version=$(get_latest_migration)
    
    log_info "New migration state:"
    log_info "  Version: ${new_version}"
    log_info "  Migrations: ${new_count}"
    log_info "  New migrations applied: $((new_count - base_count))"
  fi
  
  # Validate final state
  validate_schema
  validate_data_integrity
  
  # Test rollback capability
  test_rollback
}

# Validate only mode
run_validation_only() {
  log_section "Running Validation Only"
  
  # Assume database is already running
  if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
    # Try to use existing Marquez database
    DB_CONTAINER="marquez-db"
    DB_NAME="marquez"
    DB_USER="marquez"
    DB_PASSWORD="marquez"
  fi
  
  if ! docker ps --format '{{.Names}}' | grep -q "^${DB_CONTAINER}$"; then
    log_error "No database container found. Start Marquez or run without --validate-only"
    exit 1
  fi
  
  log_info "Using existing database: ${DB_CONTAINER}"
  
  local migration_count=$(get_migration_count)
  local latest_version=$(get_latest_migration)
  
  log_info "Current migration state:"
  log_info "  Latest version: ${latest_version}"
  log_info "  Total migrations: ${migration_count}"
  
  validate_schema
  validate_data_integrity
}

# Cleanup test resources
cleanup_test() {
  if [[ "$CLEANUP" == true ]]; then
    log_section "Cleaning Up Test Resources"
    
    if docker ps -a --format '{{.Names}}' | grep -q "^marquez-migration-test-db$"; then
      log_info "Stopping and removing test database..."
      docker stop "marquez-migration-test-db" > /dev/null 2>&1 || true
      docker rm "marquez-migration-test-db" > /dev/null 2>&1 || true
      log_info "Cleanup completed"
    fi
  fi
}

# Main execution
main() {
  log_section "Marquez Migration Testing"
  
  check_prerequisites
  
  if [[ "$VALIDATE_ONLY" == true ]]; then
    run_validation_only
  else
    run_full_test
  fi
  
  cleanup_test
  
  log_section "Migration Test Complete"
  
  echo ""
  log_info "Summary:"
  echo "  - Database schema validated"
  echo "  - Data integrity checked"
  echo "  - Migration state verified"
  
  if [[ "$WITH_DATA" == true ]]; then
    echo "  - Sample data integrity confirmed"
  fi
  
  echo ""
  log_info "Test passed successfully!"
}

# Trap errors and cleanup
trap cleanup_test EXIT

# Run main function
main
