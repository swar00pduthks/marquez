# Marquez Production Config: Azure PostgreSQL Flex Server + AKS
# Long-Term Lineage Strategy: V1/V2 vs V3 (AGE) vs Pre-built Graph

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

Here is the breakdown:

```
                    Now        6 months      18 months
V1 (normalized)     ████       ██            █         (keep for writes, retire from reads)
V2 (denorm tables)  ████       ████████      ████████  (primary read path)
V3 AGE live CTE     ██         ██            ░░        (same CTE problem, just in Cypher)
V3 AGE pre-built    ░░         ████          ████████  (target end state)
```

### Why V3 Live Cypher Doesn't Solve the Problem

The current V3 implementation:
```
Event arrives → write to relational + write to AGE graph → read via Cypher MATCH traversal
```

Cypher `MATCH (a)-[*1..10]->(b)` is STILL a live BFS traversal of the graph —
it just uses graph indexes instead of recursive CTEs. At depth=10 on a graph
with 5,000 new nodes per day × 730 days = 3.65M nodes, Cypher traversal has
the same fan-out explosion problem as the SQL recursive CTE.

### The Fix: Pre-Built Lineage Graph (Pre-Materialized Edges)

The correct V3 design computes all reachable edges **at write time**, not at read time.

```
Event arrives → extract (from_node, to_node) pairs → INSERT into lineage_edges
Read request → SELECT from lineage_edges WHERE from_node_id IN (...) LIMIT N
              → No BFS, no CTE, no graph traversal — just N indexed point lookups
```

This is how OpenMetadata works. They store explicit upstream/downstream entity
relationships as rows, not as a graph to be traversed. For depth=5 you do
5 sequential indexed queries, each milliseconds.

### OpenMetadata vs Marquez Lineage Design

| Aspect | OpenMetadata | Marquez V1/V2 | Marquez V3 Target |
|---|---|---|---|
| Storage | Edge table (entity_relationship) | Recursive normalized tables | AGE graph pre-built |
| Read depth=1 | 1 indexed SELECT | 1 recursive CTE step | 1 AGE hop |
| Read depth=5 | 5 indexed SELECTs in Java | 1 CTE (5 recursions, expensive) | 5 AGE hops (fast with indexes) |
| Read depth=10 | 10 indexed SELECTs | 1 CTE (10 recursions, can OOM) | NOT recommended |
| Write pattern | 1 INSERT per edge on COMPLETE | Upsert on every event (200×) | 1 INSERT per edge on COMPLETE |
| BFS location | Application code (Java) | PostgreSQL planner | AGE engine |
| Cache-friendly | Yes (each level cacheable) | No (full graph per request) | Partially |
| Scale ceiling | ~100M edges | ~50M rows before pain | ~1B nodes/edges |

**For your workload, the OpenMetadata pattern is pragmatic and proven.**
The Marquez V3 end-state (AGE with pre-built edges, not live traversal) achieves the same thing but with richer graph query capability (shortest path, impact analysis, etc.).

### Recommended Phases

```
Phase 1 (done)   — Fix recursive CTE OR→UNION, depth caps, tautology bug, write churn
Phase 2 (now)    — Add lineage_edges materialized table (see below)
Phase 3 (6mo)    — V2 reads use lineage_edges instead of recursive CTE
Phase 4 (18mo)   — V3 AGE graph populated from lineage_edges (pre-built, not live-traversed)
```

### The lineage_edges Table (Phase 2)

```sql
-- Pre-computed adjacency table populated at event COMPLETE time only
-- No recursive CTE needed for reads — just N simple indexed lookups
CREATE TABLE lineage_edges (
    from_node_id   UUID     NOT NULL,
    from_type      TEXT     NOT NULL,  -- 'dataset_version', 'run', 'job'
    to_node_id     UUID     NOT NULL,
    to_type        TEXT     NOT NULL,
    edge_type      TEXT     NOT NULL,  -- 'PRODUCES', 'CONSUMES'
    run_uuid       UUID     NOT NULL,
    run_date       DATE     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (from_node_id, to_node_id, edge_type)
) PARTITION BY RANGE (run_date);

-- Two directional indexes replace all recursive CTE traversal
CREATE INDEX idx_lineage_edges_upstream
    ON lineage_edges (to_node_id, from_type, run_date);
CREATE INDEX idx_lineage_edges_downstream
    ON lineage_edges (from_node_id, to_type, run_date);
```

At **depth=5** the read path becomes:
```java
// Java BFS — 5 indexed SELECT calls, each sub-millisecond
Set<UUID> frontier = Set.of(startNodeId);
for (int d = 0; d < depth; d++) {
    Set<UUID> next = dao.getDirectNeighbors(frontier);  // single indexed SELECT
    result.addAll(next);
    frontier = next;
}
```

