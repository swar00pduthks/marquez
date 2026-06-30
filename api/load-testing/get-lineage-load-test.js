import { SharedArray } from 'k6/data';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
export const errorRate = new Rate('errors');
export const responseTimeWithFacets = new Trend('response_time_with_facets');
export const responseTimeWithoutFacets = new Trend('response_time_without_facets');
export const responseSizeWithFacets = new Trend('response_size_with_facets');
export const responseSizeWithoutFacets = new Trend('response_size_without_facets');

// Load run UUIDs from metadata file.
// Only include runs that have at least one output dataset — these are the only runs with
// traversable lineage. Runs that only have lifecycle events (START/COMPLETE) with no
// dataset I/O are not in lineage_events and return 404 from GET /lineage.
const runUuids = new SharedArray('runUuids', function () {
  const metadata = JSON.parse(open('./metadata.json'));
  const withOutputs = new Set(
    metadata
      .filter(event => event.outputs && event.outputs.length > 0)
      .map(event => event.run?.runId)
      .filter(id => id !== undefined)
  );
  // Fall back to all run UUIDs if none have outputs (e.g. generated test data has no outputs)
  if (withOutputs.size === 0) {
    return [...new Set(metadata.map(e => e.run?.runId).filter(id => id !== undefined))];
  }
  return [...withOutputs];
});

// Configuration options
export const options = {
  scenarios: {
    // Test without facets
    without_facets: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 50 },   // Ramp up to 50 VUs
        { duration: '5m', target: 50 },   // Stay at 50 VUs
        { duration: '2m', target: 100 },  // Ramp up to 100 VUs
        { duration: '5m', target: 100 },  // Stay at 100 VUs
        { duration: '2m', target: 0 },    // Ramp down
      ],
      gracefulRampDown: '30s',
      tags: { test_type: 'without_facets' },
    },
    // Test with facets
    with_facets: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 50 },   // Ramp up to 50 VUs
        { duration: '5m', target: 50 },   // Stay at 50 VUs
        { duration: '2m', target: 100 },  // Ramp up to 100 VUs
        { duration: '5m', target: 100 },  // Stay at 100 VUs
        { duration: '2m', target: 0 },    // Ramp down
      ],
      gracefulRampDown: '30s',
      tags: { test_type: 'with_facets' },
      startTime: '16m', // Start after without_facets completes
    },
  },
  thresholds: {
    'http_req_duration': ['p(95)<5000'], // 95% of requests should be below 5s
    'http_req_failed': ['rate<0.05'],    // Error rate should be below 5%
    'errors': ['rate<0.05'],
  },
};

// Probe live API for run UUIDs with output datasets — more reliable than metadata filtering.
// Returns null if API is unreachable; default function falls back to SharedArray in that case.
export function setup() {
  const baseUrl = __ENV.MARQUEZ_URL || 'http://localhost:8080';
  const response = http.get(`${baseUrl}/api/v1/events/lineage?limit=500`, {
    headers: { Accept: 'application/json' },
    timeout: '30s',
  });
  if (response.status !== 200) {
    return { apiRunUuids: [] };
  }
  try {
    const body = JSON.parse(response.body);
    const events = body.events || [];
    const ids = [...new Set(
      events
        .filter(e => e.outputs && e.outputs.length > 0)
        .map(e => e.run && e.run.runId)
        .filter(id => id)
    )];
    return { apiRunUuids: ids };
  } catch (_) {
    return { apiRunUuids: [] };
  }
}

export default function (data) {
  // Prefer live API-probed UUIDs (populated by setup()); fall back to metadata-derived set.
  const uuids = (data && data.apiRunUuids && data.apiRunUuids.length > 0)
    ? data.apiRunUuids
    : runUuids;

  // Select a random run UUID
  const runUuid = uuids[Math.floor(Math.random() * uuids.length)];

  const baseUrl = __ENV.MARQUEZ_URL || 'http://localhost:8080';
  const depth = __ENV.LINEAGE_DEPTH || 20;

  // Determine which test scenario we're in based ONLY on SCENARIO env var
  const testType = __ENV.SCENARIO || 'without_facets';

  if (testType === 'with_facets') {
    // Test WITH facets
    const facets = ['spark', 'processing_engine', 'spark_version', 'spark.logical_plan'];
    const facetsParam = facets.map(f => `includeFacets=${f}`).join('&');
    const url = `${baseUrl}/api/v1/lineage?nodeId=run:${runUuid}&depth=${depth}&${facetsParam}`;

    const params = {
      headers: {
        'Accept': 'application/json',
      },
      tags: { name: 'GET_lineage_with_facets' },
    };

    const response = http.get(url, params);

    const success = check(response, {
      'status is 200': (r) => r.status === 200,
      'has graph data': (r) => r.json('graph') !== undefined,
      'response time < 10s': (r) => r.timings.duration < 10000,
    });

    // Record every request so 'errors' is the true failure fraction. Adding only
    // on failure makes the Rate 1/1=100% on the first slow request (a single CI
    // outlier), which trips the rate<0.05 threshold spuriously.
    errorRate.add(!success);

    responseTimeWithFacets.add(response.timings.duration);
    responseSizeWithFacets.add(response.body.length);

  } else {
    // Test WITHOUT facets
    const url = `${baseUrl}/api/v1/lineage?nodeId=run:${runUuid}&depth=${depth}`;

    const params = {
      headers: {
        'Accept': 'application/json',
      },
      tags: { name: 'GET_lineage_without_facets' },
    };

    const response = http.get(url, params);

    const success = check(response, {
      'status is 200': (r) => r.status === 200,
      'has graph data': (r) => r.json('graph') !== undefined,
      'response time < 10s': (r) => r.timings.duration < 10000,
    });

    // Record every request so 'errors' is the true failure fraction (see above).
    errorRate.add(!success);

    responseTimeWithoutFacets.add(response.timings.duration);
    responseSizeWithoutFacets.add(response.body.length);
  }

  // Small delay between requests
  sleep(0.5);
}

