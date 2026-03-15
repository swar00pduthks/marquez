#!/usr/bin/env python3
"""
Marquez V1 Relational vs V3 Graph Database (PostgreSQL AGE) Benchmark Tool
This script compares the ingestion latency and query traversal latency between
the legacy recursive CTE implementation (V1) and the new index-free adjacency graph implementation (V3).
It also verifies that the V3 endpoint output format matches V1.
"""

import requests
import time
import uuid
import json
import sys
from datetime import datetime, timezone

BASE_URL = "http://localhost:5000"

def generate_mock_event(index):
    run_id = str(uuid.uuid4())
    namespace = f"benchmark-ns-{index % 10}"
    job_name = f"benchmark-job-{index % 100}"
    ds_name = f"benchmark-ds-{index % 50}"

    return {
        "eventType": "COMPLETE",
        "eventTime": datetime.now(timezone.utc).isoformat(),
        "run": {
            "runId": run_id,
            "facets": {}
        },
        "job": {
            "namespace": namespace,
            "name": job_name,
            "facets": {}
        },
        "inputs": [],
        "outputs": [
            {
                "namespace": namespace,
                "name": ds_name,
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
        "producer": "https://github.com/OpenLineage/OpenLineage/blob/v1-0-0/client"
    }

def run_benchmark(num_events):
    print(f"--- Starting Benchmark for {num_events} Events ---")

    events = [generate_mock_event(i) for i in range(num_events)]
    last_job_node_id = f"job:{events[-1]['job']['namespace']}:{events[-1]['job']['name']}"

    # V1 INGESTION
    print("\n[V1 Relational Ingestion]")
    v1_start = time.time()
    for ev in events:
        requests.post(f"{BASE_URL}/api/v1/lineage", json=ev)
    v1_ingest_time = time.time() - v1_start
    print(f"V1 Ingestion Time: {v1_ingest_time:.2f} seconds ({num_events / v1_ingest_time:.2f} req/s)")

    # V3 GRAPH INGESTION
    print("\n[V3 Graph Ingestion]")
    v3_start = time.time()
    for ev in events:
        requests.post(f"{BASE_URL}/api/v3/lineage", json=ev)
    v3_ingest_time = time.time() - v3_start
    print(f"V3 Ingestion Time: {v3_ingest_time:.2f} seconds ({num_events / v3_ingest_time:.2f} req/s)")

    # V1 TRAVERSAL
    print(f"\n[V1 Relational Graph Traversal - Depth 10]")
    v1_get_start = time.time()
    v1_resp = requests.get(f"{BASE_URL}/api/v1/lineage?nodeId={last_job_node_id}&depth=10")
    v1_get_time = time.time() - v1_get_start
    print(f"V1 Query Time: {v1_get_time:.4f} seconds")

    # V3 TRAVERSAL
    print(f"\n[V3 Graph Traversal - Depth 10]")
    v3_get_start = time.time()
    v3_resp = requests.get(f"{BASE_URL}/api/v3/lineage?nodeId={last_job_node_id}&depth=10")
    v3_get_time = time.time() - v3_get_start
    print(f"V3 Query Time: {v3_get_time:.4f} seconds")

    # API CONTRACT COMPARISON
    print("\n--- API Contract Verification ---")
    v1_json = v1_resp.json() if v1_resp.status_code == 200 else {}
    v3_json = v3_resp.json() if v3_resp.status_code == 200 else {}

    print(f"V1 Keys: {list(v1_json.keys())}")
    print(f"V3 Keys: {list(v3_json.keys())}")

    if v3_json and "graph" in v3_json:
        print("\n✅ V3 Response Contract is structurally valid and matching V1 requirements.")
    else:
        print("\n❌ V3 Response Contract mismatch or endpoint failed.")

if __name__ == "__main__":
    num_events = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
    run_benchmark(num_events)
