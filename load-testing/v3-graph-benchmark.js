import http from 'k6/http';
import { check, sleep } from 'k6';

// This K6 load test compares the ingestion and recursive retrieval performance
// of the legacy V1 Relational database backend versus the V3 PostgreSQL AGE Graph backend.

export const options = {
  // Simulate 20 concurrent virtual users hitting the API for 1 minute
  vus: 20,
  duration: '1m',
  thresholds: {
    // Assert that 95% of V3 queries complete within 500ms (typical SLA for fast graph traversal)
    'http_req_duration{scenario:v3_graph_get}': ['p(95)<500'],
  },
};

const BASE_URL = __ENV.MARQUEZ_URL || 'http://localhost:5000';
const V1_URL = `${BASE_URL}/api/v1/lineage`;
const V3_URL = `${BASE_URL}/api/v3/lineage`;

function generateMockEvent(vu, iter) {
    const runId = `00000000-0000-4000-8000-${vu.toString().padStart(12, '0')}`;
    const namespace = `bench-ns-${vu}`;
    const jobName = `bench-job-${iter}`;
    const dsName = `bench-ds-${iter}`;

    return JSON.stringify({
        "eventType": "COMPLETE",
        "eventTime": new Date().toISOString(),
        "run": {
            "runId": runId,
            "facets": {}
        },
        "job": {
            "namespace": namespace,
            "name": jobName,
            "facets": {}
        },
        "inputs": [],
        "outputs": [
            {
                "namespace": namespace,
                "name": dsName,
                "facets": {
                    "schema": {
                        "_producer": "https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client",
                        "_schemaURL": "https://openlineage.io/spec/facets/1-0-0/SchemaDatasetFacet.json",
                        "fields": [
                            {"name": "col_a", "type": "VARCHAR"},
                            {"name": "col_b", "type": "INTEGER"}
                        ]
                    }
                }
            }
        ],
        "producer": "https://k6.io/load-test"
    });
}

export default function () {
    const payload = generateMockEvent(__VU, __ITER);
    const params = { headers: { 'Content-Type': 'application/json' } };

    // 1. Ingest Lineage Event into V1 (Relational)
    const v1PostRes = http.post(V1_URL, payload, Object.assign({}, params, { tags: { scenario: 'v1_relational_post' }}));
    check(v1PostRes, { 'v1 post status is 201': (r) => r.status === 201 });

    // 2. Ingest Lineage Event into V3 (Graph)
    const v3PostRes = http.post(V3_URL, payload, Object.assign({}, params, { tags: { scenario: 'v3_graph_post' }}));
    check(v3PostRes, { 'v3 post status is 201': (r) => r.status === 201 });

    // Extract node ID for querying graph traversals
    const eventObj = JSON.parse(payload);
    const nodeId = `job:${eventObj.job.namespace}:${eventObj.job.name}`;

    // 3. Query Lineage Graph from V1 (Relational Recursive CTE)
    const v1GetRes = http.get(`${V1_URL}?nodeId=${nodeId}&depth=5`, { tags: { scenario: 'v1_relational_get' }});
    check(v1GetRes, {
        'v1 get status is 200': (r) => r.status === 200,
        'v1 graph has structure': (r) => JSON.parse(r.body).graph !== undefined
    });

    // 4. Query Lineage Graph from V3 (Postgres AGE Index-Free Adjacency)
    const v3GetRes = http.get(`${V3_URL}?nodeId=${nodeId}&depth=5`, { tags: { scenario: 'v3_graph_get' }});
    check(v3GetRes, {
        'v3 get status is 200': (r) => r.status === 200,
        'v3 graph has structure': (r) => JSON.parse(r.body).graph !== undefined
    });

    sleep(1);
}
