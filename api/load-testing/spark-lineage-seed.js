import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.MARQUEZ_URL || 'http://localhost:5000';
const LINEAGE_URL = `${BASE_URL}/api/v1/lineage`;

const TARGET_EVENTS = Number(__ENV.TARGET_EVENTS || 100000);
const SEED_VUS = Number(__ENV.SEED_VUS || 40);
const HTTP_TIMEOUT = __ENV.HTTP_TIMEOUT || '30s';
const NAMESPACE = __ENV.NAMESPACE || 'spark-batch';
const JOB_PREFIX = __ENV.JOB_PREFIX || 'spark-etl';
const DATASET_NAMESPACE = __ENV.DATASET_NAMESPACE || 'warehouse';
const SLEEP_MS = Number(__ENV.SLEEP_MS || 0);

const EVENTS_PER_APP = Number(__ENV.EVENTS_PER_APP || 10);
const CHILD_STAGES = Number(__ENV.CHILD_STAGES || 4);

export const postedEvents = new Counter('seed_posted_events');
export const failedEvents = new Counter('seed_failed_events');
export const postErrorRate = new Rate('seed_error_rate');

export const options = {
  scenarios: {
    seed_lineage: {
      executor: 'shared-iterations',
      vus: SEED_VUS,
      iterations: TARGET_EVENTS,
      maxDuration: __ENV.MAX_DURATION || '2h',
      exec: 'seed',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    seed_error_rate: ['rate<0.05'],
  },
};

function hex(num, len) {
  const value = Math.abs(Number(num)) >>> 0;
  return value.toString(16).padStart(len, '0').slice(-len);
}

function deterministicUuid(a, b, c) {
  const p1 = hex(a * 2654435761 + 17, 8);
  const p2 = hex(b * 2246822519 + 29, 4);
  const p3 = `4${hex(c * 3266489917 + 43, 3)}`;
  const p4 = `a${hex(a ^ b ^ c, 3)}`;
  const p5 = `${hex(a + b, 6)}${hex(c + 97, 6)}`;
  return `${p1}-${p2}-${p3}-${p4}-${p5}`;
}

function timestampFor(index) {
  const base = Date.now();
  const offsetMs = Math.max(0, TARGET_EVENTS - index) * 2;
  return new Date(base - offsetMs).toISOString();
}

function buildEvent(index) {
  const appId = Math.floor(index / EVENTS_PER_APP);
  const slot = index % EVENTS_PER_APP;

  const parentRunId = deterministicUuid(appId, 0, 1);
  const childRunIdx = (slot % CHILD_STAGES) + 1;
  const childRunId = deterministicUuid(appId, childRunIdx, 2);

  const parentJob = `${JOB_PREFIX}-app-${appId}`;
  const childJob = `${JOB_PREFIX}-app-${appId}-stage-${childRunIdx}`;

  const isParentStart = slot === 0;
  const isParentComplete = slot === EVENTS_PER_APP - 1;
  const eventType = slot % 2 === 0 ? 'START' : 'COMPLETE';

  const runId = isParentStart || isParentComplete ? parentRunId : childRunId;
  const jobName = isParentStart || isParentComplete ? parentJob : childJob;

  const inputA = `raw/orders/day=${appId % 365}`;
  const inputB = `raw/customers/shard=${appId % 64}`;
  const output = `curated/spark/output/app=${appId}/stage=${childRunIdx}`;

  const outputSchemaFields = [
    { name: 'event_id', type: 'string', description: 'Event identifier' },
    { name: 'customer_id', type: 'string', description: 'Customer identifier' },
    { name: 'score', type: 'double', description: 'Derived score' },
  ];

  const event = {
    eventType: isParentComplete ? 'COMPLETE' : eventType,
    eventTime: timestampFor(index),
    run: {
      runId,
      facets: {},
    },
    job: {
      namespace: NAMESPACE,
      name: jobName,
      facets: {
        spark_version: {
          _producer: 'https://github.com/OpenLineage/OpenLineage',
          _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/UnknownJobFacet.json',
          version: '3.5.0',
        },
      },
    },
    producer: 'https://github.com/OpenLineage/OpenLineage',
    schemaURL: 'https://openlineage.io/spec/1-0-5/OpenLineage.json',
    inputs: [
      {
        namespace: DATASET_NAMESPACE,
        name: inputA,
        facets: {
          schema: {
            _producer: 'https://github.com/OpenLineage/OpenLineage',
            _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/SchemaDatasetFacet.json',
            fields: [
              { name: 'order_id', type: 'string', description: 'Order id' },
              { name: 'customer_id', type: 'string', description: 'Customer id' },
              { name: 'event_time', type: 'timestamp', description: 'Event time' },
            ],
          },
        },
      },
      {
        namespace: DATASET_NAMESPACE,
        name: inputB,
        facets: {
          schema: {
            _producer: 'https://github.com/OpenLineage/OpenLineage',
            _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/SchemaDatasetFacet.json',
            fields: [
              { name: 'customer_id', type: 'string', description: 'Customer id' },
              { name: 'segment', type: 'string', description: 'Customer segment' },
            ],
          },
        },
      },
    ],
    outputs: [
      {
        namespace: DATASET_NAMESPACE,
        name: output,
        facets: {
          schema: {
            _producer: 'https://github.com/OpenLineage/OpenLineage',
            _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/SchemaDatasetFacet.json',
            fields: outputSchemaFields,
          },
          dataSource: {
            _producer: 'https://github.com/OpenLineage/OpenLineage',
            _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/DatasourceDatasetFacet.json',
            name: 'azure-data-lake',
            uri: `abfss://curated/${output}`,
          },
          columnLineage: {
            _producer: 'https://github.com/OpenLineage/OpenLineage',
            _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/ColumnLineageDatasetFacet.json',
            fields: {
              event_id: {
                inputFields: [
                  { namespace: DATASET_NAMESPACE, name: inputA, field: 'order_id' },
                ],
                transformationType: 'DIRECT',
                transformationDescription: 'event_id derived directly from order_id',
              },
              customer_id: {
                inputFields: [
                  { namespace: DATASET_NAMESPACE, name: inputA, field: 'customer_id' },
                  { namespace: DATASET_NAMESPACE, name: inputB, field: 'customer_id' },
                ],
                transformationType: 'JOIN',
                transformationDescription: 'customer_id from joined orders and customers',
              },
              score: {
                inputFields: [
                  { namespace: DATASET_NAMESPACE, name: inputA, field: 'order_id' },
                  { namespace: DATASET_NAMESPACE, name: inputB, field: 'segment' },
                ],
                transformationType: 'AGGREGATION',
                transformationDescription: 'score computed from order and segment signals',
              },
            },
          },
        },
      },
    ],
  };

  if (!isParentStart && !isParentComplete) {
    event.run.facets.parent = {
      _producer: 'https://github.com/OpenLineage/OpenLineage',
      _schemaURL: 'https://openlineage.io/spec/facets/1-0-0/ParentRunFacet.json',
      run: {
        runId: parentRunId,
      },
      job: {
        namespace: NAMESPACE,
        name: parentJob,
      },
    };
  }

  return event;
}

export function seed() {
  const index = exec.scenario.iterationInTest;
  const event = buildEvent(index);

  const response = http.post(LINEAGE_URL, JSON.stringify(event), {
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    timeout: HTTP_TIMEOUT,
    tags: {
      phase: 'seed',
    },
  });

  const ok = check(response, {
    'seed event accepted': (r) => r.status === 201,
  });

  postedEvents.add(1);
  failedEvents.add(ok ? 0 : 1);
  postErrorRate.add(!ok);

  if (!ok) {
    console.error(`Seed failed at index=${index} status=${response.status} body=${String(response.body).slice(0, 200)}`);
  }

  if (SLEEP_MS > 0) {
    sleep(SLEEP_MS / 1000);
  }
}
