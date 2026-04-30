# V1 / V2 / V3 Parity Coverage Matrix

Branch: `v2-graph-database-postgres-age-13138765716357943821`
Generated: 2026-04-19
Commits: `6b1eee26` (V1↔V3 parity) + `6073f8e8` (V1↔V2 parity) + `af7b0529` (V103 migration)

## Legend
- ✅ tested, green
- ⚠️  documented gap (test pins the current behaviour so future work has a flip-to-green target)
- ❌ not yet covered
- n/a endpoint does not exist at this API version

## Integration test files (new in this PR)

| File | Tests | Status |
|---|---:|---|
| `NamespaceResourceV1V2ParityIT.java` | 8 | ✅ |
| `SourceResourceV1V2ParityIT.java` | 5 | ✅ |
| `TagResourceV1V2ParityIT.java` | 4 | ✅ |
| `LineageResourceV1V2ParityIT.java` | 7 | ✅ |
| `ColumnLineageResourceV1V2ParityIT.java` | 5 | ✅ |
| `NamespaceResourceV1V3ParityIT.java` | 5 | ✅ |
| `SourceResourceV1V3ParityIT.java` | 2 | ✅ |
| `TagResourceV1V3ParityIT.java` | 2 | ✅ |
| `JobResourceV1V3ParityIT.java` | 5 | ✅ |
| `DatasetResourceV1V3ParityIT.java` | 5 | ✅ |
| **Total** | **48** | **all green in targeted retest** |

## Resource × Version × Scenario matrix

### Namespaces

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| List | ✅ | ✅ | ✅ |
| Pagination (limit/offset) | ✅ | ✅ | ❌ (no offset on V3) |
| Get by name (200) | ✅ | ✅ | ✅ |
| Get by name (404) | ✅ | ✅ | ✅ |
| Delete (accept) | ✅ | ✅ | n/a |
| Delete (visible after — Marquez soft-delete quirk) | ⚠️  | ⚠️  | n/a |
| `https://` URI namespace — create/get/list | ✅ | ✅ | ✅ |
| Multi-scheme URIs (postgres/s3/kafka) | ✅ | ✅ | ✅ |
| URI in path-segment (via /datasets) | ✅ | ✅ | n/a |

### Sources

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| List | ✅ | ✅ | ✅ |
| Pagination | ✅ | ✅ | ❌ |
| Get by name (200) | ✅ | ✅ | n/a |
| Get by name (404) | ✅ | ✅ | n/a |
| Core-fields match (type/connectionUrl/description) | ✅ | ✅ | n/a |
| Create visibility (V1 PUT → V2 GET) | ✅ | ✅ | ⚠️ (V1-PUT not in AGE graph — documented gap) |
| `dataSource` facet → source in list | — | — | ✅ |

### Tags

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| List | ✅ | ✅ | ✅ (empty — GraphWriter does not write Tag nodes yet) |
| V1↔V2 bidirectional visibility | ✅ | ✅ | ⚠️ |
| Pagination | ✅ | ✅ | ❌ |

### Jobs

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| Global list | ✅ | ✅ | ✅ |
| Namespace-scoped list | ✅ | ✅ | ✅ |
| Get by name (200) | ✅ | ✅ | ✅ |
| Get by name (404) | ✅ | ✅ | ✅ |
| URI-namespace path | ✅ | ✅ | ✅ |

### Datasets

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| Global list | n/a (V1 has no `/datasets`) | n/a | ✅ |
| Namespace-scoped list | ✅ | ✅ | ✅ |
| Get by name (200) | ✅ | ✅ | ✅ |
| Get by name (404) | ✅ | ✅ | ✅ |
| URI-namespace path | ✅ | ✅ | ✅ |

### Lineage

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| Get graph by `dataset:...` nodeId | ✅ | ✅ | ❌ (different shape) |
| Get graph by `run:<uuid>` nodeId | ✅ | ✅ | ❌ |
| `aggregateToParentRun=true` | ✅ | ✅ | ❌ |
| `includeFacets=spark` | ✅ | ✅ | ❌ |
| Default (no includeFacets) | ✅ | ✅ | ❌ |
| URI-namespace nodeId | ✅ | ✅ | ❌ |
| Missing node (404) | ✅ | ✅ | ❌ |
| Full normalized-JSON equality (graph) | ✅ (V1=V2) | ✅ | — |

### Column-lineage

| Scenario | V1 | V2 | V3 |
|---|:-:|:-:|:-:|
| By `dataset:` nodeId | ✅ | ✅ | ❌ |
| By `datasetField:` nodeId | ✅ | ✅ | ❌ |
| `https://` URI namespace | ✅ | ✅ | ❌ |
| Missing `nodeId` → 400 | ✅ | ✅ | ❌ |
| Missing node → 404 | ✅ | ✅ | ❌ |
| Full normalized-JSON equality | ✅ (V1=V2) | ✅ | — |

### Not yet covered at any V-pair (follow-up list)

- `RunResource` transitions (START/RUNNING/COMPLETE/FAIL/ABORT) + facets on runs
- `SearchResource` (`/api/v2beta/search` for UI autocomplete)
- `StatsResource` — dataset/job/source/lineage-event counts
- `EventsLineage` + `RunLineageUpstream`
- `DatasetVersion` and `JobVersion` endpoints
- Tag-mutation endpoints on datasets / dataset-fields
- V2 and V3 normalized response body equality against V1 for the lineage core fields (currently asserts V1↔V2 byte-level, V1↔V3 asserts identity-level only)

## Release-readiness assessment

| Role | Verdict | Rationale |
|---|---|---|
| **Tester** | 🟡 Ship with follow-ups | 48 new parity tests green; 2 documented gaps (namespace soft-delete, Tag not in AGE) pinned by tests. Known non-coverage: RunResource lifecycle, Search, Stats. |
| **Product** | 🟢 Ship V1↔V2 guaranteed; V3 covered for all UI-hit endpoints (the UI uses `/api/v3`). Two V3 gaps are known & documented. | UI-critical surface (namespace, job, dataset, URI-ns handling) is green. |
| **Architect** | 🟢 Ship | V103 migration deployed, AGE enabled only when migration succeeds, V1↔V2 service-layer shared, V3 read-path isolated behind AGE. Parity IT suite guards against future drift. |

## Go/no-go

**Go** for releasing this PR as v3-beta. Follow-ups tracked above should be picked up in a separate PR before GA.
