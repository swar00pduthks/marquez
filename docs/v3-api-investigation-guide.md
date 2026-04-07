# V3 API Investigation Guide

This document covers how to diagnose issues with each V3 endpoint, the underlying Cypher queries,
and known Apache AGE 1.5.0 limitations discovered during development.

---

## Environment

| Component | Value |
|-----------|-------|
| API endpoint | `http://<EXTERNAL_IP>:5000` |
| Graph DB | Apache AGE 1.5.0 on Azure PostgreSQL Flexible Server |
| Graph name | `marquez_graph` |
| Schema | `ag_catalog`, `marquez_v3` |
| AGE graph labels | `Namespace`, `Source`, `Job`, `JobVersion`, `Run`, `Dataset`, `DatasetVersion`, `DatasetField` |
| AGE edge types | `HAS_NAMESPACE`, `CONTAINS`, `HAS_JOB_VERSION`, `HAS_RUN`, `RUN_OF`, `HAS_CHILD_RUN`, `READS`, `WRITES`, `INPUT_TO`, `PRODUCES`, `HAS_DATASET_VERSION`, `VERSION_OF`, `HAS_FIELD` |

---

## Connecting to the database for manual queries

```bash
# From any machine with psql access to the Azure PostgreSQL instance
PGPASSWORD='<password>' psql 'postgresql://<user>@<host>/<db>'

# Required session setup before any Cypher query
LOAD 'age';
SET search_path = ag_catalog, marquez_v3, "$user", public;
```

**Note:** On Azure Flexible Server, `LOAD 'age'` is blocked by policy. The application handles this
by detecting the extension via `SELECT 1 FROM pg_extension WHERE extname = 'age'` and skipping the
LOAD. For manual psql sessions you may need to use a superuser role or run from within the cluster:

```bash
kubectl exec -n marquez <pod> -- psql 'postgresql://...' -c "SELECT 1 FROM pg_extension WHERE extname = 'age'"
```

---

## Endpoint reference with Cypher queries

### 1. `POST /api/v3/lineage`

**What it does:** Ingests an OpenLineage event into both the relational store and the AGE graph.

**Cypher writes performed (in order):**

```cypher
-- Namespace + Source
MERGE (n:Source {name: 'default'}) RETURN n
MATCH (n:Source {name: 'default'}) SET n.name = 'default', n.type = 'unknown' RETURN n

MERGE (n:Namespace {name: $ns}) RETURN n
MATCH (n:Namespace {name: $ns}) SET n.name = $ns RETURN n

MERGE (a:Source {name: 'default'})-[r:HAS_NAMESPACE]->(b:Namespace {name: $ns}) RETURN r

-- Job
MERGE (n:Job {fqn: $fqn}) RETURN n
MATCH (n:Job {fqn: $fqn}) SET n.fqn = $fqn, n.name = $name, n.namespace = $ns, ... RETURN n
MERGE (a:Namespace {name: $ns})-[r:CONTAINS]->(b:Job {fqn: $fqn}) RETURN r

-- JobVersion, Run, Input/Output datasets (similar pattern)

-- Job-level shortcuts (used by lineage graph query)
MERGE (a:Dataset {fqn: $dsFqn})-[r:INPUT_TO]->(b:Job {fqn: $jobFqn}) RETURN r
MERGE (a:Job {fqn: $jobFqn})-[r:PRODUCES]->(b:Dataset {fqn: $dsFqn}) RETURN r
```

**How to investigate a 500 "Graph write failed":**

```bash
# Check pod logs
kubectl logs -n marquez -l app=marquez-api-v3 --tail=50 | grep -E '(ERROR|Graph write|Entity failed)'

# Verify graph is reachable
curl http://<IP>:5000/api/v3/namespaces

# Check AGE extension is present
kubectl exec -n marquez <pod> -- psql '...' -c "SELECT * FROM pg_extension WHERE extname = 'age'"

# Verify graph exists
psql> SELECT * FROM ag_graph;
```

