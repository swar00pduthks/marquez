# Marquez V3 Graph Model

This document explains how Marquez stores lineage data as a property graph using
[Apache AGE](https://age.apache.org/) — the graph extension for PostgreSQL — and how the
existing OpenLineage / relational concepts map to graph vertices and edges.

---

## Why a Graph?

Lineage is fundamentally a graph problem. A job reads datasets, produces datasets, and those
datasets feed into other jobs. Traversing these relationships in a relational model requires
recursive CTEs and expensive JOINs that degrade as data grows to billions of rows.

Apache AGE adds an **OpenCypher** query layer on top of the existing PostgreSQL database. Marquez
stores *both* the relational tables (V1/V2 API) **and** the AGE graph in the same PostgreSQL
instance. The graph is built from the relational data via:

1. **Real-time write** — every `POST /api/v3/lineage` event is written to both the relational
   store and the AGE graph in the same request.
2. **Backfill** — `GraphV1BackfillJob` replays historical `lineage_events` rows into the graph
   for data that arrived before V3 was deployed.

---

## Apache AGE Concepts

| AGE Term | Meaning |
|---|---|
| **Graph** | A named container for all nodes and edges. Marquez uses one graph: `marquez_graph`. |
| **Vertex (Node)** | A labelled entity with properties stored as `agtype` (superset of JSONB). |
| **Edge** | A directed, labelled relationship between two vertices. Also has properties. |
| **Label** | The "type" of a vertex or edge (e.g. `Job`, `Dataset`, `INPUT_TO`). |
| **agtype** | AGE's JSON-compatible property type. Supports strings, numbers, booleans, lists, maps. |
| **Cypher** | The OpenCypher query language used to read/write the AGE graph. |

### Session setup (required before any Cypher query)

```sql
-- Local / Docker (not needed on Azure where AGE is preloaded)
LOAD 'age';
SET search_path = ag_catalog, marquez_v3, "$user", public;
```

---

## Graph Name and Schema

| Setting | Value |
|---|---|
| Graph name | `marquez_graph` |
| AGE schema | `ag_catalog` |
| Marquez V3 schema | `marquez_v3` |
| FQN format | `{namespace}:{name}` (e.g. `spark-ns:my-job`) |

All vertex lookups use the **FQN** (`namespace:name`) as the primary key because it is unique
across all namespaces without requiring a JOIN.

---

## Vertex (Node) Types

### `:Source`

Represents a data source / connector origin. Currently always `"default"`.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `name` | string | `"default"` | `sources.name` |
| `type` | string | `"unknown"` | `sources.type` |

---

### `:Namespace`

An organisational scope that groups Jobs and Datasets.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `name` | string | `"spark-ns"` | `namespaces.name` |

---

### `:Job`

A repeatable computation unit (e.g. a Spark application, an Airflow task).

| Property | Type | Example | Maps from |
|---|---|---|---|
| `fqn` | string (**PK**) | `"spark-ns:my-etl-job"` | `{namespace}:{name}` |
| `name` | string | `"my-etl-job"` | `jobs.name` |
| `namespace` | string | `"spark-ns"` | `jobs.namespace_name` |
| `simpleName` | string | `"etl-job"` | last segment after `/` in name |
| `facets` | JSON string | `{"jobType": {...}}` | OpenLineage job facets |

---

### `:JobVersion`

A specific version of a job, determined by a deterministic UUID derived from the job's
facets + input/output dataset signatures. A job has many versions as its I/O schema evolves.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `uuid` | string (**PK**) | `"abc123..."` | SHA-based UUID of `fqn+facets+inputs+outputs` |
| `version` | string | `"abc123..."` | same as `uuid` |
| `jobFqn` | string | `"spark-ns:my-etl-job"` | `job_versions.job_uuid` → job fqn |
| `jobContext` | JSON string | `{"jobType": {...}}` | job facets at the time of this version |

---

### `:Run`

A single execution instance of a JobVersion.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `runId` | string (**PK**) | `"550e8400-..."` | `runs.uuid` |
| `fqn` | string | `"spark-ns:my-etl-job"` | job fqn (denormalized for fast lookup) |
| `jobName` | string | `"my-etl-job"` | `jobs.name` |
| `jobNamespace` | string | `"spark-ns"` | `jobs.namespace_name` |
| `state` | string | `"COMPLETE"` | last OpenLineage event type: `START`/`RUNNING`/`COMPLETE`/`FAIL`/`ABORT` |
| `createdAt` | ISO-8601 | `"2024-01-15T10:00:00Z"` | first event time for this runId |
| `updatedAt` | ISO-8601 | `"2024-01-15T10:05:00Z"` | most recent event time |
| `startedAt` | ISO-8601 | `"2024-01-15T10:00:00Z"` | set on `START` or `RUNNING` event |
| `endedAt` | ISO-8601 | `"2024-01-15T10:05:00Z"` | set on `COMPLETE`/`FAIL`/`ABORT` event |
| `durationMs` | number | `300000` | `endedAt - startedAt` |
| `facets` | JSON string | `{"parent": {...}}` | OpenLineage run facets |

> **Parent run:** If a run carries the `parent` facet (`run.facets.parent.run.runId`), a parent
> `:Run` node is also created (in `RUNNING` state) and a `HAS_CHILD_RUN` edge is written.

---

### `:Dataset`

A logical data entity (table, topic, file path). A dataset exists independently of its versions.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `fqn` | string (**PK**) | `"hive-ns:orders"` | `{namespace}:{name}` |
| `name` | string | `"orders"` | `datasets.name` |
| `namespace` | string | `"hive-ns"` | `datasets.namespace_name` |
| `type` | string | `"DB_TABLE"` | `datasets.type` |
| `facets` | JSON string | `{"schema": {...}}` | OpenLineage dataset facets |

---

### `:DatasetVersion`

An immutable snapshot of a dataset at a specific point in time (schema + content fingerprint).

| Property | Type | Example | Maps from |
|---|---|---|---|
| `uuid` | string (**PK**) | `"ff00cc..."` | `dataset_versions.uuid` |
| `version` | string | `"ff00cc..."` | same as `uuid` |
| `datasetFqn` | string | `"hive-ns:orders"` | denormalized dataset fqn |
| `namespace` | string | `"hive-ns"` | denormalized dataset namespace |
| `createdAt` | ISO-8601 | `"2024-01-15T10:05:00Z"` | event time when this version was created |
| `facets` | JSON string | `{"dataSource": {...}}` | OpenLineage dataset version facets |

---

### `:DatasetField`

A column or field within a dataset version.

| Property | Type | Example | Maps from |
|---|---|---|---|
| `name` | string (**PK within dataset**) | `"order_id"` | `dataset_fields.name` |
| `type` | string | `"BIGINT"` | `dataset_fields.type` |
| `description` | string | `"Primary key"` | `dataset_fields.description` |

---

## Edge (Relationship) Types

### Structural / Organisational Edges

| Edge | From → To | Meaning |
|---|---|---|
| `HAS_NAMESPACE` | `:Source` → `:Namespace` | A source owns a namespace |
| `CONTAINS` | `:Namespace` → `:Job` | A namespace contains a job |
| `CONTAINS` | `:Namespace` → `:Dataset` | A namespace contains a dataset |
| `HAS_JOB_VERSION` | `:Job` → `:JobVersion` | A job has a version |
| `HAS_RUN` | `:JobVersion` → `:Run` | A job version was executed as a run |
| `RUN_OF` | `:Run` → `:Job` | Shortcut: a run belongs to a job (bypasses JobVersion) |
| `HAS_CHILD_RUN` | `:Run` → `:Run` | Parent run spawned a child run (via OpenLineage `parent` facet) |
| `HAS_DATASET_VERSION` | `:Dataset` → `:DatasetVersion` | A dataset has a version |
| `VERSION_OF` | `:DatasetVersion` → `:Dataset` | Reverse of HAS_DATASET_VERSION |
| `HAS_FIELD` | `:DatasetVersion` → `:DatasetField` | A dataset version has a field/column |

### Lineage Edges (the core of the graph)

| Edge | From → To | Meaning |
|---|---|---|
| `INPUT_TO` | `:Dataset` → `:Job` | **Job-level shortcut:** this dataset feeds this job |
| `PRODUCES` | `:Job` → `:Dataset` | **Job-level shortcut:** this job produces this dataset |
| `READS` | `:Run` → `:DatasetVersion` | This run read this specific dataset version |
| `WRITES` | `:Run` → `:DatasetVersion` | This run produced this specific dataset version |

> **Two levels of lineage edges:**
> - `INPUT_TO` / `PRODUCES` connect `:Dataset` ↔ `:Job` directly. These are the edges used
>   by `GET /api/v3/lineage` for fast graph traversal. They are **job-level** and do not track
>   individual versions.
> - `READS` / `WRITES` connect `:Run` ↔ `:DatasetVersion`. These track **exactly which version**
>   was read/written by which execution. Used for run-level lineage queries.

---

## Full Graph Diagram

```
(:Source {name:"default"})
    -[:HAS_NAMESPACE]->
(:Namespace {name:"spark-ns"})
    -[:CONTAINS]->
(:Job {fqn:"spark-ns:my-etl-job"})
    -[:HAS_JOB_VERSION]->
(:JobVersion {uuid:"abc123"})
    -[:HAS_RUN]->
(:Run {runId:"550e...", state:"COMPLETE"})
    -[:READS]->
(:DatasetVersion {uuid:"ddee..", datasetFqn:"hive-ns:raw-orders"})
    -[:VERSION_OF]->
(:Dataset {fqn:"hive-ns:raw-orders"})
    -[:HAS_FIELD]->
(:DatasetField {name:"order_id", type:"BIGINT"})

(:Dataset {fqn:"hive-ns:raw-orders"})
    -[:INPUT_TO]->
(:Job {fqn:"spark-ns:my-etl-job"})
    -[:PRODUCES]->
(:Dataset {fqn:"hive-ns:clean-orders"})

(:Run {runId:"550e..."})
    -[:RUN_OF]->
(:Job {fqn:"spark-ns:my-etl-job"})

-- Parent / child runs (Spark driver + tasks):
(:Run {runId:"parent-id", state:"COMPLETE"})
    -[:HAS_CHILD_RUN]->
(:Run {runId:"child-id", state:"COMPLETE"})
```

---

## Relational → Graph Mapping Reference

| Relational table | Graph concept | Notes |
|---|---|---|
| `namespaces` | `:Namespace` vertex | 1:1 |
| `sources` | `:Source` vertex | Always `"default"` in current impl |
| `jobs` | `:Job` vertex | `fqn = namespace:name` |
| `job_versions` | `:JobVersion` vertex | UUID is deterministic hash of facets+I/O |
| `runs` | `:Run` vertex | State updated on each event type |
| `run_states` | `state` property on `:Run` | Collapsed to latest state on the vertex |
| `datasets` | `:Dataset` vertex | `fqn = namespace:name` |
| `dataset_versions` | `:DatasetVersion` vertex | UUID from `dataset_versions.uuid` |
| `dataset_fields` | `:DatasetField` vertex | Per-version field snapshot |
| `job_versions_io` (input) | `INPUT_TO` edge + `READS` edge | Job-level and run-level |
| `job_versions_io` (output) | `PRODUCES` edge + `WRITES` edge | Job-level and run-level |
| `runs.parent_run_uuid` | `HAS_CHILD_RUN` edge | Written when OpenLineage `parent` facet is present |

---

## Backfill

The graph is populated from historical data via two background jobs:

### `GraphV1BackfillJob`

Replays `lineage_events` rows into the AGE graph using keyset pagination on
`(event_time, run_id)` — no OFFSET scans on a multi-billion-row table.

- Checkpointed in `backfill_checkpoints` (table with `version`, `last_event_time`,
  `last_run_id`, `completed_at`)
- Idempotent: all writes use Cypher `MERGE` so re-running the same event is a no-op
- Stops permanently once `completed_at` is set (first empty batch)

### `DenormV1BackfillJob`

Backfills the `run_lineage_denormalized` table (used by V2 API performance queries) using
the same checkpoint pattern.

---

## Key Queries

```sql
-- Set up session first
LOAD 'age';
SET search_path = ag_catalog, marquez_v3, "$user", public;

-- Count vertices by label
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Job) RETURN count(n) $$) AS (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Dataset) RETURN count(n) $$) AS (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Run) RETURN count(n) $$) AS (c agtype);

-- Lineage for a job: what datasets feed into it?
SELECT * FROM cypher('marquez_graph', $$
  MATCH (d:Dataset)-[:INPUT_TO]->(j:Job)
  WHERE j.fqn = 'spark-ns:my-etl-job'
  RETURN d.fqn
$$) AS (fqn agtype);

-- Lineage for a job: what does it produce?
SELECT * FROM cypher('marquez_graph', $$
  MATCH (j:Job)-[:PRODUCES]->(d:Dataset)
  WHERE j.fqn = 'spark-ns:my-etl-job'
  RETURN d.fqn
$$) AS (fqn agtype);

-- 2-hop lineage: datasets → job → datasets
SELECT * FROM cypher('marquez_graph', $$
  MATCH (n {fqn: 'spark-ns:my-etl-job'})-[:INPUT_TO|PRODUCES*1..2]-(m)
  RETURN DISTINCT m.fqn, labels(m)[0]
$$) AS (fqn agtype, label agtype);

-- All runs for a job with state
SELECT * FROM cypher('marquez_graph', $$
  MATCH (r:Run)-[:RUN_OF]->(j:Job)
  WHERE j.fqn = 'spark-ns:my-etl-job'
  RETURN r.runId, r.state, r.startedAt, r.endedAt
  ORDER BY r.createdAt DESC LIMIT 20
$$) AS (runId agtype, state agtype, startedAt agtype, endedAt agtype);

-- What did a specific run read and write?
SELECT * FROM cypher('marquez_graph', $$
  MATCH (r:Run {runId: 'your-run-id'})-[rel:READS|WRITES]->(dv:DatasetVersion)
  RETURN type(rel), dv.datasetFqn, dv.uuid
$$) AS (rel_type agtype, dataset agtype, version agtype);

-- Parent/child run tree
SELECT * FROM cypher('marquez_graph', $$
  MATCH (parent:Run {runId: 'parent-run-id'})-[:HAS_CHILD_RUN*1..]->(child:Run)
  RETURN child.runId, child.state, child.fqn
$$) AS (runId agtype, state agtype, fqn agtype);
```

---

## AGE Limitations in v1.5.0

See [v3-api-investigation-guide.md](v3-api-investigation-guide.md#age-150-known-limitations) for
the full list of known AGE 1.5.0 bugs and their workarounds. Key ones:

| Limitation | Workaround |
|---|---|
| `[:T1\|T2]` alternation syntax not supported | Two separate queries merged in Java |
| `MERGE + SET` on existing nodes throws "Entity failed: 3" | Split into MERGE then MATCH+SET |
| `WHERE (n:Label)` predicate in WHERE not supported | Rely on edge type semantics instead |
| `*0..N` variable-length paths unreliable | Use `*1..N` + a separate exact-match query |
| `LOAD 'age'` blocked on Azure Flexible Server | Detect via `pg_extension`, skip LOAD |
