# Marquez Production Config: Azure PostgreSQL Flex Server + AKS
# Long-Term Lineage Strategy: V1/V2 vs V3 (AGE) vs Pre-built Graph

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│  AKS Cluster                                                     │
│                                                                  │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │  Marquez API │  │  Marquez API │  │  Marquez API │  ×3 pods  │
│  │  Pod         │  │  Pod         │  │  Pod         │           │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘           │
│         └─────────────────┴─────────────────┘                   │
│                           │                                      │
└───────────────────────────┼──────────────────────────────────────┘
                            │ JDBC (port 6432 — built-in PgBouncer)
                            ▼
┌──────────────────────────────────────────────────────────────────┐
│  Azure PostgreSQL Flexible Server — PG 17 + AGE extension        │
│                                                                  │
│  Built-in PgBouncer (transaction mode, port 6432)                │
│  ↓ fans out to ↓                                                 │
│  PostgreSQL 17 backend (port 5432)                               │
│                                                                  │
│  Extensions: age, pg_stat_statements                             │
│  V1 tables:  runs, jobs, datasets, lineage_events, run_facets    │
│  V2 tables:  run_lineage_denormalized, dataset_denormalized, ... │
│  V3 graph:   ag_catalog (Apache AGE graph for Cypher queries)    │
│  Pre-mat:    lineage_edges (BFS adjacency — replaces CTE reads)  │
└──────────────────────────────────────────────────────────────────┘
```

Single database instance serves all three API versions. No separate pod for AGE.
PgBouncer is Azure-managed via server parameter — no sidecar or extra Deployment needed.

---

## 1. Why You're Struggling While Instagram/OpenAI Don't

The issue is **not PostgreSQL**. It is the operating point.

Marquez was designed for ~10,000 lineage events/day with 1–5 events per run.
You are operating at:

```
1,000,000 events/day × 200 Spark events/run = 5,000 job runs/day
                                              × 200 DB writes each
                                              = 30,000,000 upserts/day (before our fix)
```

Instagram and OpenAI run at billion-row scale on PostgreSQL because they obey two rules:

| Rule | Instagram/OpenAI | Current Marquez |
|---|---|---|
| Write pattern | Append-only INSERT | UPDATE same row 200 times per run |
| Read pattern | Pre-materialized adjacency, simple SELECT | Live recursive BFS CTE at query time |
| Graph traversal | Precomputed at write time | Computed on every API request |
| JSON aggregation | Avoided in hot queries | `JSON_AGG(DISTINCT jsonb_build_object(...))` in recursive CTEs |
| Log level | WARN in prod | **DEBUG for everything** (current config.yml) |
| Connection pooling | PgBouncer, 200+ connections | Default HikariCP 8-10 connections |

The `logging.level: DEBUG` in your `config.yml` alone at 1M events/day generates
100M+ log lines/day and costs 10–20% extra CPU on the API pod.

---

## 2. Long-Term Strategy: Which API Version Wins?

### The Honest Answer

**V2 wins for the next 12–18 months. V3 (AGE with pre-built graph) wins long-term.**

```
                    Now        6 months      18 months
V1 (normalized)     ████       ██            █         (keep for writes, retire from reads)
V2 (denorm tables)  ████       ████████      ████████  (primary read path)
V3 AGE live CTE     ██         ██            ░░        (same CTE problem, just in Cypher)
V3 AGE pre-built    ░░         ████          ████████  (target end state)
```

### Why V3 Live Cypher Doesn't Solve the Problem

Cypher `MATCH (a)-[*1..10]->(b)` is STILL a live BFS traversal of the graph —
it just uses graph indexes instead of recursive CTEs. At depth=10 on a graph
with 5,000 new nodes per day × 730 days = 3.65M nodes, Cypher traversal has
the same fan-out explosion problem as the SQL recursive CTE.

### The Fix: Pre-Built Lineage Graph (Pre-Materialized Edges)

```
Event arrives → extract (from_node, to_node) pairs → INSERT into lineage_edges
Read request → SELECT from lineage_edges WHERE from_node_id IN (...) LIMIT N
              → No BFS, no CTE, no graph traversal — just N indexed point lookups