**Common errors:**
- `Entity failed to be updated: 3` — AGE 1.5.0 bug when SET is applied to an existing node. Suppressed in code; log level DEBUG. Safe to ignore.
- `current transaction is aborted` — indicates `useTransaction` instead of `useHandle`. Should use autocommit (useHandle) for all Cypher writes.
- `Graph write failed: Failed to write lineage event to graph` — check the full stack trace in logs.

---

### 2. `GET /api/v3/lineage?nodeId=job:{namespace}:{name}&depth=2`

**Cypher queries:**

```cypher
-- Step 1: fetch start node
MATCH (n:Job {fqn: $fqn}) RETURN n LIMIT 1

-- Step 2: traverse INPUT_TO edges (run twice: once per edge type)
MATCH (:Job {fqn: $fqn})-[:INPUT_TO*1..2]-(n) RETURN DISTINCT n LIMIT 500
MATCH (:Job {fqn: $fqn})-[:PRODUCES*1..2]-(n) RETURN DISTINCT n LIMIT 500

-- Step 3: collect edges (only if >1 node found)
MATCH (:Job {fqn: $fqn})-[rels:INPUT_TO*1..2]-(n) UNWIND rels AS rel RETURN DISTINCT rel LIMIT 1000
MATCH (:Job {fqn: $fqn})-[rels:PRODUCES*1..2]-(n) UNWIND rels AS rel RETURN DISTINCT rel LIMIT 1000
```

Same pattern for `nodeId=dataset:{namespace}:{name}` using `Dataset` label.

**How to investigate empty graph / missing edges:**

```bash
# Check if nodes exist
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Job {fqn: 'ns:jobname'}) RETURN n $$) as (n agtype);

# Check if INPUT_TO edges exist
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (a:Dataset)-[r:INPUT_TO]->(b:Job) WHERE b.fqn = 'ns:jobname' RETURN a.fqn, b.fqn $$) as (a agtype, b agtype);

# Check if PRODUCES edges exist
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (a:Job)-[r:PRODUCES]->(b:Dataset) WHERE a.fqn = 'ns:jobname' RETURN a.fqn, b.fqn $$) as (a agtype, b agtype);
```

**Common issues:**
- Empty `inEdges`/`outEdges` — edges not written (check POST returned 201 and no "Graph write failed" in logs)
- `syntax error at or near ":"` — label predicate in WHERE (e.g. `WHERE (n:Job OR n:Dataset)`) — not supported in AGE 1.5.0
- Nodes found but no edges — run a fresh POST event; existing data may predate the edge-writing code

---

### 3. `GET /api/v3/lineage?nodeId=run:{runId}`

**Cypher queries:**

```cypher
-- Fetch run properties
MATCH (r:Run {runId: $runId}) RETURN properties(r) LIMIT 1

-- Fetch inputs (DatasetVersions read by this run)
MATCH (r:Run {runId: $runId})-[:READS]->(dv:DatasetVersion)-[:VERSION_OF]->(d:Dataset)
RETURN properties(dv), properties(d)

-- Fetch outputs
MATCH (r:Run {runId: $runId})-[:WRITES]->(dv:DatasetVersion)-[:VERSION_OF]->(d:Dataset)
RETURN properties(dv), properties(d)
```

**How to investigate:**

```bash
# Check run exists in graph
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (r:Run {runId: 'your-run-id'}) RETURN properties(r) $$) as (r agtype);

# Check READS edges
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (r:Run {runId: 'your-run-id'})-[:READS]->(dv:DatasetVersion) RETURN dv.uuid, dv.datasetFqn $$) as (uuid agtype, fqn agtype);
```

**Common issues:**
- 404 — run not in graph; POST event may have failed silently (check logs)
- Duplicate inputs — same DatasetVersion UUID appears multiple times; this is a data artifact from repeated ingestion of the same event. Use `deduplicateDatasetVersions` or post with unique runIds.