This matches OpenMetadata's approach exactly. No recursive CTE, no OOM risk,
no depth-related performance cliff, cacheable per level.

---

## 3. Azure PostgreSQL Flexible Server Configuration

### SKU Selection

**Do NOT use Azure PostgreSQL Flexible Server for V3 (AGE).**
Azure does not allow custom extensions like AGE. Use Flex Server for V1/V2 only.

| Workload Stage | Recommended SKU | vCores | RAM | IOPS |
|---|---|---|---|---|
| Now (1M events/day, V1/V2) | Standard_E8ds_v5 | 8 | 64 GB | up to 25,600 |
| 3M events/day | Standard_E16ds_v5 | 16 | 128 GB | up to 51,200 |
| With read replica for lineage reads | Primary E8ds_v5 + Replica E4ds_v5 | — | — | — |

**Storage:** Premium SSD v2, 500 GB minimum, IOPS provisioned at 5,000 initially.
Enable auto-grow. At 1M events/day the lineage_events table grows ~150 GB/year.

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

# Connections
max_connections                   = 200      (PgBouncer sits in front; real backend connections ~80)

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

# Extensions
shared_preload_libraries          = pg_stat_statements
pg_stat_statements.track          = all
track_activity_query_size         = 4096
```

### Connection Pooling: PgBouncer on AKS

Run PgBouncer as a sidecar or dedicated Deployment. Use **transaction pooling**.

```ini
# pgbouncer.ini
[databases]
marquez = host=<azure-flex-hostname> port=5432 dbname=marquez

[pgbouncer]
pool_mode = transaction
max_client_conn = 500          ; max Marquez API connections across all pods
default_pool_size = 50         ; PostgreSQL backend connections per database
min_pool_size = 10
reserve_pool_size = 10
reserve_pool_timeout = 3       ; seconds before using reserve pool
server_idle_timeout = 600
server_lifetime = 3600
server_connect_timeout = 10
client_idle_timeout = 60
query_timeout = 30             ; kill client query after 30s — matches PG statement_timeout
stats_period = 60

# Authentication
auth_type = scram-sha-256
auth_file = /etc/pgbouncer/userlist.txt
```

---

## 4. AKS Pod Configuration

### Marquez API Deployment (values.yaml — production override)

```yaml
# chart/values-production.yaml
marquez:
  replicaCount: 3                    # Minimum for production HA; add HPA for burst

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

  # Pass JVM flags via env (add to deployment.yaml)
  # JAVA_OPTS set in extraEnv below
  extraEnv:
    - name: JAVA_OPTS
      value: >-
        -Xms2g
        -Xmx5g
        -XX:+UseG1GC
        -XX:MaxGCPauseMillis=200
        -XX:InitiatingHeapOccupancyPercent=70
        -XX:G1HeapRegionSize=16m
        -XX:+ExitOnOutOfMemoryError
        -Djava.security.egd=file:/dev/./urandom
        -Dfile.encoding=UTF-8
    - name: LOG_LEVEL
      value: "WARN"                  # CRITICAL: DEBUG at 1M events/day = 100M log lines/day

  podAnnotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "5001"
    prometheus.io/path: "/metrics"

  # Anti-affinity: spread API pods across AKS nodes
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
      # Jetty thread pool — size to handle burst from 5,000 Spark jobs
      acceptorThreads: 2
      selectorThreads: 8
  adminConnectors:
    - type: http
      port: ${MARQUEZ_ADMIN_PORT:-5001}
  gzip:
    enabled: true
    minimumEntitySize: 256 bytes     # Compress all lineage responses >256B
    bufferSize: 8KiB

db:
  driverClass: org.postgresql.Driver
  url: jdbc:postgresql://${PGBOUNCER_HOST}:5432/marquez?sslmode=require&prepareThreshold=0
  #                      ^^^^^^^^^^^^^^^^ — point at PgBouncer, NOT Flex Server directly
  #                                       prepareThreshold=0 required for PgBouncer transaction mode
  user: ${POSTGRES_USER}
  password: ${POSTGRES_PASSWORD}

  # HikariCP pool — talk to PgBouncer, so pool can be larger
  maxSize: 30                        # PgBouncer handles the real PG connection limit
  minSize: 10
  maxWaitForConnection: 5s
  validationQuery: "SELECT 1"
  connectionTimeout: 5000
  idleTimeout: 600000
  maxLifetime: 1800000
  # Disable server-side prepared statements — required for PgBouncer transaction mode
  initializationQuery: "SET search_path=public"

migrateOnStartup: true
ageEnabled: ${MARQUEZ_AGE_ENABLED:-false}  # false for Flex Server (no AGE support)

