import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';

const metadataEvents = new SharedArray('metadata-events', function () {
  return JSON.parse(open('./metadata.json'));
});

const BASE_URL = __ENV.MARQUEZ_URL || 'http://localhost:5000';
const V1_BASE = `${BASE_URL}/api/v1`;
const V2_BASE = `${BASE_URL}/api/v2`;
const V2BETA_BASE = `${BASE_URL}/api/v2beta`;

const SEED_EVENTS = Number(__ENV.SEED_EVENTS || 50);
const USE_EXISTING_DATA = (__ENV.USE_EXISTING_DATA || 'false').toLowerCase() === 'true';
const MIN_EXISTING_EVENTS = Number(__ENV.MIN_EXISTING_EVENTS || 400000);
const FAIL_IF_BELOW_MIN = (__ENV.FAIL_IF_BELOW_MIN || 'true').toLowerCase() === 'true';
const SETUP_TIMEOUT = __ENV.SETUP_TIMEOUT || '15m';
const HTTP_TIMEOUT = __ENV.HTTP_TIMEOUT || '30s';
const LINEAGE_AGGREGATE_TO_PARENT = (__ENV.LINEAGE_AGGREGATE_TO_PARENT || 'false').toLowerCase() === 'true';
const LINEAGE_INCLUDE_FACETS = (__ENV.LINEAGE_INCLUDE_FACETS || '').trim();
const READ_SLEEP_SECONDS = Number(__ENV.READ_SLEEP_SECONDS || 0.2);
const WRITE_SLEEP_SECONDS = Number(__ENV.WRITE_SLEEP_SECONDS || 0.1);

export const endpointHits = new Counter('endpoint_hits');
export const endpointErrors = new Rate('endpoint_errors');
export const endpointLatency = new Trend('endpoint_latency', true);