export function handleSummary(data) {
  return {
    'summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data, { indent: ' ', enableColors: true }),
  };
}

function textSummary(data, options) {
  const indent = options.indent || '';
  const enableColors = options.enableColors !== false;

  let summary = '\n';
  summary += `${indent}===============================================\n`;
  summary += `${indent}    Marquez GET Lineage Load Test Results\n`;
  summary += `${indent}===============================================\n\n`;

  // Overall metrics
  summary += `${indent}Overall Metrics:\n`;
  summary += `${indent}  Requests: ${data.metrics.http_reqs.values.count}\n`;
  summary += `${indent}  Request Rate: ${data.metrics.http_reqs.values.rate.toFixed(2)}/s\n`;
  summary += `${indent}  Failed: ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%\n`;
  summary += `${indent}  Duration (avg): ${data.metrics.http_req_duration.values.avg.toFixed(2)}ms\n`;
  summary += `${indent}  Duration (p95): ${(data.metrics.http_req_duration.values['p(95)'] ?? 0).toFixed(2)}ms\n`;
  summary += `${indent}  Duration (p99): ${(data.metrics.http_req_duration.values['p(99)'] ?? 0).toFixed(2)}ms\n`;
  summary += `${indent}  Duration (max): ${data.metrics.http_req_duration.values.max.toFixed(2)}ms\n\n`;

  // Without facets metrics
  if (data.metrics.response_time_without_facets?.values?.avg !== undefined) {
    summary += `${indent}Without Facets:\n`;
    summary += `${indent}  Response Time (avg): ${data.metrics.response_time_without_facets.values.avg.toFixed(2)}ms\n`;
    summary += `${indent}  Response Time (p95): ${(data.metrics.response_time_without_facets.values['p(95)'] ?? 0).toFixed(2)}ms\n`;
    summary += `${indent}  Response Time (p99): ${(data.metrics.response_time_without_facets.values['p(99)'] ?? 0).toFixed(2)}ms\n`;
    summary += `${indent}  Response Size (avg): ${(data.metrics.response_size_without_facets.values.avg / 1024).toFixed(2)}KB\n\n`;
  }

  // With facets metrics
  if (data.metrics.response_time_with_facets?.values?.avg !== undefined) {
    summary += `${indent}With Facets:\n`;
    summary += `${indent}  Response Time (avg): ${data.metrics.response_time_with_facets.values.avg.toFixed(2)}ms\n`;
    summary += `${indent}  Response Time (p95): ${(data.metrics.response_time_with_facets.values['p(95)'] ?? 0).toFixed(2)}ms\n`;
    summary += `${indent}  Response Time (p99): ${(data.metrics.response_time_with_facets.values['p(99)'] ?? 0).toFixed(2)}ms\n`;
    summary += `${indent}  Response Size (avg): ${(data.metrics.response_size_with_facets.values.avg / 1024).toFixed(2)}KB\n\n`;
  }

  // Performance comparison
  if (data.metrics.response_time_with_facets?.values?.avg !== undefined &&
      data.metrics.response_time_without_facets?.values?.avg !== undefined) {
    const withFacets = data.metrics.response_time_with_facets.values.avg;
    const withoutFacets = data.metrics.response_time_without_facets.values.avg;
    const improvement = ((withoutFacets - withFacets) / withoutFacets * 100).toFixed(2);

    summary += `${indent}Performance Comparison:\n`;
    if (improvement > 0) {
      summary += `${indent}  With facets is ${improvement}% faster than without facets\n`;
    } else {
      summary += `${indent}  Without facets is ${Math.abs(improvement)}% faster than with facets\n`;
    }

    const sizeWithFacets = data.metrics.response_size_with_facets.values.avg;
    const sizeWithoutFacets = data.metrics.response_size_without_facets.values.avg;
    const sizeReduction = ((sizeWithoutFacets - sizeWithFacets) / sizeWithoutFacets * 100).toFixed(2);

    if (sizeReduction > 0) {
      summary += `${indent}  Response size reduced by ${sizeReduction}% with facet filtering\n`;
    }
  }

  summary += `${indent}===============================================\n`;

  return summary;
}