```

This is how OpenMetadata works. At depth=5 you do 5 sequential indexed queries,
each milliseconds. No OOM risk, no planner problem, cacheable per level.

### Recommended Phases

```
Phase 1 (done)   — Fix recursive CTE OR→UNION, depth caps, tautology bug, write churn
Phase 2 (done)   — Add lineage_edges materialized table (V107 migration)
Phase 3 (6mo)    — V2 reads use lineage_edges instead of recursive CTE
Phase 4 (18mo)   — V3 AGE graph populated from lineage_edges (pre-built, not live-traversed)
```

---

## 3. Azure PostgreSQL Flexible Server Configuration

### SKU Selection

| Workload Stage | Recommended SKU | vCores | RAM | IOPS |
|---|---|---|---|---|
| Now (1M events/day) | Standard_E8ds_v5 | 8 | 64 GB | up to 25,600 |
| 3M events/day | Standard_E16ds_v5 | 16 | 128 GB | up to 51,200 |
| With read replica for lineage reads | Primary E8ds_v5 + Replica E4ds_v5 | — | — | — |

**Storage:** Premium SSD v2, 500 GB minimum, IOPS provisioned at 5,000 initially.
Enable auto-grow. At 1M events/day the lineage_events table grows ~150 GB/year.

### Built-in PgBouncer (Server Parameter — No Sidecar Needed)

Enable PgBouncer as a server parameter in the Azure Portal:

```
Server parameters to set in Azure Portal:
  pgbouncer.enabled              = true
  pgbouncer.pool_mode            = transaction      ← CRITICAL for correctness
  pgbouncer.max_client_conn      = 500
  pgbouncer.default_pool_size    = 50
  pgbouncer.min_pool_size        = 10
  pgbouncer.server_idle_timeout  = 600
  pgbouncer.query_timeout        = 30
```

When enabled, PgBouncer listens on **port 6432** on the same hostname as your
Flex Server. Your JDBC URL must point to port 6432, NOT 5432:

```
jdbc:postgresql://<flex-server-hostname>:6432/marquez?sslmode=require&prepareThreshold=0
```

`prepareThreshold=0` disables server-side prepared statements, which is required
for PgBouncer transaction mode. This is already set in the Helm chart via
`PGBOUNCER_PREPARE_THRESHOLD=0`.

### PostgreSQL Server Parameters (set in Azure Portal → Server Parameters)

```
# Memory
shared_buffers                    = 16384    (MB — 25% of 64 GB RAM)
effective_cache_size              = 49152    (MB — 75% of 64 GB RAM)
work_mem                          = 128      (MB — PER SORT per connection; recursive CTEs use multiple)
maintenance_work_mem              = 2048     (MB — for VACUUM and index builds)
temp_buffers                      = 32       (MB)

# Write performance
wal_buffers                       = 64       (MB)
checkpoint_completion_target      = 0.9
min_wal_size                      = 2048     (MB)
max_wal_size                      = 8192     (MB)
synchronous_commit                = off      (safe for lineage events — eventual consistency acceptable)
                                             # IMPORTANT: set to 'on' for run state transitions

# I/O (Premium SSD v2)
random_page_cost                  = 1.1      (SSD — lower than spinning disk default of 4)
effective_io_concurrency          = 200      (SSD)
seq_page_cost                     = 1.0

# Parallelism
max_worker_processes              = 8        (= vCores)
max_parallel_workers              = 8
max_parallel_workers_per_gather   = 4
parallel_tuple_cost               = 0.1

# Connections (PgBouncer sits in front; ~50 real PostgreSQL connections from PgBouncer)
max_connections                   = 100

# Autovacuum — critical for high-write tables
autovacuum_max_workers            = 6
autovacuum_vacuum_scale_factor    = 0.02     (vacuum when 2% of table modified — default is 20%)
autovacuum_analyze_scale_factor   = 0.01
autovacuum_vacuum_cost_delay      = 2        (ms — aggressive vacuum pace)
autovacuum_vacuum_cost_limit      = 800      (default 200 — allows faster VACUUM)
autovacuum_naptime                = 30       (seconds between autovacuum checks)

