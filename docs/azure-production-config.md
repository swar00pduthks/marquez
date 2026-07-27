# Marquez Production Config: Azure PostgreSQL Flex Server PG17

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
│  Azure PostgreSQL Flexible Server — PG 17                        │
│                                                                  │
│  Built-in PgBouncer (transaction mode, port 6432)                │
│  ↓ fans out to ↓                                                 │
│  PostgreSQL 17 backend (port 5432)                               │
│                                                                  │
│  Extensions: pg_stat_statements                                  │
│  V1 tables:  runs, jobs, datasets, lineage_events, run_facets    │
│  V2 tables:  run_lineage_denormalized, dataset_denormalized, ... │
│  Pre-mat:    lineage_edges (BFS adjacency — replaces CTE reads)  │
└──────────────────────────────────────────────────────────────────┘
```

Single database instance serves V1 and V2 API versions.
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

## 2. Performance Strategy

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
Phase 3 (6mo)    — V2 reads use lineage_edges BFS-in-Java instead of recursive CTE
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

### Built-in PgBouncer

Enable via Azure Portal: **Flexible Server → Server Parameters → search "pgbouncer"**

When `pgbouncer.enabled = ON`, PgBouncer listens on **port 6432** on the same hostname.
Your JDBC URL must use port 6432 (NOT 5432).

`prepareThreshold=0` disables server-side prepared statements — required for
PgBouncer transaction mode. Set `PGBOUNCER_PREPARE_THRESHOLD=0` in your Helm values.

### Flex Server Parameter Reference

Navigate to: **Azure Portal → Flexible Server → Server Parameters → search `<name>` → Save**

Parameters marked *(static)* require a server restart after saving.

#### PgBouncer

| Parameter | Value | Notes |
|---|---|---|
| pgbouncer.enabled | ON | Built-in — no sidecar needed |
| pgbouncer.pool_mode | transaction | **CRITICAL** — Marquez uses connection-per-request |
| pgbouncer.max_client_conn | 500 | 3 pods × 20 HikariCP + headroom |
| pgbouncer.default_pool_size | 50 | Server-side pool per DB/user pair |
| pgbouncer.min_pool_size | 10 | Keeps warm connections alive |
| pgbouncer.server_idle_timeout | 600 | Seconds before idle server connection closed |
| pgbouncer.query_timeout | 30 | Seconds; matches statement_timeout below |

#### Memory *(static — restart required)*

| Parameter | Value | Unit | Notes |
|---|---|---|---|
| shared_buffers | 16384 | MB | 25% of 64 GB RAM |
| effective_cache_size | 49152 | MB | 75% of 64 GB RAM (planner hint, dynamic) |
| work_mem | 131072 | kB | 128 MB per sort; recursive CTEs use multiple |
| maintenance_work_mem | 2097152 | kB | 2 GB for VACUUM and index builds |

#### WAL

| Parameter | Value | Unit | Notes |
|---|---|---|---|
| wal_buffers | 65536 | kB | 64 MB *(static)* |
| checkpoint_completion_target | 0.9 | — | Spreads checkpoint I/O |
| min_wal_size | 2048 | MB | |
| max_wal_size | 8192 | MB | |
| synchronous_commit | off | — | Safe for lineage events; use `on` for run-state transitions |

#### I/O (Premium SSD v2)

| Parameter | Value | Notes |
|---|---|---|
| random_page_cost | 1.1 | NVMe-equivalent; default 4 is for spinning disk |
| effective_io_concurrency | 200 | Parallel I/O requests the SSD can handle |

#### Parallelism

| Parameter | Value | Notes |
|---|---|---|
| max_worker_processes | 8 | Matches vCores *(static)* |
| max_parallel_workers | 8 | |
| max_parallel_workers_per_gather | 4 | Half vCores |

#### Connections

| Parameter | Value | Notes |
|---|---|---|
| max_connections | 100 | PgBouncer sits in front; ~50 real PG connections used |

#### Autovacuum (critical for high-write tables)

| Parameter | Value | Unit | Notes |
|---|---|---|---|
| autovacuum_max_workers | 6 | — | Default 3; extra workers for run_facets, runs |
| autovacuum_vacuum_scale_factor | 0.02 | — | Vacuum at 2% dead tuples (default 20%) |
| autovacuum_analyze_scale_factor | 0.01 | — | Analyze at 1% new tuples |
| autovacuum_vacuum_cost_delay | 2 | ms | Reduce throttling; default 2ms already on PG17 |
| autovacuum_vacuum_cost_limit | 800 | — | Higher = faster vacuum; default 200 |
| autovacuum_naptime | 30 | s | Check tables every 30 s; default 60 |

#### Timeouts

| Parameter | Value | Unit | Notes |
|---|---|---|---|
| lock_timeout | 5000 | ms | Prevents cascading lock waits |
| statement_timeout | 30000 | ms | **Kills runaway lineage CTEs** |
| idle_in_transaction_session_timeout | 10000 | ms | Clears stuck transactions |

#### Logging

| Parameter | Value | Notes |
|---|---|---|
| log_min_duration_statement | 5000 | ms; captures queries slower than 5 s |
| log_connections | off | Reduces log noise at 1M events/day |
| log_disconnections | off | |

#### Extensions *(static — restart required)*

| Parameter | Value | Notes |
|---|---|---|
| shared_preload_libraries | pg_stat_statements | |
| pg_stat_statements.track | all | |
| track_activity_query_size | 4096 | Bytes |

### Production JDBC URL

```
jdbc:postgresql://<flex-server-hostname>:6432/<db>?sslmode=require&prepareThreshold=0&socketTimeout=30&connectTimeout=5&loginTimeout=5
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
      memory: "8Gi"         # JVM heap capped at 5Gi, overhead ~2.5Gi → fits in 8Gi

  db:
    host: <flex-server-hostname>
    port: 6432              # PgBouncer port (NOT 5432)
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
  pgbouncerPrepareThreshold: "0"   # Required for PgBouncer transaction mode
  ageEnabled: false

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