---

### 4. `GET /api/v3/lineage?nodeId=run:{runId}&aggregateToParentRun=true`

**Cypher queries:**

```cypher
-- Find root ancestor (walk up HAS_CHILD_RUN in reverse)
MATCH (ancestor:Run)-[:HAS_CHILD_RUN*1..]->(r:Run {runId: $runId})
RETURN properties(ancestor)

-- Collect all descendant runs from ancestor
MATCH (parent:Run {runId: $parentId})-[:HAS_CHILD_RUN*0..]->(c:Run)
RETURN properties(c)

-- For each child run, fetch inputs and outputs (same as endpoint 3)
```

**How to investigate:**

```bash
# Check HAS_CHILD_RUN edges exist
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (p:Run)-[:HAS_CHILD_RUN]->(c:Run) WHERE c.runId = 'child-id' RETURN p.runId, c.runId $$) as (p agtype, c agtype);

# Check parent facet was included in the event
# The parent run facet must be in run.facets.parent.run.runId
```

**Common issues:**
- Returns the child run itself with no aggregation — means no `HAS_CHILD_RUN` edge was written (parent facet was missing from the OpenLineage event)
- `syntax error at or near ")"` — AGE 1.5.0 does not support `WHERE NOT ()-[:R]->(n)` anonymous node patterns in WHERE clauses

---

### 5. `GET /api/v3/namespaces`

**Cypher query:**

```cypher
MATCH (n:Namespace) RETURN properties(n) SKIP $off LIMIT $lim
```

**How to investigate:**

```bash
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Namespace) RETURN n.name LIMIT 20 $$) as (name agtype);
```

---

### 6. `GET /api/v3/namespaces/{namespace}/datasets`

**Cypher query:**

```cypher
MATCH (d:Dataset) WHERE d.namespace = $ns
RETURN properties(d)
SKIP $off LIMIT $lim
```

**How to investigate:**

```bash
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (d:Dataset) WHERE d.namespace = 'my-ns' RETURN d.name, d.fqn LIMIT 20 $$) as (name agtype, fqn agtype);
```

---

### 7. `GET /api/v3/namespaces/{namespace}/datasets/{dataset}`

**Cypher query:**

```cypher
MATCH (d:Dataset) WHERE d.namespace = $ns AND d.name = $ds
RETURN properties(d)
```

---

### 8. `GET /api/v3/namespaces/{namespace}/datasets/{dataset}/versions`

**Cypher query:**

```cypher
MATCH (:Namespace {name: $ns})-[:CONTAINS]->(:Dataset {name: $ds})-[:HAS_DATASET_VERSION]->(v:DatasetVersion)
RETURN properties(v)
SKIP $off LIMIT $lim
```

**Note:** This requires `CONTAINS` edges. These are written during real-time ingestion via the graph writer. If versions are missing, check `CONTAINS` edges:

```bash
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (:Namespace {name: 'my-ns'})-[:CONTAINS]->(d:Dataset) RETURN d.name $$) as (name agtype);
```

---

### 9. `GET /api/v3/namespaces/{namespace}/jobs`

**Cypher query:**

```cypher
MATCH (j:Job) WHERE j.namespace = $ns
RETURN properties(j)
SKIP $off LIMIT $lim
```

---

### 10. `GET /api/v3/namespaces/{namespace}/jobs/{job}`

**Cypher query:**

```cypher
MATCH (j:Job) WHERE j.namespace = $ns AND j.name = $job
RETURN properties(j)
LIMIT 1
```

---

### 11. `GET /api/v3/namespaces/{namespace}/jobs/{job}/runs`

**Cypher query:**

```cypher
MATCH (r:Run)-[:RUN_OF]->(j:Job)
WHERE j.namespace = $ns AND j.name = $job
WITH DISTINCT r
RETURN properties(r)
ORDER BY r.createdAt DESC
SKIP $off LIMIT $lim
```

