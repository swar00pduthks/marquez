# Marquez GET Lineage Load Test Runner
# This script automates the entire load testing process for 400K records

param(
    [string]$MarquezUrl = "http://40.74.17.81:5000",
    [int]$RecordCount = 400000,
    [int]$LineageDepth = 20,
    [int]$VirtualUsers = 100,
    [string]$TestDuration = "10m",
    [switch]$SkipDataGeneration,
    [switch]$SkipDataLoad,
    [switch]$WithFacetsOnly,
    [switch]$WithoutFacetsOnly
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "Continue"

# Colors for output
function Write-Success { param($Message) Write-Host $Message -ForegroundColor Green }
function Write-Info { param($Message) Write-Host $Message -ForegroundColor Cyan }
function Write-Warning { param($Message) Write-Host $Message -ForegroundColor Yellow }
function Write-Error { param($Message) Write-Host $Message -ForegroundColor Red }

# Change to marquez directory
$marquezDir = "e:\Swaroop\marquez"
$loadTestDir = Join-Path $marquezDir "api\load-testing"

Write-Info "=========================================="
Write-Info "Marquez GET Lineage Load Test Runner"
Write-Info "=========================================="
Write-Info "Target: $MarquezUrl"
Write-Info "Records: $RecordCount"
Write-Info "Depth: $LineageDepth"
Write-Info "VUs: $VirtualUsers"
Write-Info "Duration: $TestDuration"
Write-Info "=========================================="

# Step 1: Check prerequisites
Write-Info "`nStep 1: Checking prerequisites..."

# Check k6
if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    Write-Error "k6 is not installed. Install with: choco install k6"
    Write-Info "Or download from: https://k6.io/docs/get-started/installation/"
    exit 1
}
Write-Success "✓ k6 is installed"

# Check Java
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "Java is not installed. Required for metadata generation."
    exit 1
}
Write-Success "✓ Java is installed"

# Check kubectl
if (-not (Get-Command kubectl -ErrorAction SilentlyContinue)) {
    Write-Warning "kubectl is not installed. Database monitoring will be limited."
} else {
    Write-Success "✓ kubectl is installed"
}

# Verify Marquez API is accessible
Write-Info "Checking Marquez API connectivity..."
try {
    $response = Invoke-RestMethod -Uri "$MarquezUrl/api/v1/namespaces" -TimeoutSec 10
    Write-Success "✓ Marquez API is accessible at $MarquezUrl"
} catch {
    Write-Error "✗ Cannot connect to Marquez API at $MarquezUrl"
    Write-Error "Error: $_"
    exit 1
}

# Step 2: Build marquez-api.jar if needed
if (-not $SkipDataGeneration) {
    $jarFile = Get-ChildItem -Path (Join-Path $marquezDir "api\build\libs") -Filter "marquez-api-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    
    if (-not $jarFile) {
        Write-Info "`nStep 2: Building marquez-api.jar..."
        Push-Location $marquezDir
        try {
            ./gradlew :api:shadowJar
            Write-Success "✓ Built marquez-api.jar"
        } catch {
            Write-Error "✗ Failed to build marquez-api.jar"
            Write-Error "Error: $_"
            Pop-Location
            exit 1
        }
        Pop-Location
        
        $jarFile = Get-ChildItem -Path (Join-Path $marquezDir "api\build\libs") -Filter "marquez-api-*.jar" | Select-Object -First 1
    } else {
        Write-Success "✓ marquez-api.jar already exists: $($jarFile.Name)"
    }
} else {
    Write-Info "`nStep 2: Skipping marquez-api.jar build (--SkipDataGeneration)"
}