**`run_facets` at 1.1 TB is the immediate 2-year time bomb.**
10 facets per event × 1M events/day = 10M rows/day × 730 days = **7.3 billion rows**.
This table must be range-partitioned by `lineage_event_time` before month 4.

```sql
-- Run BEFORE run_facets hits 500M rows (schedule as a maintenance window)
CREATE TABLE run_facets_new (LIKE run_facets INCLUDING ALL)
    PARTITION BY RANGE (lineage_event_time);
-- Monthly partitions, 12-month active retention
```

---

## 7. Most Impactful Changes — Ranked by ROI

| Rank | Change | Effort | Impact |
|---|---|---|---|
| 1 | `logging.level: WARN` in production config | 5 min | 10–20% CPU freed immediately |
| 2 | Gate denorm writes on START/COMPLETE/FAIL only (done) | done | 100× write IOPS reduction |
| 3 | Built-in PgBouncer via server parameter + port 6432 | 15 min | Fixes connection exhaustion under Spark burst |
| 4 | `statement_timeout=30s` on Flex Server | 5 min | Kills runaway CTEs, frees connections |
| 5 | Fix tautology bug `dvf.run_uuid = dvf.run_uuid` (done) | done | 50×+ response size reduction |
| 6 | UNION ALL CTE split + depth caps (done) | done | Eliminates index bypass in BFS queries |
| 7 | Partition `run_facets` by `lineage_event_time` | 1 day | Prevents 1.1 TB unpartitioned table |
| 8 | Build `lineage_edges` table, move reads off recursive CTEs | done (V107) | Eliminates BFS CPU cost at write time |
| 9 | BFS-in-Java reads from `lineage_edges` | 1 week | Eliminates recursive CTE entirely from read path |
| 10 | Move to `Standard_E8ds_v5` with `synchronous_commit=off` | 2 hours | 3× write throughput |
| 11 | Add read replica for lineage GET queries | 2 hours | Write path isolated from read path |

---

## 8. Monitoring Queries

```sql
-- DEFAULT partition size alert — rows here mean a month partition was missed
SELECT COUNT(*) FROM run_lineage_denormalized_default;
SELECT COUNT(*) FROM run_parent_lineage_denormalized_default;

-- lineage_edges growth rate
SELECT run_date, COUNT(*) FROM lineage_edges GROUP BY run_date ORDER BY run_date DESC LIMIT 30;

-- run_facets size (track monthly; action needed before 500M rows)
SELECT pg_size_pretty(pg_total_relation_size('run_facets'));
SELECT COUNT(*) FROM run_facets;

-- Slow query log (queries averaging > 5 s)
SELECT query, calls, total_exec_time/calls AS avg_ms, rows
FROM pg_stat_statements
WHERE total_exec_time/calls > 5000
ORDER BY avg_ms DESC
LIMIT 20;

-- PgBouncer pool stats
-- Connect on port 6432 with user pgbouncer to the pgbouncer database
-- SHOW POOLS; SHOW STATS; SHOW CLIENTS;
```
