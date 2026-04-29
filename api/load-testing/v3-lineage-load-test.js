import { SharedArray } from 'k6/data';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
export const errorRate = new Rate('errors');
export const v3ResponseTime = new Trend('v3_response_time', true);
export const v3ResponseSize = new Trend('v3_response_size');

const BASE_URL = (__ENV.MARQUEZ_URL || 'http://localhost:8080').replace(/\/$/, '');
const DEPTH = Number(__ENV.LINEAGE_DEPTH || 20);

// Load run UUIDs from metadata — only runs with output datasets have traversable lineage.
// Runs with only START/COMPLETE events return 404 from GET /lineage.
const runUuids = new SharedArray('runUuids', function () {
  const metadata = JSON.parse(open('./metadata.json'));
  const withOutputs = new Set(
    metadata
      .filter(event => event.outputs && event.outputs.length > 0)
      .map(event => event.run && event.run.runId)
      .filter(id => id)
  );
  if (withOutputs.size === 0) {
    return [...new Set(metadata.map(e => e.run && e.run.runId).filter(id => id))];
  }
  return [...withOutputs];
});

// Probe live API for run UUIDs with output datasets — more reliable than metadata filtering.
export function setup() {
  const baseUrl = (__ENV.MARQUEZ_URL || 'http://localhost:8080').replace(/\/$/, '');
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

export const options = {
  vus: Number(__ENV.VUS || 25),
  duration: __ENV.DURATION || '5m',
  thresholds: {
    'http_req_duration': ['p(95)<5000'],
    'http_req_failed': ['rate<0.05'],
    'errors': ['rate<0.05'],
  },
};

export default function (data) {
  // Prefer live API-probed UUIDs; fall back to metadata-derived set.
  const uuids = (data && data.apiRunUuids && data.apiRunUuids.length > 0)
    ? data.apiRunUuids
    : runUuids;
  const runUuid = uuids[Math.floor(Math.random() * uuids.length)];
  const nodeId = encodeURIComponent(`run:${runUuid}`);
  const url = `${BASE_URL}/api/v3/lineage?nodeId=${nodeId}&depth=${DEPTH}`;

  const response = http.get(url, {
    headers: { Accept: 'application/json' },
    tags: { name: 'GET_v3_lineage' },
  });

  const success = check(response, {
    'status is 200': (r) => r.status === 200,
    'has graph field': (r) => {
      try { return r.json('graph') !== undefined; } catch (_) { return false; }
    },
    'response time < 10s': (r) => r.timings.duration < 10000,
  });

  // Record EVERY iteration into the Rate metric so the denominator includes
  // successes, not just failures. Previous pattern (`if (!success) errorRate.add(1)`)
  // only incremented the numerator — one failure pinned the rate at 100% no
  // matter how many successes followed, tripping the `rate<0.05` threshold
  // even when 14k+/14k requests succeeded with healthy 10ms latencies.
  errorRate.add(!success);

  v3ResponseTime.add(response.timings.duration);
  v3ResponseSize.add(response.body ? response.body.length : 0);

  sleep(0.5);
}

export function handleSummary(data) {
  const reqs    = data.metrics.http_reqs.values.count;
  const failed  = (data.metrics.http_req_failed.values.rate * 100).toFixed(2);
  const avgMs   = data.metrics.http_req_duration.values.avg.toFixed(2);
  const p95Ms   = (data.metrics.http_req_duration.values['p(95)'] ?? 0).toFixed(2);
  const p99Ms   = (data.metrics.http_req_duration.values['p(99)'] ?? 0).toFixed(2);

  let out = '\n';
  out += ' ===============================================\n';
  out += '     Marquez V3 GET /lineage Load Test\n';
  out += ' ===============================================\n\n';
  out += ` Requests:          ${reqs}\n`;
  out += ` Failed:            ${failed}%\n`;
  out += ` Duration avg:      ${avgMs}ms\n`;
  out += ` Duration p95:      ${p95Ms}ms\n`;
  out += ` Duration p99:      ${p99Ms}ms\n`;

  if (data.metrics.v3_response_time) {
    const v3avg = data.metrics.v3_response_time.values.avg.toFixed(2);
    const v3p95 = (data.metrics.v3_response_time.values['p(95)'] ?? 0).toFixed(2);
    out += `\n V3 lineage avg:    ${v3avg}ms\n`;
    out += ` V3 lineage p95:    ${v3p95}ms\n`;
  }

  out += ' ===============================================\n';

  return {
    'v3-summary.json': JSON.stringify(data, null, 2),
    stdout: out,
  };
}