logging:
  level: WARN                        # NOT DEBUG — this is the most impactful single change
  appenders:
    - type: console
      logFormat: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
  loggers:
    marquez: INFO                    # Marquez service-level events (starts, errors)
    marquez.db: WARN                 # SQL only on warnings — DEBUG is 100M lines/day at 1M events
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

### AGE Pod (V3 only — separate StatefulSet, NOT on Azure Flex Server)

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres-age
spec:
  serviceName: postgres-age
  replicas: 1
  selector:
    matchLabels:
      app: postgres-age
  template:
    spec:
      containers:
        - name: postgres-age
          image: swar00pduth/postgres-age:14-latest
          resources:
            requests:
              cpu: "2"
              memory: "16Gi"         # AGE graph in-memory traversal needs RAM
            limits:
              cpu: "4"
              memory: "32Gi"
          env:
            - name: POSTGRES_DB
              value: marquez_graph
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
            - name: POSTGRES_SHARED_BUFFERS
              value: "8GB"
            - name: POSTGRES_WORK_MEM
              value: "256MB"         # AGE graph queries need more work_mem than relational
          args:
            - postgres
            - -c
            - shared_buffers=8GB
            - -c
            - work_mem=256MB
            - -c
            - effective_cache_size=24GB
            - -c
            - max_connections=50    # Only Marquez V3 pods talk to this
            - -c
            - shared_preload_libraries=age,pg_stat_statements
            - -c
            - statement_timeout=15000
          volumeMounts:
            - name: postgres-age-data
              mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:
    - metadata:
        name: postgres-age-data
      spec:
        accessModes: [ReadWriteOnce]
        storageClassName: managed-premium  # Azure Premium SSD
        resources:
          requests:
            storage: 200Gi           # Graph only — much smaller than relational store
```

---

## 5. Node Pool Sizing for AKS

```
┌──────────────────────────────────────────────────────┐
│  Node Pool: marquez-api                              │
│  VM SKU: Standard_D4ds_v5 (4 vCPU, 16 GB RAM)       │
│  Count: 3 (min) → 8 (max with cluster autoscaler)   │
│  Each node runs: 1 Marquez API pod + PgBouncer       │
│  Taints: none (shared pool OK)                       │
└──────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│  Node Pool: postgres-age                             │
│  VM SKU: Standard_E8s_v5 (8 vCPU, 64 GB RAM)        │
│  Count: 1 (StatefulSet — no horizontal scale for PG) │
│  Taints: dedicated=postgres-age:NoSchedule           │
│  Tolerations: on AGE StatefulSet only                │
└──────────────────────────────────────────────────────┘
```

---

## 6. Data Volume Projections at 1M Events/Day (2 Years)

| Table | Row/day | Row size | 2-yr size | Action |
|---|---|---|---|---|
| lineage_events | 1,000,000 | ~200B | **146 GB** | Already indexed by run_date |
| run_facets | **10,000,000** | ~150B | **1.1 TB** | **Partition urgently — V107** |
| run_lineage_denormalized | 150,000 | ~500B | 54 GB | Manageable with partitions |
| runs | 5,000 | ~300B | 2.1 GB | Small |
| dataset_versions | 10,000 | ~200B | 5.5 GB | Small |
| lineage_edges (new) | 30,000 | ~100B | 18 GB | Replace CTE reads |

**`run_facets` at 1.1 TB is the immediate 2-year time bomb.**
With 10 facets per event × 1M events/day = 10M rows/day × 730 days = **7.3 billion rows**.
This table must be range-partitioned by `lineage_event_time` before month 4.

```sql
-- Run BEFORE run_facets hits 500M rows
-- Schedule as a one-time maintenance window (requires brief downtime or logical replication)
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
| 3 | PgBouncer transaction pooling | 1 hour | Fixes connection exhaustion under Spark burst |
| 4 | `statement_timeout=30s` on Azure Flex Server | 5 min | Kills runaway CTEs, frees connections |
| 5 | Fix tautology bug `dvf.run_uuid = dvf.run_uuid` (done) | done | 50×+ response size reduction |
| 6 | Partition `run_facets` by `lineage_event_time` | 1 day | Prevents 1.1 TB unpartitioned table |
| 7 | Build `lineage_edges` table, move reads off recursive CTEs | 1 week | Eliminates BFS CPU cost entirely |
| 8 | Move to `Standard_E8ds_v5` with `synchronous_commit=off` | 2 hours | 3× write throughput on Flex Server |
| 9 | Add read replica for lineage GET queries | 2 hours | Write path isolated from read path |
| 10 | V3 AGE pre-built graph (NOT live Cypher traversal) | 2 months | Long-term stable graph API |