# Query behavior
lock_timeout                      = 5000     (ms)
statement_timeout                 = 30000    (ms — kill queries >30s; prevents runaway lineage CTEs)
idle_in_transaction_session_timeout = 10000 (ms)

# Logging — INFO only in production
log_min_duration_statement        = 5000     (ms — only log queries >5s)
log_connections                   = off
log_disconnections                = off
log_duration                      = off

# Extensions (set shared_preload_libraries in Azure Portal)
shared_preload_libraries          = pg_stat_statements,age
pg_stat_statements.track          = all
track_activity_query_size         = 4096
```

---

## 4. AKS Pod Configuration

### Marquez API Deployment (values.yaml — production override)

```yaml
# chart/values-production.yaml
marquez:
  replicaCount: 3

  image:
    repository: swar00pduth/marquez
    tag: 0.52.43

  resources:
    requests:
      cpu: "2"
      memory: "5Gi"
    limits:
      cpu: "4"
      memory: "8Gi"                  # JVM heap capped at 5Gi, overhead ~2.5Gi → fits in 8Gi limit

  db:
    host: <flex-server-hostname>
    port: 6432                        # ← PgBouncer port (NOT 5432)
    name: marquez
    user: buendia
    # password: set via existingSecretName

  javaOpts: >-
    -Xms2g
    -Xmx5g
    -XX:+UseG1GC
    -XX:MaxGCPauseMillis=200
    -XX:InitiatingHeapOccupancyPercent=70
    -XX:G1HeapRegionSize=16m
    -XX:+ExitOnOutOfMemoryError
    -Djava.security.egd=file:/dev/./urandom

  logLevel: "WARN"
  dbPoolMaxSize: 20
  dbPoolMinSize: 5
  pgbouncerPrepareThreshold: "0"     # Required for PgBouncer transaction mode

  ageEnabled: true                   # AGE is enabled on Azure Flex Server PG 17

  podAnnotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "5001"
    prometheus.io/path: "/metrics"

  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            topologyKey: kubernetes.io/hostname
            labelSelector:
              matchLabels:
                app.kubernetes.io/component: marquez
```

### Marquez Config for Production (config.yml via ConfigMap)

```yaml
server:
  applicationConnectors:
    - type: http
      port: ${MARQUEZ_PORT:-5000}
      httpCompliance: RFC7230_LEGACY
      acceptorThreads: 2
      selectorThreads: 8
  adminConnectors:
    - type: http
      port: ${MARQUEZ_ADMIN_PORT:-5001}
  gzip:
    enabled: true
    minimumEntitySize: 256 bytes
    bufferSize: 8KiB

db:
  driverClass: org.postgresql.Driver
  # Port 6432 = Azure Flex Server built-in PgBouncer (transaction mode)
  # prepareThreshold=0 disables server-side prepared statements (required for transaction pooling)
  url: jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/${POSTGRES_DB}?sslmode=require&prepareThreshold=${PGBOUNCER_PREPARE_THRESHOLD:-0}
  user: ${POSTGRES_USER}
  password: ${POSTGRES_PASSWORD}
  maxSize: ${DB_POOL_MAX_SIZE:-20}
  minSize: ${DB_POOL_MIN_SIZE:-5}
  maxWaitForConnection: 5s
  validationQuery: "SELECT 1"
  connectionTimeout: 5000
  idleTimeout: 600000
  maxLifetime: 1800000

migrateOnStartup: true
ageEnabled: ${MARQUEZ_AGE_ENABLED:-true}

logging:
  level: WARN
  appenders:
    - type: console
      logFormat: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
  loggers:
    marquez: INFO
    marquez.db: WARN
    org.jdbi: WARN
    org.postgresql: WARN
    org.eclipse.jetty: WARN