**Java-level deduplication:** Results are additionally deduped in Java by `runId`, preferring the entry that has `fqn` (most complete run node). This handles the case where duplicate Run nodes exist in the graph from earlier bugs.

**How to investigate:**

```bash
# Count runs for a job
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (r:Run)-[:RUN_OF]->(j:Job) WHERE j.fqn = 'ns:jobname' RETURN r.runId, r.state, r.createdAt $$) as (runId agtype, state agtype, createdAt agtype);

# Check if duplicate Run nodes exist (same runId, different graph IDs)
psql> SELECT * FROM cypher('marquez_graph', $$ MATCH (r:Run {runId: 'your-run-id'}) RETURN id(r), r.runId, r.createdAt $$) as (id agtype, runId agtype, createdAt agtype);
```

**Common issues:**
- Duplicate runs — two Run nodes with the same `runId` in the graph (from previous bug where upsert was not idempotent). Fixed in v31 by Java-level dedup.

---

### 12. `GET /api/v3/namespaces/{namespace}/jobs/{job}/runs/{runId}` (via `RunResourceV3`)

**Cypher query:**

```cypher
MATCH (r:Run {runId: $runId}) RETURN properties(r) LIMIT 1
```

---

### 13. Dataset version fields (batch-fetched per dataset in lineage graph)

**Cypher query:**

```cypher
MATCH (d:Dataset {fqn: $fqn})-[:HAS_DATASET_VERSION]->(dv:DatasetVersion)-[:HAS_FIELD]->(f:DatasetField)
RETURN properties(f)
```

---

## AGE 1.5.0 Known Limitations

These were discovered through production debugging. All have workarounds in the codebase.

### 1. `LOAD 'age'` blocked on Azure Flexible Server

**Symptom:** `ERROR: permission denied to execute LOAD`
**Workaround:** Detect via `SELECT 1 FROM pg_extension WHERE extname = 'age'` instead. The `LOAD` itself is not needed if AGE is already loaded as an extension.
**Code location:** `GraphDao.detectAge()`

---

### 2. `MERGE + SET` on existing nodes throws "Entity failed to be updated: 3"

**Symptom:** `ERROR: Entity failed to be updated: 3`
**Root cause:** PostgreSQL `TM_Updated` heap tuple version conflict. Occurs when Cypher `SET` tries to update a node that was already committed in a previous statement within the same session.
**Workaround:** Split into two statements — `MERGE` (no SET) to ensure node exists, then `MATCH ... SET n.k=v` to update. Catch and suppress the "Entity failed" error on the SET step for existing nodes (their properties are stable).
**Code location:** `GraphDao.upsertNode()`
**Affects:** All node upserts (Job, Dataset, Run, etc.)

---

### 3. Edge-type alternation `[:T1|T2]` not supported

**Symptom:** `ERROR: syntax error at or near "|"`
**Workaround:** Run two separate Cypher queries — one per edge type — and merge results in Java.
**Affects:** `GET /api/v3/lineage` node and edge traversal queries

---

### 4. Label predicates in WHERE not supported

**Symptom:** `ERROR: syntax error at or near ":"`
**Example:** `WHERE (other:Job OR other:Dataset)` fails
**Workaround:** Remove the WHERE predicate. The `INPUT_TO` and `PRODUCES` edge types connect only Job and Dataset nodes by schema, so the filter is redundant.
**Affects:** Lineage graph traversal queries

---

### 5. List concatenation `collect() + collect()` not supported

**Symptom:** `ERROR: syntax error` or wrong results
**Example:** `WITH collect(DISTINCT start) + collect(DISTINCT other) AS ns` fails
**Workaround:** Fetch the start node separately, then fetch connected nodes in a second query without the concatenation.
**Affects:** `GET /api/v3/lineage` node collection

---

### 6. `WHERE NOT ()-[:R]->(n)` anonymous node pattern not supported