export const options = {
  setupTimeout: SETUP_TIMEOUT,
  scenarios: {
    reads_v1_v2: {
      executor: 'constant-vus',
      vus: Number(__ENV.READ_VUS || 20),
      duration: __ENV.READ_DURATION || '8m',
      exec: 'readEndpoints',
    },
    lineage_writes: {
      executor: 'constant-vus',
      vus: Number(__ENV.WRITE_VUS || 8),
      duration: __ENV.WRITE_DURATION || '8m',
      exec: 'lineageWrites',
      startTime: __ENV.WRITE_START_TIME || '10s',
    },
    run_lifecycle: {
      executor: 'constant-vus',
      vus: Number(__ENV.RUN_VUS || 4),
      duration: __ENV.RUN_DURATION || '8m',
      exec: 'runLifecycle',
      startTime: __ENV.RUN_START_TIME || '15s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    endpoint_errors: ['rate<0.05'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    endpoint_latency: ['p(95)<2500'],
  },
};

function unique(values) {
  return [...new Set(values.filter((v) => v !== undefined && v !== null && v !== ''))];
}

function pick(arr, fallback) {
  if (!arr || arr.length === 0) {
    return fallback;
  }
  return arr[Math.floor(Math.random() * arr.length)];
}

function request(method, url, body, expectedStatuses, tagName) {
  const params = {
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
    timeout: HTTP_TIMEOUT,
    tags: {
      endpoint: tagName,
      method,
    },
  };

  const response = http.request(method, url, body, params);
  const ok = check(response, {
    [`${tagName} status expected`]: (r) => expectedStatuses.includes(r.status),
  });

  endpointHits.add(1, { endpoint: tagName, method });
  endpointLatency.add(response.timings.duration, { endpoint: tagName, method });
  endpointErrors.add(!ok, { endpoint: tagName, method, status: String(response.status) });

  return response;
}

function buildLineageUrl(runId) {
  const params = [];
  params.push(`nodeId=${encodeURIComponent(`run:${runId}`)}`);
  params.push('depth=10');
  if (LINEAGE_AGGREGATE_TO_PARENT) {
    params.push('aggregateToParentRun=true');
  }
  if (LINEAGE_INCLUDE_FACETS) {
    for (const facet of LINEAGE_INCLUDE_FACETS.split(',')) {
      const trimmed = facet.trim();
      if (trimmed) {
        params.push(`includeFacets=${encodeURIComponent(trimmed)}`);
      }
    }
  }
  return `${V1_BASE}/lineage?${params.join('&')}`;
}

function buildLineageV2Url(runId) {
  const params = [];
  params.push(`nodeId=${encodeURIComponent(`run:${runId}`)}`);
  params.push('depth=10');
  if (LINEAGE_AGGREGATE_TO_PARENT) {
    params.push('aggregateToParentRun=true');
  }
  return `${V2_BASE}/lineage?${params.join('&')}`;
}

function fetchLineageEventCount() {
  const response = request(
    'GET',
    `${V1_BASE}/events/lineage?limit=1&offset=0`,
    null,
    [200],
    'GET /api/v1/events/lineage (count-check)'
  );

  if (response.status !== 200) {
    throw new Error(`Unable to fetch lineage event count, status=${response.status}`);
  }

  let payload = null;
  try {
    payload = JSON.parse(response.body);
  } catch (_err) {
    throw new Error('Unable to parse /api/v1/events/lineage response while checking totalCount');
  }

  if (!payload || payload.totalCount === undefined || payload.totalCount === null) {
    throw new Error('Response from /api/v1/events/lineage did not include totalCount');
  }

  return Number(payload.totalCount);
}

function collectSamples() {
  const namespaces = [];
  const jobs = [];
  const datasets = [];
  const runs = [];
  const sources = ['postgres'];

  for (const ev of metadataEvents) {
    if (ev.job && ev.job.namespace) {
      namespaces.push(ev.job.namespace);
    }
    if (ev.job && ev.job.name) {
      jobs.push(ev.job.name);
    }
    if (ev.run && ev.run.runId) {
      runs.push(ev.run.runId);
    }

    const inputs = ev.inputs || [];
    const outputs = ev.outputs || [];
    for (const ds of inputs.concat(outputs)) {
      if (ds && ds.name) {
        datasets.push(ds.name);
      }
      if (ds && ds.namespace) {
        namespaces.push(ds.namespace);
      }
    }
  }

  return {
    namespaces: unique(namespaces),
    jobs: unique(jobs),
    datasets: unique(datasets),
    runs: unique(runs),
    sources: unique(sources),
  };
}

export function setup() {
  const samples = collectSamples();
  const seedCount = Math.min(SEED_EVENTS, metadataEvents.length);
  const existingEventCount = fetchLineageEventCount();

  if (USE_EXISTING_DATA && existingEventCount < MIN_EXISTING_EVENTS && FAIL_IF_BELOW_MIN) {
    throw new Error(
      `USE_EXISTING_DATA=true but existing lineage events (${existingEventCount}) are below MIN_EXISTING_EVENTS (${MIN_EXISTING_EVENTS})`
    );
  }

  // Seed data to improve read-path coverage before load starts (optional).
  if (!USE_EXISTING_DATA && seedCount > 0) {
    for (let i = 0; i < seedCount; i++) {
      const event = metadataEvents[i];
      request('POST', `${V1_BASE}/lineage`, JSON.stringify(event), [201], 'POST /api/v1/lineage');
    }
  }

  return {
    samples,
    existingEventCount,
  };
}

export function readEndpoints(data) {
  const ns = pick(data.samples.namespaces, 'default');
  const job = pick(data.samples.jobs, 'example-job');
  const dataset = pick(data.samples.datasets, 'example-dataset');
  const runId = pick(data.samples.runs, '00000000-0000-0000-0000-000000000000');
  const source = pick(data.samples.sources, 'postgres');

  group('V1 Read Endpoints', function () {
    request('GET', `${V1_BASE}/namespaces`, null, [200], 'GET /api/v1/namespaces');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}`, null, [200, 404], 'GET /api/v1/namespaces/{namespace}');

    request('GET', `${V1_BASE}/sources`, null, [200], 'GET /api/v1/sources');
    request('GET', `${V1_BASE}/sources/${encodeURIComponent(source)}`, null, [200, 404], 'GET /api/v1/sources/{source}');

    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/datasets`, null, [200], 'GET /api/v1/namespaces/{namespace}/datasets');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/datasets/${encodeURIComponent(dataset)}`, null, [200, 404], 'GET /api/v1/namespaces/{namespace}/datasets/{dataset}');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/datasets/${encodeURIComponent(dataset)}/versions`, null, [200], 'GET /api/v1/namespaces/{namespace}/datasets/{dataset}/versions');

    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs`, null, [200], 'GET /api/v1/namespaces/{namespace}/jobs');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}`, null, [200, 404], 'GET /api/v1/namespaces/{namespace}/jobs/{job}');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}/versions`, null, [200], 'GET /api/v1/namespaces/{namespace}/jobs/{job}/versions');
    request('GET', `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}/runs`, null, [200], 'GET /api/v1/namespaces/{namespace}/jobs/{job}/runs');

    request('GET', `${V1_BASE}/jobs/runs/${runId}`, null, [200, 404], 'GET /api/v1/jobs/runs/{id}');
    request('GET', `${V1_BASE}/jobs/runs/${runId}/facets`, null, [200, 404], 'GET /api/v1/jobs/runs/{id}/facets');

    request('GET', buildLineageUrl(runId), null, [200, 400, 404], 'GET /api/v1/lineage');
    request('GET', `${V1_BASE}/column-lineage?namespace=${encodeURIComponent(ns)}&job=${encodeURIComponent(job)}`, null, [200, 400, 404], 'GET /api/v1/column-lineage');

    request('GET', `${V1_BASE}/tags`, null, [200], 'GET /api/v1/tags');
    request('GET', `${V1_BASE}/search?q=${encodeURIComponent(job)}`, null, [200], 'GET /api/v1/search');
    request('GET', `${V1_BASE}/events/lineage?limit=10`, null, [200, 404], 'GET /api/v1/events/lineage');
  });

  group('V2 Read Endpoints', function () {
    request('GET', `${V2_BASE}/namespaces/${encodeURIComponent(ns)}/datasets`, null, [200, 404], 'GET /api/v2/namespaces/{namespace}/datasets');
    request('GET', `${V2_BASE}/namespaces/${encodeURIComponent(ns)}/datasets/${encodeURIComponent(dataset)}`, null, [200, 404], 'GET /api/v2/namespaces/{namespace}/datasets/{dataset}');
    request('GET', `${V2_BASE}/namespaces/${encodeURIComponent(ns)}/datasets/${encodeURIComponent(dataset)}/versions`, null, [200, 404], 'GET /api/v2/namespaces/{namespace}/datasets/{dataset}/versions');
    request('GET', `${V2_BASE}/namespaces/${encodeURIComponent(ns)}/jobs`, null, [200, 404], 'GET /api/v2/namespaces/{namespace}/jobs');
    request('GET', `${V2_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}`, null, [200, 404], 'GET /api/v2/namespaces/{namespace}/jobs/{job}');
    request('GET', buildLineageV2Url(runId), null, [200, 400, 404], 'GET /api/v2/lineage');
    request('GET', `${V2BETA_BASE}/search?q=${encodeURIComponent(job)}`, null, [200], 'GET /api/v2beta/search');
  });

  sleep(READ_SLEEP_SECONDS);
}

export function lineageWrites() {
  const event = pick(metadataEvents, metadataEvents[0]);
  request('POST', `${V1_BASE}/lineage`, JSON.stringify(event), [201], 'POST /api/v1/lineage');
  sleep(WRITE_SLEEP_SECONDS);
}

export function runLifecycle(data) {
  const ns = pick(data.samples.namespaces, 'default');
  const job = pick(data.samples.jobs, 'example-job');
  const now = new Date().toISOString();

  const createRes = request(
    'POST',
    `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}/runs`,
    JSON.stringify({ args: { trigger: 'k6' } }),
    [201],
    'POST /api/v1/namespaces/{namespace}/jobs/{job}/runs'
  );

  let runId = null;
  try {
    runId = JSON.parse(createRes.body).id;
  } catch (_err) {
    runId = null;
  }

  if (runId) {
    request('POST', `${V1_BASE}/jobs/runs/${runId}/start?at=${encodeURIComponent(now)}`, null, [200], 'POST /api/v1/jobs/runs/{id}/start');
    request('POST', `${V1_BASE}/jobs/runs/${runId}/complete?at=${encodeURIComponent(now)}`, null, [200], 'POST /api/v1/jobs/runs/{id}/complete');

    const failRes = request(
      'POST',
      `${V1_BASE}/namespaces/${encodeURIComponent(ns)}/jobs/${encodeURIComponent(job)}/runs`,
      JSON.stringify({ args: { trigger: 'k6-fail-path' } }),
      [201],
      'POST /api/v1/namespaces/{namespace}/jobs/{job}/runs (fail-flow)'
    );

    let failRunId = null;
    try {
      failRunId = JSON.parse(failRes.body).id;
    } catch (_err2) {
      failRunId = null;
    }

    if (failRunId) {
      request('POST', `${V1_BASE}/jobs/runs/${failRunId}/start?at=${encodeURIComponent(now)}`, null, [200], 'POST /api/v1/jobs/runs/{id}/start (fail-flow)');
      request('POST', `${V1_BASE}/jobs/runs/${failRunId}/fail?at=${encodeURIComponent(now)}`, null, [200], 'POST /api/v1/jobs/runs/{id}/fail');
      request('POST', `${V1_BASE}/jobs/runs/${failRunId}/abort?at=${encodeURIComponent(now)}`, null, [200, 409], 'POST /api/v1/jobs/runs/{id}/abort');
    }
  }

  sleep(WRITE_SLEEP_SECONDS);
}