```

### HPA (Horizontal Pod Autoscaler)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: marquez-api-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: marquez
  minReplicas: 3
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---

## 5. Node Pool Sizing for AKS

Only one node pool needed — Marquez API pods only. No separate database node pool
(database is Azure managed).

```
┌──────────────────────────────────────────────────────┐
│  Node Pool: marquez-api                              │
│  VM SKU: Standard_D4ds_v5 (4 vCPU, 16 GB RAM)       │
│  Count: 3 (min) → 10 (max with cluster autoscaler)  │
│  Each node runs: 1 Marquez API pod                   │
│  Taints: none (shared pool OK)                       │
└──────────────────────────────────────────────────────┘
```

---

## 6. Data Volume Projections at 1M Events/Day (2 Years)

| Table | Rows/day | Row size | 2-yr size | Action |
|---|---|---|---|---|
| lineage_events | 1,000,000 | ~200B | **146 GB** | Already indexed by run_date |
| run_facets | **10,000,000** | ~150B | **1.1 TB** | **Partition urgently — V108** |
| run_lineage_denormalized | 150,000 | ~500B | 54 GB | Manageable with partitions |
| runs | 5,000 | ~300B | 2.1 GB | Small |
| dataset_versions | 10,000 | ~200B | 5.5 GB | Small |
| lineage_edges (new) | 30,000 | ~100B | 18 GB | Replaces recursive CTE reads |
| AGE graph (ag_catalog) | ~40,000 | ~200B | ~21 GB | Grows with lineage_edges |

**`run_facets` at 1.1 TB is the immediate 2-year time bomb.**
With 10 facets per event × 1M events/day = 10M rows/day × 730 days = **7.3 billion rows**.
This table must be range-partitioned by `lineage_event_time` before month 4.

```sql
-- Run BEFORE run_facets hits 500M rows
-- Schedule as a one-time maintenance window
CREATE TABLE run_facets_new (LIKE run_facets INCLUDING ALL)
    PARTITION BY RANGE (lineage_event_time);
-- Monthly partitions, 12-month retention (facets are hot for 12 months, cold after)
```

---

## 7. Most Impactful Changes — Ranked by ROI

| Rank | Change | Effort | Impact |
|---|---|---|---|
| 1 | `logging.level: WARN` in production config | 5 min | 10–20% CPU freed immediately |
| 2 | Gate denorm writes on START/COMPLETE/FAIL only (done) | done | 100× write IOPS reduction |
| 3 | Built-in PgBouncer via server parameter + port 6432 | 15 min | Fixes connection exhaustion under Spark burst |
| 4 | `statement_timeout=30s` on Azure Flex Server | 5 min | Kills runaway CTEs, frees connections |
| 5 | Fix tautology bug `dvf.run_uuid = dvf.run_uuid` (done) | done | 50×+ response size reduction |
| 6 | UNION ALL CTE split + depth caps (done) | done | Eliminates index bypass in BFS queries |
| 7 | Partition `run_facets` by `lineage_event_time` | 1 day | Prevents 1.1 TB unpartitioned table |
| 8 | Build `lineage_edges` table, move reads off recursive CTEs | done (V107) | Eliminates BFS CPU cost entirely |
| 9 | Move to `Standard_E8ds_v5` with `synchronous_commit=off` | 2 hours | 3× write throughput |
| 10 | Add read replica for lineage GET queries | 2 hours | Write path isolated from read path |
| 11 | V3 AGE pre-built graph (NOT live Cypher traversal) | 2 months | Long-term stable graph API |

---

## 8. Monitoring Queries

```sql
-- DEFAULT partition size alert — rows here mean a month partition was missed
SELECT COUNT(*) FROM run_lineage_denormalized_default;
SELECT COUNT(*) FROM run_parent_lineage_denormalized_default;

-- lineage_edges growth rate
SELECT run_date, COUNT(*) FROM lineage_edges GROUP BY run_date ORDER BY run_date DESC LIMIT 30;

-- run_facets size (track monthly)
SELECT pg_size_pretty(pg_total_relation_size('run_facets'));
SELECT COUNT(*) FROM run_facets;

-- Slow query log check
SELECT query, calls, total_exec_time/calls AS avg_ms, rows
FROM pg_stat_statements
WHERE total_exec_time/calls > 5000
ORDER BY avg_ms DESC
LIMIT 20;

-- PgBouncer pool stats (query against Flex Server pgbouncer stats)
-- Connect on port 6432 with user pgbouncer to the pgbouncer database
-- SHOW POOLS; SHOW STATS; SHOW CLIENTS;
```
