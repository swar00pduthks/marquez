import { SharedArray } from 'k6/data';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics — track V1 and V2 separately for comparison
export const errorRate = new Rate('errors');
export const v1ResponseTime = new Trend('v1_response_time', true);
export const v2ResponseTime = new Trend('v2_response_time', true);
export const v1ResponseSize = new Trend('v1_response_size');
export const v2ResponseSize = new Trend('v2_response_size');

const BASE_URL = (__ENV.MARQUEZ_URL || 'http://localhost:8080').replace(/\/$/, '');
const DEPTH = Number(__ENV.LINEAGE_DEPTH || 20);
const VUS = Number(__ENV.BENCH_VUS || 20);
const DURATION = __ENV.BENCH_DURATION || '2m';

// Load run UUIDs from metadata — only runs with output datasets have traversable lineage.
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

export const options = {
  vus: VUS,
  duration: DURATION,
  thresholds: {
    'http_req_duration': ['p(95)<5000'],
    'http_req_failed': ['rate<0.05'],
    'errors': ['rate<0.05'],
  },
};

// Probe the live API to verify it's reachable and optionally supplement run UUIDs.
export function setup() {
  const response = http.get(`${BASE_URL}/api/v1/events/lineage?limit=200`, {
    headers: { Accept: 'application/json' },
    timeout: '30s',
  });

  let apiRunUuids = [];
  if (response.status === 200) {
    try {
      const body = JSON.parse(response.body);
      const events = body.events || [];
      const withOutputs = events
        .filter(e => e.outputs && e.outputs.length > 0)
        .map(e => e.run && e.run.runId)
        .filter(id => id);
      apiRunUuids = [...new Set(withOutputs)];
    } catch (_) {
      // ignore parse errors — fall back to metadata-derived UUIDs
    }
  }

  return { apiRunUuids };
}

export default function (data) {
  // Prefer live API-probed UUIDs; fall back to metadata-derived set.
  const uuids = (data.apiRunUuids && data.apiRunUuids.length > 0)
    ? data.apiRunUuids
    : runUuids;

  const runUuid = uuids[Math.floor(Math.random() * uuids.length)];

  // --- V1 GET /lineage ---
  const v1Url = `${BASE_URL}/api/v1/lineage?nodeId=${encodeURIComponent(`run:${runUuid}`)}&depth=${DEPTH}`;
  const v1Response = http.get(v1Url, {
    headers: { Accept: 'application/json' },
    tags: { name: 'GET_v1_lineage' },
  });

  const v1Ok = check(v1Response, {
    'v1 status is 200': (r) => r.status === 200,
    'v1 has graph field': (r) => {
      try { return r.json('graph') !== undefined; } catch (_) { return false; }
    },
    'v1 response time < 10s': (r) => r.timings.duration < 10000,
  });
  if (!v1Ok) errorRate.add(1);
  v1ResponseTime.add(v1Response.timings.duration);
  v1ResponseSize.add(v1Response.body ? v1Response.body.length : 0);

  sleep(0.2);

  // --- V2 GET /lineage ---
  const v2Url = `${BASE_URL}/api/v2/lineage?nodeId=${encodeURIComponent(`run:${runUuid}`)}&depth=${DEPTH}`;
  const v2Response = http.get(v2Url, {
    headers: { Accept: 'application/json' },
    tags: { name: 'GET_v2_lineage' },
  });

  const v2Ok = check(v2Response, {
    'v2 status is 200': (r) => r.status === 200,
    'v2 has graph field': (r) => {
      try { return r.json('graph') !== undefined; } catch (_) { return false; }
    },
    'v2 response time < 10s': (r) => r.timings.duration < 10000,
  });
  if (!v2Ok) errorRate.add(1);
  v2ResponseTime.add(v2Response.timings.duration);
  v2ResponseSize.add(v2Response.body ? v2Response.body.length : 0);

  sleep(0.3);
}

export function handleSummary(data) {
  const reqs   = data.metrics.http_reqs.values.count;
  const failed = (data.metrics.http_req_failed.values.rate * 100).toFixed(2);

  let out = '\n';
  out += ' ===============================================\n';
  out += '     Marquez V1 vs V2 GET /lineage Benchmark\n';
  out += ' ===============================================\n\n';
  out += ` Total requests:    ${reqs}\n`;
  out += ` Failed:            ${failed}%\n`;

  const v1 = data.metrics.v1_response_time;
  const v2 = data.metrics.v2_response_time;

  if (v1) {
    const avg = v1.values.avg.toFixed(2);
    const p95 = (v1.values['p(95)'] ?? 0).toFixed(2);
    out += `\n V1 /lineage avg:   ${avg}ms  p95: ${p95}ms\n`;
  }
  if (v2) {
    const avg = v2.values.avg.toFixed(2);
    const p95 = (v2.values['p(95)'] ?? 0).toFixed(2);
    out += ` V2 /lineage avg:   ${avg}ms  p95: ${p95}ms\n`;
  }

  if (v1 && v2) {
    const diff = v1.values.avg - v2.values.avg;
    const pct  = Math.abs(diff / v1.values.avg * 100).toFixed(1);
    if (diff > 0) {
      out += `\n V2 is ${pct}% faster than V1 (avg)\n`;
    } else if (diff < 0) {
      out += `\n V1 is ${pct}% faster than V2 (avg)\n`;
    } else {
      out += '\n V1 and V2 have the same average latency\n';
    }
  }

  out += ' ===============================================\n';

  return {
    'v2-benchmark-summary.json': JSON.stringify(data, null, 2),
    stdout: out,
  };
}
