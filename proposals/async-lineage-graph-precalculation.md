# Proposal: Asynchronous Lineage Graph Precalculation

## The Problem
Currently, the Marquez API calculates lineage dynamically using recursive Common Table Expressions (CTEs) like `WITH RECURSIVE lineage AS (...)`. For a large graph (millions or billions of lineage events, particularly for run-level datasets and facets), these recursive queries force PostgreSQL to do heavy on-the-fly index traversals and array aggregations. Even with indexes, fetching a lineage graph up to a depth of 20 levels can result in massive CPU usage and slow responses (frequently exceeding 100ms and sometimes resulting in API timeouts).

## Proposed Solution: Precalculated JSON Payloads
Instead of evaluating the 20-level dependency tree synchronously during the API `GET /api/v1/lineage` request, we should pre-calculate the graph and serve it instantaneously.

### 1. Database Table
Create a new denormalized table to serve lineage directly:

```sql
CREATE TABLE precalculated_lineage (
    node_id VARCHAR(255) PRIMARY KEY,
    lineage_graph JSONB NOT NULL,
    last_updated TIMESTAMPTZ DEFAULT NOW()
);
```

### 2. Asynchronous Background Calculation
Introduce a background job (e.g., using a queue or periodically triggered worker) that builds the JSON graph using the existing recursive query. When an event is ingested that modifies an edge (e.g. a new job run with a new dataset input/output):
1. The background worker identifies the affected components.
2. It recalculates the graph up to depth 20 for the impacted `node_id`.
3. It performs a fast UPSERT into `precalculated_lineage`.

### 3. API Read Path
The API read path becomes a simple key-value lookup:
```java
@SqlQuery("SELECT lineage_graph FROM precalculated_lineage WHERE node_id = :nodeId")
String getPrecalculatedLineage(String nodeId);
```
This drops API response times from seconds down to ~1-5 milliseconds, regardless of the complexity of the 20-level tree.

## Alternatives Considered
* **Closure Tables:** Maintaining a closure table (`ancestor_id`, `descendant_id`, `depth`) makes it easier to do incremental updates to the graph, but still requires the API to fetch and serialize hundreds of rows into a nested JSON structure at read time.
* **Graph Databases:** We could use Neo4j or another specialized graph database, but that violates the "no external infrastructure" standard and adds immense operational overhead compared to leveraging PostgreSQL's JSONB capabilities.

## Conclusion
Moving graph traversal out of the synchronous API read path into an asynchronous background worker is the only scalable way to handle deeply recursive data models at billion-event scale.
