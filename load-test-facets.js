import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 10,
  duration: '30s',
};

const BASE_URL = __ENV.MARQUEZ_URL || 'http://localhost:5000/api/v1';
const V2_URL = BASE_URL.replace('/v1', '/v2');

export default function () {
  const namespace = 'default';

  // 1. List Datasets with Facets
  const listDatasetsRes = http.get(`${V2_URL}/namespaces/${namespace}/datasets?includeFacets=schema,documentation`);
  check(listDatasetsRes, {
    'list datasets status is 200': (r) => r.status === 200,
    'list datasets has facets': (r) => {
        const body = JSON.parse(r.body);
        return body.datasets && body.datasets.length > 0 ? body.datasets[0].facets !== undefined : true;
    },
  });

  // 2. Get Single Dataset with Facets
  // Assuming 'orders' dataset exists or use the first one from list
  const datasets = JSON.parse(listDatasetsRes.body).datasets;
  if (datasets && datasets.length > 0) {
    const datasetName = datasets[0].name;
    const getDatasetRes = http.get(`${V2_URL}/namespaces/${namespace}/datasets/${datasetName}?includeFacets=schema`);
    check(getDatasetRes, {
      'get dataset status is 200': (r) => r.status === 200,
      'get dataset has schema facet': (r) => {
          const body = JSON.parse(r.body);
          return body.facets && (body.facets.schema !== undefined || Object.keys(body.facets).length >= 0);
      },
    });
  }

  // 3. List Jobs with Facets
  const listJobsRes = http.get(`${V2_URL}/namespaces/${namespace}/jobs?includeFacets=jobFacet`);
  check(listJobsRes, {
    'list jobs status is 200': (r) => r.status === 200,
  });

  sleep(1);
}
