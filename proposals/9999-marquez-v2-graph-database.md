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

**Handling Complex Properties:**
Instead of scattering metadata across multiple tables, rich OpenLineage facets (e.g., Data Quality metrics, SLA predictions) will be stored directly inside the `agtype` properties map on their respective nodes (`:Run`, `:DatasetVersion`, `:JobVersion`). This enables powerful Cypher queries that can filter graph traversals based on JSON attributes seamlessly.

### 2. Scaling Strategy

To handle billions of runs and thousands of namespaces:
1. **Partitioning by Namespace:** In a native graph, we can use Namespace nodes as entry points to subgraph traversals, effectively partitioning the graph logically.
2. **Time-Based Archiving / TTL:** For runs, we will implement time-to-live (TTL) or archive older Run nodes to cold storage, keeping only the "active" or recent lineage in the hot graph, as deep historical run graphs are rarely queried in real-time.
3. **Pre-computed Lineage Facets:** Even in a graph, storing pre-computed aggregations (like `input_uuids` and `output_uuids`) as properties on the `Run` node will speed up API responses that don't require full graph traversal.

### 3. Step-by-Step Execution Plan

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