**Symptom:** `ERROR: syntax error at or near ")"`
**Example:** `WHERE NOT ()-[:HAS_CHILD_RUN]->(ancestor)` fails
**Workaround:** Use a reverse traversal (`MATCH (ancestor:Run)-[:HAS_CHILD_RUN*1..]->(r:Run {runId: $id})`) and take the last result (furthest ancestor).
**Affects:** `findRootAncestor()` in aggregated run lineage

---

### 7. `properties()` is a Cypher function, not a SQL function

**Symptom:** `ERROR: function properties(agtype) does not exist`
**Wrong:** `SELECT agtype_to_json(properties(n)) FROM cypher(...) RETURN n`
**Correct:** `SELECT agtype_to_json(n) FROM cypher(...) RETURN properties(n)`
`properties()` must be called inside the Cypher `$$...$$` block; the SQL column alias receives the result.

---

### 8. `useTransaction` + AGE SAVEPOINT cascade

**Symptom:** `ERROR: current transaction is aborted, commands ignored until end of transaction block`
**Root cause:** When AGE's `LOAD 'age'` fails inside a transaction (Azure blocks it), the transaction enters aborted state. All subsequent statements in that transaction fail.
**Workaround:** Use `jdbi.useHandle()` (autocommit) instead of `jdbi.useTransaction()` for all AGE Cypher operations. Cypher MERGE is idempotent, so transaction atomicity is not required.
**Affects:** `POST /api/v3/lineage` graph write, `OpenLineageService` async graph write

---

### 9. `ON CREATE SET` / `ON MATCH SET` not supported

**Symptom:** `ERROR: syntax error at or near "ON"`
**Workaround:** Use the split MERGE + MATCH+SET approach described in limitation #2.

---

## General debugging checklist

```bash
# 1. Is the pod running?
kubectl get pods -n marquez -l app=marquez-api-v3

# 2. Is AGE available?
curl http://<IP>:5000/api/v3/namespaces
# Empty list = AGE not available or graph empty
# 503 = AGE explicitly not available (GraphDao.isAgeAvailable() = false)

# 3. Recent errors?
kubectl logs -n marquez -l app=marquez-api-v3 --tail=100 | grep -E '(ERROR|WARN|Graph write|Entity failed|syntax error)'

# 4. Did the graph write succeed?
kubectl logs -n marquez -l app=marquez-api-v3 --tail=50 | grep -v 'stats/' | grep -v memcache

# 5. Does the node exist in the graph?
# (use psql inside the pod as shown in "Connecting to the database" section)
```

## Quick Cypher reference for manual investigation

```sql
-- Count nodes by label
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Job) RETURN count(n) $$) as (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Dataset) RETURN count(n) $$) as (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH (n:Run) RETURN count(n) $$) as (c agtype);

-- Count edges by type
SELECT * FROM cypher('marquez_graph', $$ MATCH ()-[r:INPUT_TO]->() RETURN count(r) $$) as (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH ()-[r:PRODUCES]->() RETURN count(r) $$) as (c agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH ()-[r:HAS_CHILD_RUN]->() RETURN count(r) $$) as (c agtype);

-- Find all runs for a job
SELECT * FROM cypher('marquez_graph', $$ MATCH (r:Run)-[:RUN_OF]->(j:Job) WHERE j.fqn = 'ns:jobname' RETURN r.runId, r.state LIMIT 10 $$) as (runId agtype, state agtype);

-- Find lineage for a job
SELECT * FROM cypher('marquez_graph', $$ MATCH (d:Dataset)-[:INPUT_TO]->(j:Job) WHERE j.fqn = 'ns:jobname' RETURN d.fqn $$) as (fqn agtype);
SELECT * FROM cypher('marquez_graph', $$ MATCH (j:Job)-[:PRODUCES]->(d:Dataset) WHERE j.fqn = 'ns:jobname' RETURN d.fqn $$) as (fqn agtype);

-- List all graphs
SELECT * FROM ag_graph;

-- List all labels in the graph
SELECT * FROM ag_label;
```
