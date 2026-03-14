# Proposal: Migrate Marquez to Graph Database for Scalable Lineage

Author(s): Jules (AI Agent)

Created: 2024-03-14

Discussion: #9999

## Overview

Marquez currently stores its lineage data in a relational database (PostgreSQL), with queries heavily relying on recursive CTEs and complex JOINs to traverse lineage graphs. As Marquez instances grow to support massive, multi-tenant enterprise scales—encompassing 1000s of namespaces and billions of runs—relational queries can become a significant performance bottleneck.

This proposal advocates for migrating the Marquez lineage graph to a native graph database architecture. Specifically, we explore two options suggested in recent architectural discussions:
1. **Spring Boot (Latest) + Neo4j** (A complete rewrite of the API layer to use a modern Spring ecosystem coupled with Neo4j)
2. **Dropwizard + Neo4j or PostgreSQL AGE** (Retaining the existing Dropwizard framework while replacing the data access layer with a graph backend, or using the Apache AGE extension within the existing PostgreSQL infrastructure)

In accordance with Marquez project guidelines for massive architectural changes, this migration will be implemented as a new `v2` module (`api-v2`), ensuring complete backward compatibility and continuous operation of the existing `v1` API.

## Proposal

We propose the creation of an `api-v2` module that implements a graph-first architecture.

### Why a Graph Database?
Lineage is fundamentally a graph problem. Entities like `Datasets`, `Jobs`, and `Runs` are nodes, and the relationships (e.g., `Job` *CONSUMES* `Dataset`, `Run` *PRODUCES* `DatasetVersion`) are edges. Native graph databases are optimized for traversing these relationships (index-free adjacency) without the overhead of heavy relational JOINs, providing constant-time traversals regardless of total dataset size.

### Scale Requirements
The new architecture must support:
* **Multi-tenancy:** 1000s of namespaces with strict isolation.
* **Volume:** Billions of historical runs.
* **Throughput:** Extreme write rates from Autonomous Ingestion Ecosystems (scanners, parsers, BI bots).
* **Read Latency:** Sub-second response times for complex lineage graph queries spanning multiple degrees of separation.

### Framework and Database Options

**Option 1: Spring Boot 3 + Neo4j (The "Spring Latest" Approach)**
* **Pros:** Leverages the robust Spring ecosystem, particularly Spring Data Neo4j, which significantly simplifies graph object mapping (OGM). Excellent community support and modern reactive programming models.
* **Cons:** Requires a complete rewrite of the API layer, abandoning the existing Dropwizard setup.

**Option 2: Dropwizard + Neo4j / PostgreSQL AGE (The Incremental Approach)**
* **Pros:** Reuses existing Dropwizard infrastructure, API definitions, and potentially some of the `marquez_client` integrations. If using Postgres AGE, we can keep the operational simplicity of a single database technology while utilizing Cypher queries over the OpenCypher-compliant AGE extension.
* **Cons:** Less out-of-the-box OGM magic compared to Spring Data Neo4j. Dropwizard's ecosystem is smaller than Spring's. Postgres AGE is still maturing compared to standalone Neo4j.

**Recommendation:** Given the constraints of maintaining backwards compatibility with `v1` and the massive scale requirement, we recommend **Option 2 (Dropwizard + PostgreSQL AGE)** as the initial iteration, with a fallback to a dedicated Neo4j cluster if AGE performance tuning under billions of edges falls short. This minimizes operational overhead (no new DB infrastructure to deploy initially) while unlocking graph query capabilities via OpenCypher.

## Implementation

### 1. Data Model Mapping (Relational to Graph)

The core Marquez OpenLineage model translates cleanly to a property graph. In PostgreSQL AGE, properties are stored natively as `agtype` (a superset of JSONB), allowing us to store arbitrary metadata, OpenLineage custom facets, and run arguments directly on the nodes without rigid schemas.

**Nodes (Labels):**
* `:Source` (Properties: `name`, `type`, `connectionUrl`, `description`)
* `:Namespace` (Properties: `name`, `description`)
* `:Job` (Properties: `name`, `type`, `description`)
* `:JobVersion` (Properties: `uuid`, `version`, `location`, `latestRunUuid`)
* `:Dataset` (Properties: `name`, `type`, `description`)
* `:DatasetVersion` (Properties: `uuid`, `version`, `schema`)
* `:DatasetField` (Properties: `name`, `type`, `description`)
* `:Run` (Properties: `uuid`, `state`, `startedAt`, `endedAt`, `runArgs`, `nominalStartTime`, `nominalEndTime`)
* `:RunState` (Properties: `state`, `transitionedAt`)

