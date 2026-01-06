# Load Test Script for Marquez on AKS
# Generates metadata and runs k6 load test against AKS deployment

param(
    [int]$TotalRecords = 4000000,
    [int]$VirtualUsers = 50,
    [string]$Duration = "2h",
    [string]$ApiUrl = "<changeme>"
)

Write-Host "╔════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║  Marquez AKS Load Test - 4M Records                        ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════╝" -ForegroundColor Cyan

# ============================================================================
# Step 1: Generate Test Metadata
# ============================================================================
Write-Host "`n[1/3] Generating test metadata..." -ForegroundColor Yellow

# Each run generates ~20 OpenLineage events (START, RUNNING, COMPLETE, etc.)
# For 4M records, we need 4M / 20 = 200,000 runs
$runs = [math]::Ceiling($TotalRecords / 20)
$bytesPerEvent = 16384  # ~16KB per event

Write-Host "  Generating metadata for $runs runs (${TotalRecords} total events)..." -ForegroundColor Gray
Write-Host "  Event size: $bytesPerEvent bytes (~16KB)" -ForegroundColor Gray

$jarPath = ".\api\build\libs\marquez-api-0.52.33.jar"
$metadataPath = ".\load-test-metadata.json"

java -jar $jarPath metadata --runs $runs --bytes-per-event $bytesPerEvent --output $metadataPath

if ($LASTEXITCODE -ne 0) {
    Write-Host "  ❌ Failed to generate metadata" -ForegroundColor Red
    exit 1
}

$fileSize = (Get-Item $metadataPath).Length / 1MB
$fileSizeMB = [math]::Round($fileSize, 2)
Write-Host "  ✅ Metadata generated: $metadataPath (${fileSizeMB} MB)" -ForegroundColor Green

# ============================================================================
# Step 2: Create k6 Load Test Script
# ============================================================================
Write-Host "`n[2/3] Creating k6 load test script..." -ForegroundColor Yellow

$k6Script = @"
import { SharedArray } from 'k6/data';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';

// Custom metrics
export const errorRate = new Rate('errors');
export const successRate = new Rate('success');
export const requestDuration = new Trend('request_duration');
export const totalRequests = new Counter('total_requests');
export const totalEvents = new Counter('total_events');

// Load metadata (shared across all VUs)
const metadata = new SharedArray('metadata', function () {
  return JSON.parse(open('./load-test-metadata.json'));
});

// Options for load test
export const options = {
  stages: [
    { duration: '2m', target: 10 },   // Ramp up to 10 users
    { duration: '5m', target: 25 },   // Ramp up to 25 users
    { duration: '10m', target: 50 },  // Ramp up to 50 users
    { duration: '1h', target: 50 },   // Stay at 50 users for 1 hour
    { duration: '5m', target: 0 },    // Ramp down to 0 users
  ],
  thresholds: {
    'http_req_duration': ['p(95)<5000', 'p(99)<10000'], // 95% < 5s, 99% < 10s
    'http_req_failed': ['rate<0.05'],                    // Error rate < 5%
    'errors': ['rate<0.05'],                             // Custom error rate < 5%
  },
};

export default function () {
  const url = '$ApiUrl/api/v1/lineage';
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
    timeout: '30s',
  };

  // Get OpenLineage event for this VU
  const eventIndex = (__VU - 1) % metadata.length;
  const ol_event = metadata[eventIndex];

  // Send POST request
  const response = http.post(url, JSON.stringify(ol_event), params);
  
  // Track metrics
  totalRequests.add(1);
  requestDuration.add(response.timings.duration);

  // Check response
  const success = check(response, {
    'status is 201': (r) => r.status == 201,
    'response time < 5s': (r) => r.timings.duration < 5000,
  });

  if (success) {
    successRate.add(1);
    totalEvents.add(1);
  } else {
    errorRate.add(1);
    console.error(`Failed request: status=`+response.status+`, duration=`+response.timings.duration+`ms`);
  }

  // Small sleep between requests (adjust based on desired throughput)
  sleep(0.1);  // 10 requests/second per VU = 500 req/s with 50 VUs
}

export function handleSummary(data) {
  return {
    'load-test-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

function textSummary(data, options) {
  const indent = options.indent || '';
  const colors = options.enableColors;
  
  let summary = '\n' + indent + '=== Load Test Summary ===\n\n';
  
  summary += indent + `Total Requests: `+data.metrics.total_requests.values.count+`\n`;
  summary += indent + `Total Events Sent: `+data.metrics.total_events.values.count+`\n`;
  summary += indent + `Success Rate: `+(data.metrics.success.values.rate * 100).toFixed(2)+`%\n`;
  summary += indent + `Error Rate: `+(data.metrics.errors.values.rate * 100).toFixed(2)+`%\n`;
  summary += indent + `Avg Request Duration: `+data.metrics.request_duration.values.avg.toFixed(2)+`ms\n`;
  summary += indent + `P95 Request Duration: `+data.metrics.request_duration.values['p(95)'].toFixed(2)+`ms\n`;
  summary += indent + `P99 Request Duration: `+data.metrics.request_duration.values['p(99)'].toFixed(2)+`ms\n`;
  
  return summary;
}
"@

$k6Script | Out-File -FilePath "load-test.js" -Encoding utf8
Write-Host "  ✅ k6 script created: load-test.js" -ForegroundColor Green

# ============================================================================
# Step 3: Run k6 Load Test
# ============================================================================
Write-Host "`n[3/3] Running k6 load test against AKS..." -ForegroundColor Yellow
Write-Host "  Target: $ApiUrl" -ForegroundColor Gray
Write-Host "  Virtual Users: $VirtualUsers" -ForegroundColor Gray
Write-Host "  Duration: $Duration" -ForegroundColor Gray
Write-Host "  Expected throughput: ~500 requests/second" -ForegroundColor Gray
Write-Host "`n  Press Ctrl+C to stop the test early`n" -ForegroundColor Yellow

# Check if k6 is installed
$k6Version = & k6 version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "  ❌ k6 is not installed!" -ForegroundColor Red
    Write-Host "`n  Install k6 with:" -ForegroundColor Yellow
    Write-Host "    winget install k6" -ForegroundColor Gray
    Write-Host "  Or download from: https://k6.io/docs/getting-started/installation/" -ForegroundColor Gray
    exit 1
}

Write-Host "  k6 version: $k6Version" -ForegroundColor Gray

# Run k6
k6 run load-test.js

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ Load test completed successfully!" -ForegroundColor Green
    Write-Host "  Results saved to: load-test-summary.json" -ForegroundColor Gray
} else {
    Write-Host "`n⚠️  Load test completed with errors" -ForegroundColor Yellow
}

Write-Host "`nTo view detailed metrics, check:" -ForegroundColor Cyan
Write-Host "  - load-test-summary.json (detailed metrics)" -ForegroundColor Gray
Write-Host "  - kubectl logs -n marquez -l app.kubernetes.io/name=marquez --tail=100" -ForegroundColor Gray