# Step 3: Generate metadata
if (-not $SkipDataGeneration) {
    Write-Info "`nStep 3: Generating metadata for $RecordCount records..."
    Write-Warning "This may take 15-30 minutes for 400K records..."
    
    $runs = [math]::Floor($RecordCount / 20)
    $metadataFile = Join-Path $loadTestDir "metadata.json"
    
    Push-Location $marquezDir
    try {
        $jarPath = (Get-ChildItem -Path "api\build\libs" -Filter "marquez-api-*.jar" | Select-Object -First 1).FullName
        
        Write-Info "Generating $runs job runs (approx $RecordCount events)..."
        $startTime = Get-Date
        
        java -jar $jarPath metadata `
            --runs $runs `
            --bytes-per-event 8192 `
            --output $metadataFile
        
        $duration = (Get-Date) - $startTime
        Write-Success "✓ Generated metadata in $($duration.TotalMinutes.ToString('0.00')) minutes"
        
        $fileSize = (Get-Item $metadataFile).Length / 1MB
        Write-Info "Metadata file size: $($fileSize.ToString('0.00')) MB"
        
    } catch {
        Write-Error "✗ Failed to generate metadata"
        Write-Error "Error: $_"
        Pop-Location
        exit 1
    }
    Pop-Location
} else {
    Write-Info "`nStep 3: Skipping metadata generation (--SkipDataGeneration)"
    
    $metadataFile = Join-Path $loadTestDir "metadata.json"
    if (-not (Test-Path $metadataFile)) {
        Write-Error "✗ metadata.json not found at $metadataFile"
        Write-Error "Run without --SkipDataGeneration to generate it"
        exit 1
    }
    Write-Success "✓ Using existing metadata.json"
}

# Step 4: Load data into Marquez
if (-not $SkipDataLoad) {
    Write-Info "`nStep 4: Loading data into Marquez at $MarquezUrl..."
    Write-Warning "This may take 1-2 hours for 400K records..."
    
    Push-Location $loadTestDir
    try {
        # Check current run count
        Write-Info "Checking current run count in database..."
        try {
            $currentRuns = kubectl exec -n marquez deployment/marquez-api -- `
                psql -h marquez-test-5mlj.postgres.database.azure.com `
                -U marquezadmin -d marquez_test -t `
                -c "SELECT COUNT(*) FROM runs;" 2>$null
            
            if ($currentRuns) {
                Write-Info "Current runs in database: $($currentRuns.Trim())"
            }
        } catch {
            Write-Warning "Could not query database (kubectl access required)"
        }
        
        Write-Info "Starting data load with k6..."
        $loadStartTime = Get-Date
        
        # Use POST lineage endpoint to load data
        $env:MARQUEZ_URL = $MarquezUrl
        k6 run --vus 50 --duration 60m http.js
        
        $loadDuration = (Get-Date) - $loadStartTime
        Write-Success "✓ Data load completed in $($loadDuration.TotalMinutes.ToString('0.00')) minutes"
        
        # Check final run count
        try {
            $finalRuns = kubectl exec -n marquez deployment/marquez-api -- `
                psql -h marquez-test-5mlj.postgres.database.azure.com `
                -U marquezadmin -d marquez_test -t `
                -c "SELECT COUNT(*) FROM runs;" 2>$null
            
            if ($finalRuns) {
                Write-Success "Final runs in database: $($finalRuns.Trim())"
            }
        } catch {
            Write-Warning "Could not query final run count"
        }
        
    } catch {
        Write-Error "✗ Failed to load data"
        Write-Error "Error: $_"
        Pop-Location
        exit 1
    }
    Pop-Location
} else {
    Write-Info "`nStep 4: Skipping data load (--SkipDataLoad)"
}

# Step 5: Run GET lineage load tests
Write-Info "`nStep 5: Running GET lineage load tests..."

Push-Location $loadTestDir
try {
    $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
    $resultsFile = "results-$timestamp.json"
    $summaryFile = "summary-$timestamp.txt"
    
    if ($WithFacetsOnly) {
        Write-Info "Running WITH FACETS test only..."
        $scenario = "with_facets"
    } elseif ($WithoutFacetsOnly) {
        Write-Info "Running WITHOUT FACETS test only..."
        $scenario = "without_facets"
    } else {
        Write-Info "Running BOTH scenarios (without facets, then with facets)..."
        $scenario = $null
    }
    
    Write-Info "Configuration:"
    Write-Info "  - Virtual Users: $VirtualUsers"
    Write-Info "  - Duration: $TestDuration"
    Write-Info "  - Lineage Depth: $LineageDepth"
    Write-Info "  - Results: $resultsFile"
    
    $env:MARQUEZ_URL = $MarquezUrl
    $env:LINEAGE_DEPTH = $LineageDepth
    
    if ($scenario) {
        $env:SCENARIO = $scenario
        k6 run --vus $VirtualUsers --duration $TestDuration `
            --out json=$resultsFile `
            get-lineage-load-test.js | Tee-Object -FilePath $summaryFile
    } else {
        # Run comprehensive test with both scenarios
        k6 run --out json=$resultsFile `
            get-lineage-load-test.js | Tee-Object -FilePath $summaryFile
    }
    
    Write-Success "`n✓ Load test completed!"
    Write-Info "Results saved to:"
    Write-Info "  - JSON: $resultsFile"
    Write-Info "  - Summary: $summaryFile"
    
    # Parse and display key metrics
    if (Test-Path "summary.json") {
        Write-Info "`nKey Metrics:"
        $summary = Get-Content "summary.json" | ConvertFrom-Json
        
        if ($summary.metrics.http_reqs) {
            Write-Info "  Total Requests: $($summary.metrics.http_reqs.values.count)"
            Write-Info "  Request Rate: $($summary.metrics.http_reqs.values.rate.ToString('0.00'))/s"
        }
        
        if ($summary.metrics.http_req_duration) {
            Write-Info "  Avg Response Time: $($summary.metrics.http_req_duration.values.avg.ToString('0.00'))ms"
            Write-Info "  P95 Response Time: $($summary.metrics.http_req_duration.values.'p(95)'.ToString('0.00'))ms"
            Write-Info "  P99 Response Time: $($summary.metrics.http_req_duration.values.'p(99)'.ToString('0.00'))ms"
        }
        
        if ($summary.metrics.http_req_failed) {
            $errorRate = $summary.metrics.http_req_failed.values.rate * 100
            Write-Info "  Error Rate: $($errorRate.ToString('0.00'))%"
        }
        
        if ($summary.metrics.response_time_without_facets -and $summary.metrics.response_time_with_facets) {
            Write-Info "`nComparison:"
            $withoutAvg = $summary.metrics.response_time_without_facets.values.avg
            $withAvg = $summary.metrics.response_time_with_facets.values.avg
            $improvement = (($withoutAvg - $withAvg) / $withoutAvg * 100)
            
            if ($improvement -gt 0) {
                Write-Success "  ✓ WITH facets is $($improvement.ToString('0.00'))% faster"
            } else {
                Write-Warning "  ⚠ WITHOUT facets is $([math]::Abs($improvement).ToString('0.00'))% faster"
            }
            
            $sizeWithout = $summary.metrics.response_size_without_facets.values.avg / 1024
            $sizeWith = $summary.metrics.response_size_with_facets.values.avg / 1024
            $sizeReduction = (($sizeWithout - $sizeWith) / $sizeWithout * 100)
            
            Write-Success "  ✓ Response size reduced by $($sizeReduction.ToString('0.00'))% with facets"
            Write-Info "    - Without: $($sizeWithout.ToString('0.00'))KB"
            Write-Info "    - With: $($sizeWith.ToString('0.00'))KB"
        }
    }
    
} catch {
    Write-Error "✗ Load test failed"
    Write-Error "Error: $_"
    Pop-Location
    exit 1
}
Pop-Location

Write-Success "`n=========================================="
Write-Success "Load Test Completed Successfully!"
Write-Success "=========================================="
Write-Info "Check the following files for detailed results:"
Write-Info "  - $loadTestDir\$resultsFile"
Write-Info "  - $loadTestDir\$summaryFile"
Write-Info "  - $loadTestDir\summary.json"