**Edges (Relationships):**
* `(:Source)-[:HAS_NAMESPACE]->(:Namespace)`
* `(:Namespace)-[:HAS_JOB]->(:Job)`
* `(:Job)-[:HAS_VERSION]->(:JobVersion)`
* `(:Namespace)-[:HAS_DATASET]->(:Dataset)`
* `(:JobVersion)-[:HAS_RUN]->(:Run)`
* `(:Run)-[:HAS_STATE]->(:RunState)`
* `(:Run)-[:CONSUMES_VERSION]->(:DatasetVersion)`
* `(:Run)-[:PRODUCES_VERSION]->(:DatasetVersion)`
* `(:Dataset)-[:HAS_VERSION]->(:DatasetVersion)`
* `(:DatasetVersion)-[:HAS_FIELD]->(:DatasetField)`

**Column Lineage (Dataset Fields):**
In `v1`, column lineage relies heavily on intermediate tables like `column_lineage` to map input dataset fields to output dataset fields. In the `v2` graph:
* `(:DatasetField)-[:DERIVED_FROM { transformationDescription: "...", transformationType: "..." }]->(:DatasetField)`
* `(:Run)-[:APPLIES_TRANSFORMATION]->(:DatasetField)`

This graph-native approach allows users to perform recursive Cypher graph traversals (`-[:DERIVED_FROM*]-`) on individual columns. A single query can trace a BI dashboard column back to the exact source database column in milliseconds.

**Mapping Auxiliary and Legacy Tables:**
The `v1` schema contains roughly 35 tables. The graph migration dramatically simplifies this footprint by converting many auxiliary tables into native graph properties or relationships:

* **Tables becoming standalone Nodes:**
  * `tags` ➡️ `:Tag` Node (Properties: `name`, `description`).
  * `owners` / `namespace_ownerships` ➡️ `:Owner` Node linked via `(:Namespace)-[:OWNED_BY]->(:Owner)`.
  * `dataset_schemas` / `dataset_schema_versions` ➡️ `:SchemaVersion` Node (Properties: `uuid`, `schema`). Linked via `(:DatasetVersion)-[:HAS_SCHEMA]->(:SchemaVersion)`.

* **Tables becoming Edges (Relationships):**
  * `dataset_fields_tag_mapping`, `datasets_tag_mapping`, `jobs_tag_mapping` ➡️ A unified `(:Node)-[:HAS_TAG]->(:Tag)` edge that can apply to any entity in the graph.
  * `job_versions_io_mapping`, `runs_input_mapping` ➡️ Replaced natively by the `CONSUMES` and `PRODUCES` edges.
  * `dataset_symlinks` ➡️ Natively modeled as `(:Dataset)-[:SYMLINK_TO {isPrimary: true/false}]->(:Dataset)`.

* **Tables collapsed into Node Properties (`agtype` JSONB):**
  * `run_args` ➡️ Nested inside the `:Run` node's `agtype` properties map (`{runArgs: {...}}`).
  * `job_contexts` ➡️ Nested inside the `:JobVersion` node's properties.
  * `dataset_facets`, `job_facets`, `run_facets` ➡️ Stored natively within their respective nodes' `agtype` properties.
  * `lineage_events` ➡️ The raw events will be stored in an append-only JSON/NoSQL event store (e.g., raw table or blob storage) strictly for audit purposes, not traversed for lineage queries.

* **Tables eliminated (No longer needed):**
  * Denormalized query-performance tables (`dataset_denormalized`, `run_lineage_denormalized`, etc.) are completely eliminated because native graph index-free adjacency traversals provide the required performance out-of-the-box without needing complex triggers to maintain flattened pre-computations.

**Handling Complex Properties:**
Instead of scattering metadata across multiple tables, rich OpenLineage facets (e.g., Data Quality metrics, SLA predictions) will be stored directly inside the `agtype` properties map on their respective nodes (`:Run`, `:DatasetVersion`, `:JobVersion`). This enables powerful Cypher queries that can filter graph traversals based on JSON attributes seamlessly.

