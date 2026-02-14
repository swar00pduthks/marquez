import { SharedArray } from 'k6/data';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

export const errorRate = new Rate('errors');

const metadata = new SharedArray('metadata', function () {
  return JSON.parse(open('./metadata.json'));
});

// Configure to load all data efficiently using iterations instead of duration
export const options = {
  scenarios: {
    load_all_data: {
      executor: 'per-vu-iterations',
      vus: 25,
      iterations: 1,  // Each VU runs exactly once
      maxDuration: '5m',
    },
  },
};

export default function () {
  const url = 'http://localhost:8080/api/v1/lineage';
  const params = {
    headers: {
      'Content-Type': 'application/json',
    },
  };

  // Calculate which chunk of metadata this VU should handle
  const totalVUs = 25;
  const eventsPerVU = Math.ceil(metadata.length / totalVUs);
  const startIdx = (__VU - 1) * eventsPerVU;
  const endIdx = Math.min(startIdx + eventsPerVU, metadata.length);

  // Load all events in this VU's chunk
  for (let i = startIdx; i < endIdx; i++) {
    const ol_event = metadata[i];

    check(http.post(url, JSON.stringify(ol_event), params), {
      'status is 201': (r) => r.status == 201,
    }) || errorRate.add(1);

    sleep(0.05);  // Small delay between posts
  }
}