### 2. Advanced Graph Querying (`aggregateToParentRun`)

In `v1`, collapsing lineage logic via `aggregateToParentRun=true` requires incredibly heavy and complex recursive CTEs. In the `v2` graph:
* We ingest a new edge: `(:Run {id: "child"})-[:HAS_PARENT]->(:Run {id: "parent"})`
* When querying, if `aggregateToParentRun=true`, the Cypher graph traversal naturally filters out `(:Dataset)` nodes that are strictly internal to the child run, and re-routes the `CONSUMES` and `PRODUCES` edges up to the parent `Run` node before returning the path.
* This dramatically speeds up high-level aggregated lineage views.

### 3. Backward Compatibility for V1 Endpoints

A major concern during this migration is: **How will the existing `v1` REST endpoints (e.g., `GET /api/v1/namespaces/{namespace}/datasets`) be supported by the new `v2` graph architecture?**

To maintain strict backwards compatibility without requiring clients to change their APIs:
1. **Resource Controller Delegation:** The existing `v1` HTTP Resource classes (e.g., `DatasetResource.java`, `JobResource.java`) will remain untouched on the surface. However, their underlying service layer will be swapped out via dependency injection to utilize the new `GraphDao` instead of the legacy relational DAOs.
2. **Cypher Translation:** For example, when a user calls the `v1` endpoint to list datasets in a namespace, the `v2` backend will execute a simple Cypher query: `MATCH (n:Namespace {name: $ns})-[:HAS_DATASET]->(d:Dataset) RETURN d` and serialize the result back into the exact same JSON format expected by `v1` clients.

### 4. Testing Strategy (V1 vs V2)

Can we run the exact same `v1` tests on the `v2` codebase?
* **Database/DAO Unit Tests:** **No.** The existing `v1` tests rely heavily on inserting raw relational records using SQL `INSERT INTO ...` and verifying table constraints. Because the underlying storage mechanism is entirely different (Nodes/Edges instead of Tables), these low-level data access tests must be rewritten specifically for Cypher/AGE.
* **API Integration Tests (Blackbox):** **Yes.** The end-to-end API tests (e.g., sending an HTTP OpenLineage payload and asserting the HTTP response of a `GET` request) will remain identical. This ensures that while the internal database engine changes completely, the external API contract remains 100% compliant with existing OpenLineage specifications.

### 5. Scaling Strategy

To handle billions of runs and thousands of namespaces:
1. **Partitioning by Namespace:** In a native graph, we can use Namespace nodes as entry points to subgraph traversals, effectively partitioning the graph logically.
2. **Time-Based Archiving / TTL:** For runs, we will implement time-to-live (TTL) or archive older Run nodes to cold storage, keeping only the "active" or recent lineage in the hot graph, as deep historical run graphs are rarely queried in real-time.
3. **Pre-computed Lineage Facets:** Even in a graph, storing pre-computed aggregations (like `input_uuids` and `output_uuids`) as properties on the `Run` node will speed up API responses that don't require full graph traversal.

### 6. Step-by-Step Execution Plan

1. **Phase 1: Bootstrap `api-v2` Module**
   - Create a new Gradle subproject `api-v2`.
   - Setup Dropwizard (or Spring Boot, pending final architecture decision) configuration.
   - Implement the database driver connection (Neo4j Driver or Postgres AGE JDBC wrapper).
2. **Phase 2: Graph Data Access Layer (DAO)**
   - Implement `v2` DAOs using Cypher queries.
   - Create a dual-write mechanism in the `v1` ingestion path to mirror incoming OpenLineage events to both the relational schema (for `v1` API) and the graph schema (for `v2` API).
3. **Phase 3: `v2` Lineage API**
   - Implement the `GET /api/v2/lineage` endpoint using pure graph traversal queries.
   - Run extensive performance testing comparing `v1` (CTE) vs `v2` (Cypher) latency on graphs with billions of edges.
4. **Phase 4: Migration Tooling**
   - Build a background job to backfill historical data from the `v1` relational tables into the `v2` graph database.

## Next Steps

1. Discuss and finalize the Framework choice: **Spring Boot 3 + Neo4j** vs **Dropwizard + Postgres AGE/Neo4j**.
2. Create prototype branch to benchmark Cypher query performance against a mocked dataset of 1 billion Run nodes using Postgres AGE.

## License Footer

----
SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
