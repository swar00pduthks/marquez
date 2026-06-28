# Stories: Batch Monitoring & Predictive ETA

**Feature Spec:** `specs/batch-monitoring-eta/spec.md`
**PRD:** `specs/batch-monitoring-eta/prd.md`
**UX Design:** `specs/batch-monitoring-eta/ux.md`
**Created:** 2026-06-28
**SM Agent review:** 2026-06-28

---

## Summary

Add predictive SLA monitoring for overnight batch jobs. Operations engineers configure a deadline and expected runtime per job; a background poller computes whether each running job is ON_TRACK, AT_RISK, or BREACHED using historical p50/p90 durations. A new `/batch-monitor` dashboard surfaces this status across all jobs in a namespace, with inline blast-radius traversal showing which downstream jobs are at risk if a parent is late.

---

## Story Map

```
Story 1 (DB Migration: job_slas, run_eta_history, sla_alert_configs)
    └── Story 2 (DAO Layer: SlaDao + RunEtaHistoryDao)
            └── Story 3 (EtaComputationService + background poller)
            │       └── Story 6 (AlertService + webhook dispatch)
            └── Story 4 (SLA CRUD API: PUT/GET/DELETE /jobs/{ns}/{name}/sla)
            │       └── Story 5 (Batch summary API + run ETA response fields)
            │               ├── Story 7 (Frontend: Redux slice + types)
            │               │       └── Story 8 (Frontend: BatchWindowDashboard + SlaConfigPanel)
            │               │               └── Story 9 (Frontend: BlastRadiusTree + FreshnessBadge)
            └── Story 10 (Prometheus metrics)
                    └── Story 11 (Docs update)
```

---

## Stories

---

## Story 1: DB Migration — SLA and ETA Tables
**Status:** TODO
**Size:** S
**Modules:** [API]
**Depends on:** none

### Context
Three new tables support the SLA monitoring feature:
- `job_slas` — one row per (job, namespace, profile_name); stores deadline, expected_start, runtime_p90_min, active_days_mask, timezone
- `run_eta_history` — one row per completed run recording actual duration; RANGE-partitioned by `created_at` for time-series queries
- `sla_alert_configs` — one row per job SLA storing webhook URL and notification preferences

The natural-language-lineage-agent migration uses V106. This feature uses V107. Confirm the latest migration version before creating the file.

### Tasks
- [ ] Confirm highest existing migration version with `ls api/src/main/resources/marquez/db/migration/ | sort | tail -5`
- [ ] Create `api/src/main/resources/marquez/db/migration/V107__add_batch_sla_tables.sql`:
  - `job_slas` table: `(uuid PK, job_uuid FK, namespace_uuid FK, profile_name text, expected_start_utc time, runtime_p90_min int, sla_deadline_utc time, timezone text, active_days_mask smallint, created_at timestamptz, updated_at timestamptz)` — UNIQUE on `(job_uuid, profile_name)`
  - `run_eta_history` table: `(uuid PK, job_uuid FK, run_uuid FK, actual_duration_min int, sla_met boolean, created_at timestamptz)` — RANGE-partitioned by `created_at` monthly
  - `sla_alert_configs` table: `(uuid PK, job_sla_uuid FK, webhook_url text, notify_at_risk boolean, notify_breached boolean, created_at timestamptz, updated_at timestamptz)`
  - Indexes: `(job_uuid)` on `job_slas`; `(job_uuid, created_at)` on `run_eta_history`
- [ ] Verify migration is backward-compatible (no NOT NULL without defaults, no drops)
- [ ] Run `./gradlew :api:flywayMigrate` against a local DB and confirm success
- [ ] Write a test that verifies the migration applies cleanly to a fresh schema

### Acceptance Criteria
- AC1: Migration file named `V107__add_batch_sla_tables.sql` (two underscores, correct version number)
- AC2: `run_eta_history` is RANGE-partitioned by `created_at`; a default partition catches overflow rows
- AC3: `UNIQUE (job_uuid, profile_name)` constraint on `job_slas` enforces one SLA config per profile per job
- AC4: Migration applies without error on a fresh PostgreSQL 14 database
- AC5: Migration applies without error on a database with V1–V106 already applied
- AC6: `./gradlew check` passes with the new migration file present

---

## Story 2: DAO Layer — SlaDao and RunEtaHistoryDao
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 1

### Context
`SlaDao` handles CRUD on `job_slas` and `sla_alert_configs`. `RunEtaHistoryDao` reads historical durations for ETA computation and appends a row on run completion. All reads use `marquez_reader`; writes use `marquez_writer`. The `getHistoricalDurations(jobUuid, dayOfWeek, limit)` query is the hot path for ETA computation — ensure it uses the `(job_uuid, created_at)` index.

### Tasks
- [ ] Create `SlaRow.java`, `SlaAlertConfigRow.java`, `RunEtaHistoryRow.java` in `marquez.db.models`
- [ ] Create `SlaDao.java` in `marquez.db`:
  - `upsertJobSla(SlaRow row)` — INSERT ... ON CONFLICT DO UPDATE
  - `findByJob(UUID jobUuid, String profileName)` → `Optional<SlaRow>`
  - `findAllByNamespace(UUID namespaceUuid)` → `List<SlaRow>`
  - `deleteByJob(UUID jobUuid, String profileName)` → `int` (rows deleted)
  - `upsertAlertConfig(SlaAlertConfigRow row)`
  - `findAlertConfigByJobSla(UUID jobSlaUuid)` → `Optional<SlaAlertConfigRow>`
- [ ] Create `RunEtaHistoryDao.java` in `marquez.db`:
  - `getHistoricalDurations(UUID jobUuid, int dayOfWeekIso, int limit)` → `List<Integer>` (minutes, most recent first)
  - `recordRunCompletion(RunEtaHistoryRow row)` — called by the run-completion handler
- [ ] Add Apache 2.0 license header to all new files
- [ ] Write unit tests using TestContainers for all DAO methods
- [ ] Run `./gradlew spotlessApply pmdMain`

### Acceptance Criteria
- AC1: `upsertJobSla` is idempotent — calling twice with the same `(job_uuid, profile_name)` updates rather than inserting a duplicate
- AC2: `getHistoricalDurations` uses the `(job_uuid, created_at)` index (confirm with EXPLAIN ANALYZE in test)
- AC3: `recordRunCompletion` writes to the partitioned `run_eta_history` table; no write touches `OpenLineageDao` or any hot path
- AC4: All reads route through `marquez_reader` datasource (assert datasource name in tests)
- AC5: Unit tests cover: upsert creates, upsert updates, find-not-found, delete-returns-zero-for-missing
- AC6: `./gradlew check` passes with no new failures

---

## Story 3: EtaComputationService and Background Poller
**Status:** TODO
**Size:** L
**Modules:** [API]
**Depends on:** Story 2

### Context
`EtaComputationService` computes predicted completion time for a single running job using the three-tier algorithm from the PRD:
1. p50 historical duration adjusted by elapsed ratio (primary)
2. Fall back to p90 if < 5 historical runs
3. Fall back to configured `runtime_p90_min` if no history at all

SLA status logic:
- `ON_TRACK` — predicted completion > 15 min before deadline
- `AT_RISK` — predicted completion within 15 min of deadline
- `BREACHED` — current time > deadline

`BatchPollerService` runs as a `ManagedScheduledExecutor` (Dropwizard managed thread pool); polls every 60s globally, dropping to 10s per AT_RISK job. ETA computation per job must complete within 200ms (`statement_timeout` on `marquez_reader` for this query path).

### Tasks
- [ ] Create `EtaComputationService.java` in `marquez.service`:
  - `computeEta(UUID jobUuid, Instant runStart, String profileName, int dayOfWeekIso)` → `EtaResult`
  - `EtaResult` record: `{ predictedCompletionUtc, slaStatus, bufferMinutes, confidenceTier, historicalRunCount }`
  - Tier 1: compute p50 from history; adjust by `elapsed / p50` ratio
  - Tier 2: fall back to p90 if `historicalRunCount < 5`
  - Tier 3: fall back to configured `runtime_p90_min` if no history
- [ ] Create `BatchPollerService.java` implementing `Managed`:
  - Global tick: every 60s, query all running jobs with an SLA config in the namespace
  - Per AT_RISK job: re-compute ETA every 10s (use a separate scheduled task per AT_RISK job; cancel when job completes or becomes ON_TRACK again)
  - Write ETA results to an in-memory cache (Guava `LoadingCache`, 30s TTL) used by the batch summary API
  - On run completion (COMPLETE/FAILED/ABORTED): call `RunEtaHistoryDao.recordRunCompletion()`
- [ ] Register `BatchPollerService` as a managed object in `MarquezApp.java`
- [ ] Add config to `MarquezConfig.java`: `batchMonitor.pollIntervalSeconds` (default 60), `batchMonitor.atRiskPollIntervalSeconds` (default 10), `batchMonitor.etaTimeoutMs` (default 200)
- [ ] Write unit tests for `EtaComputationService` covering all three tiers and the AT_RISK boundary (14 min buffer vs. 16 min buffer)
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: Tier 1 ETA: `predictedCompletion = runStart + (p50 / (elapsed/p50_elapsed_ratio))` — unit test verifies the formula with known inputs
- AC2: Tier 2 activates when `historicalRunCount < 5`; `confidenceTier = "P90_FALLBACK"` in result
- AC3: Tier 3 activates when `historicalRunCount == 0`; `confidenceTier = "CONFIG_FALLBACK"` in result
- AC4: Status is `AT_RISK` when `bufferMinutes <= 15`; `BREACHED` when buffer is negative; `ON_TRACK` otherwise
- AC5: `BatchPollerService` starts and stops cleanly with Dropwizard lifecycle (no thread leaks on shutdown)
- AC6: AT_RISK jobs receive a 10s re-poll; ON_TRACK jobs receive a 60s re-poll (verified via time-controlled unit test)
- AC7: ETA cache (`LoadingCache`) is populated after first poll and served to the summary API without additional DB queries
- AC8: `./gradlew check` passes

---

## Story 4: SLA CRUD API Endpoints
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 2

### Context
Three endpoints manage SLA configuration per job: `PUT /api/v1/jobs/{namespace}/{name}/sla` (upsert), `GET /api/v1/jobs/{namespace}/{name}/sla`, `DELETE /api/v1/jobs/{namespace}/{name}/sla`. These are user-facing configuration endpoints (low traffic); they use the write/read paths normally.

### Tasks
- [ ] Create `SlaResource.java` in `marquez.api`:
  - `PUT /api/v1/jobs/{namespace}/{name}/sla` — body: `SlaConfig`; returns `200` with saved config
  - `GET /api/v1/jobs/{namespace}/{name}/sla` — returns `200` with config or `404` if not configured
  - `DELETE /api/v1/jobs/{namespace}/{name}/sla` — returns `204` on success, `404` if not configured
- [ ] Validate that `slaDeadlineUtc > (expectedStartUtc + runtimeP90Min minutes)` — return `422` with field-level error if not
- [ ] Register resource in `MarquezApp.java`; instantiate `SlaService` (wrapping `SlaDao`) in `MarquezContext.java`
- [ ] Update `docs/openapi.yml` with all three endpoint definitions and `SlaConfig` schema
- [ ] Write `SlaResourceTest.java` and `SlaResourceIntegrationTest.java`
- [ ] Add entry to `CHANGELOG.md` under `[Unreleased]`
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: `PUT` is idempotent — calling twice with the same `(namespace, name)` updates, does not return `409`
- AC2: `PUT` with `slaDeadlineUtc <= expectedStartUtc + runtimeP90Min` returns `422` with a human-readable error message identifying which field is invalid
- AC3: `GET` returns `200` for a configured job; `404` for a job that has no SLA config
- AC4: `DELETE` returns `204` for a configured job; `404` for a job that has no SLA config
- AC5: `docs/openapi.yml` updated and passes `swagger-cli validate docs/openapi.yml`
- AC6: Integration test covers the full PUT → GET → DELETE cycle
- AC7: `./gradlew check` passes

---

## Story 5: Batch Summary API and Run ETA Response Fields
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 3, Story 4

### Context
Two API changes surface ETA data to the frontend:
1. `GET /api/v1/batch/summary?namespace={ns}` — new endpoint returning the `BatchWindowSummary` response (counts by status, per-job `SlaJobStatus` list) served from the Guava cache populated by `BatchPollerService`
2. `GET /api/v1/runs/{id}` — existing endpoint; add `predictedCompletionUtc` and `slaStatus` fields to the response (null if no SLA configured or run not active)

### Tasks
- [ ] Create `BatchMonitorResource.java` in `marquez.api`:
  - `GET /api/v1/batch/summary?namespace={ns}` — reads from `BatchPollerService` Guava cache; returns `BatchWindowSummary` JSON
  - `BatchWindowSummary` schema: `{ onTrack: int, atRisk: int, breached: int, noSla: int, jobs: SlaJobStatus[] }`
  - `SlaJobStatus` schema: per PRD section 3.1
  - Returns `200` even if namespace has no SLA-configured jobs (returns all-zero counts + empty `jobs` array)
- [ ] Modify `RunResource.java` (or `RunResponse.java`) to add optional `predictedCompletionUtc` and `slaStatus` fields
- [ ] Populate new run fields from the Guava cache (null if job has no SLA config or run is not RUNNING)
- [ ] Update `docs/openapi.yml` with the new endpoint and modified run response schema
- [ ] Write `BatchMonitorResourceTest.java` and update `RunResourceTest.java`
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: `GET /api/v1/batch/summary?namespace=ns` returns `200` with correct counts and job list served from cache (no DB query)
- AC2: Summary response sorts jobs: BREACHED first, then AT_RISK, then ON_TRACK, all sorted by `slaDeadlineUtc ASC` within group
- AC3: `GET /api/v1/runs/{id}` for a RUNNING job with SLA config includes non-null `predictedCompletionUtc` and `slaStatus`
- AC4: `GET /api/v1/runs/{id}` for a RUNNING job without SLA config has `predictedCompletionUtc: null` and `slaStatus: "NO_SLA"`
- AC5: `docs/openapi.yml` updated and passes `swagger-cli validate docs/openapi.yml`
- AC6: `./gradlew check` passes

---

## Story 6: AlertService and Webhook Dispatch
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 3

### Context
When `BatchPollerService` detects a transition to AT_RISK or BREACHED for a job that has an `sla_alert_config`, it calls `AlertService` to dispatch a webhook. The webhook payload is a JSON object (see PRD section 7.3). Alerts must be debounced — only fire once per status transition per run (not on every 10s poll tick). Use an in-memory set of `(runId, status)` pairs that have already been alerted; clear on run completion.

### Tasks
- [ ] Create `AlertService.java` in `marquez.service`:
  - `maybeAlert(UUID runId, UUID jobSlaUuid, SlaStatus newStatus, EtaResult etaResult)` — fires webhook if this `(runId, status)` pair has not been alerted yet
  - Uses OkHttp (already a dependency) for async POST to the configured webhook URL
  - Webhook payload: `{ event: "SLA_AT_RISK" | "SLA_BREACHED", job, namespace, runId, predictedCompletion, deadline, bufferMinutes, downstreamAtRisk: [] }`
  - Debounce via `ConcurrentHashMap<String, Set<SlaStatus>> alertedRuns`; cleared when run completes
- [ ] Wire `AlertService` into `BatchPollerService`; call after each ETA re-computation that changes status
- [ ] Add config: `batchMonitor.webhookTimeoutMs` (default 5000); failed webhook POST logs a warning, does not propagate exception to poller
- [ ] Write unit tests for `AlertService` covering: first AT_RISK fires, second AT_RISK does not re-fire, BREACHED fires separately, run completion clears the set
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: Webhook fires once on first transition to AT_RISK for a given run (not on subsequent 10s re-polls)
- AC2: Webhook fires once on first transition to BREACHED even if AT_RISK was already fired
- AC3: Webhook payload includes `bufferMinutes` as a positive number (AT_RISK) or negative number (BREACHED)
- AC4: A 5xx from the webhook endpoint logs a `WARN` message and does not crash the poller thread
- AC5: A webhook timeout (> `webhookTimeoutMs`) is handled gracefully (OkHttp `ConnectTimeoutException` caught)
- AC6: `./gradlew check` passes

---

## Story 7: Frontend — Redux Slice and API Types
**Status:** TODO
**Size:** S
**Modules:** [WEB]
**Depends on:** Story 5

### Context
Wire the batch summary API and the SLA CRUD API into the React/Redux store. Types must match the OpenAPI schemas exactly. Two slices: `batchMonitorSlice` (summary + job list) and `slaConfigSlice` (CRUD for the SLA config panel).

### Tasks
- [ ] Add TypeScript types to `web/src/types/batchMonitor.ts`:
  - `BatchWindowSummary`, `SlaJobStatus`, `SlaConfig`, `SlaStatus`, `BlastRadiusNode`
- [ ] Create `web/src/requests/batchMonitorRequests.ts`: `getBatchSummary(namespace)`, `putJobSla(namespace, name, config)`, `getJobSla(namespace, name)`, `deleteJobSla(namespace, name)`
- [ ] Create `web/src/store/batchMonitorSlice.ts`:
  - State: `{ summary: BatchWindowSummary | null, isLoading: boolean, error: string | null }`
  - Thunk: `fetchBatchSummary(namespace)`
  - Selectors: `selectBatchSummary`, `selectBatchMonitorLoading`, `selectBatchMonitorError`
- [ ] Create `web/src/store/slaConfigSlice.ts`:
  - State: `{ config: SlaConfig | null, isSaving: boolean, error: string | null }`
  - Thunks: `fetchSlaConfig(namespace, jobName)`, `saveSlaConfig(namespace, jobName, config)`, `removeSlaConfig(namespace, jobName)`
- [ ] Register both slices in `web/src/store/index.ts`
- [ ] Write Jest tests for both slices (all thunks in pending/fulfilled/rejected)
- [ ] Run `cd web && yarn test` and `yarn tsc --noEmit`

### Acceptance Criteria
- AC1: `SlaJobStatus` TypeScript type includes all fields from the OpenAPI schema (status, jobName, namespace, startedAt, etaUtc, deadlineUtc, bufferMinutes)
- AC2: `batchMonitorSlice` handles loading, error, and populated states for `fetchBatchSummary`
- AC3: `slaConfigSlice` handles all three CRUD operations with correct optimistic/pessimistic updates
- AC4: All slice tests pass using MSW to mock API responses
- AC5: `yarn tsc --noEmit` and `yarn test` pass with no regressions

---

## Story 8: Frontend — BatchWindowDashboard and SlaConfigPanel
**Status:** TODO
**Size:** L
**Modules:** [WEB]
**Depends on:** Story 7

### Context
Implements the primary batch monitoring UI per `ux.md`:
- `BatchWindowDashboard` — the `/batch-monitor` page; MUI `Table` with expandable rows, summary bar, sort order (BREACHED → AT_RISK → ON_TRACK), `?highlight=jobName` deep-link support
- `SlaConfigPanel` — MUI `Drawer` slide-over for SLA configuration; includes the live preview section showing alert trigger condition and historical p50/p90

### Tasks
- [ ] Create `/batch-monitor` page at `web/src/pages/batch-monitor/index.tsx`
- [ ] Create `web/src/components/batch-monitor/BatchWindowDashboard.tsx`:
  - MUI `Table` with columns: Status, Job Name, Namespace, Started, ETA, Deadline, Buffer (per `ux.md` Component Specs)
  - Sort: BREACHED first, then AT_RISK, then ON_TRACK; within group, sort by `deadlineUtc ASC`
  - Summary bar: MUI `Alert` showing "X on track / Y at risk / Z breached"
  - Row expansion via `Collapse`; expanded row shows ETA explanation, historical reliability `LinearProgress`, blast radius (collapsed), and action buttons
  - Deep link: on mount, if `?highlight=jobName` query param present, find that row, scroll to it, expand it
  - Table `aria-label="Batch window status"` per accessibility checklist
- [ ] Create `web/src/components/batch-monitor/SlaStatusBadge.tsx`:
  - MUI `Chip` size="small" with icon and color per status (ON_TRACK green, AT_RISK amber, BREACHED red, BLOCKED grey, NO_SLA grey)
  - `aria-label="SLA status: [label]"` — never color-only
- [ ] Create `web/src/components/batch-monitor/SlaConfigPanel.tsx`:
  - MUI `Drawer` anchor="right"
  - Fields: Profile name, Days active (ToggleButtonGroup), Expected start (TimePicker), Runtime p90 (duration input), SLA deadline (TimePicker), Timezone (Select)
  - Live preview section below form
  - Esc closes (with discard confirmation if form is dirty); Tab cycles within drawer (focus trap)
  - `role="dialog"` `aria-label="Configure SLA for [jobName]"`
- [ ] Add `SlaConfigPanel` trigger ("Configure SLA" button) to job detail page
- [ ] Write RTL tests covering all UI states: loading (skeleton), empty (no SLA jobs), all-on-track banner, table with BREACHED row, row expansion, SLA config panel open/save/close
- [ ] Run `cd web && yarn test` and manually verify in browser: deep link, row expansion, blast radius, SLA config save

### Acceptance Criteria
- AC1: Summary bar shows correct counts derived from Redux state (not hardcoded)
- AC2: Table sorts BREACHED before AT_RISK before ON_TRACK; within each group, rows sort by `deadlineUtc ASC`
- AC3: `?highlight=customer_orders_etl` causes that row to be auto-scrolled-to and auto-expanded on page load
- AC4: Expanded row shows ETA explanation text in the format: "Based on p90 historical duration (Xh Ym). Elapsed: Xh Ym."
- AC5: `SlaConfigPanel` validates that deadline > (expectedStart + runtimeP90) before enabling Save; shows inline error if not
- AC6: `SlaConfigPanel` Esc triggers discard confirmation dialog if any field is dirty
- AC7: All `SlaStatusBadge` instances have `aria-label="SLA status: [label]"` (no color-only status)
- AC8: RTL tests cover: loading state, empty state, all-on-track banner, expanded row, and SLA panel save flow
- AC9: `yarn tsc --noEmit` and `yarn test` pass with no regressions

---

## Story 9: Frontend — BlastRadiusTree and FreshnessBadge
**Status:** TODO
**Size:** M
**Modules:** [WEB]
**Depends on:** Story 8

### Context
`BlastRadiusTree` renders the downstream dependency tree inside an expanded `BatchWindowDashboard` row. `FreshnessBadge` is a reusable `Chip` added to the Dataset detail page. Both depend on the `BlastRadiusNode` type from Story 7.

The blast radius data comes from the `GET /api/v1/batch/summary` response's per-job `downstreamJobs` field. A separate `GET /api/v1/jobs/{ns}/{name}/lineage?depth=3` call retrieves it on row expansion.

### Tasks
- [ ] Create `web/src/components/batch-monitor/BlastRadiusTree.tsx`:
  - MUI `TreeView` + `TreeItem` with `SlaStatusBadge` per node
  - BLOCKED/AT_RISK nodes always visible; ON_TRACK nodes collapsed under "N safe downstream jobs ▾"
  - "Show N more hops" expander for nodes beyond `maxDepth=3`
  - Node click → navigate to job detail page
  - `aria-label="Downstream blast radius for [rootJob]"` on TreeView; per-node `aria-label="[jobName]: [status]"`
- [ ] Create `web/src/components/datasets/FreshnessBadge.tsx`:
  - MUI `Chip` size="small" with `Tooltip`
  - FRESH (green), STALE (amber), UNKNOWN (grey) per `ux.md` spec
  - Tooltip: "Updated X ago. Expected every Nh." or "Last updated X ago. Expected every Nh."
- [ ] Add `FreshnessBadge` to the Dataset detail page (alongside existing metadata fields)
- [ ] Wire `BlastRadiusTree` into the expanded row of `BatchWindowDashboard` (collapsed by default, expand on click; expanded by default when accessed via `?highlight=` deep link)
- [ ] Write RTL tests for `BlastRadiusTree` (empty state, BLOCKED visible, ON_TRACK collapsed, "show more" expander) and `FreshnessBadge` (FRESH, STALE, UNKNOWN with tooltip text)
- [ ] Run `cd web && yarn test`

### Acceptance Criteria
- AC1: BLOCKED and AT_RISK nodes are always visible without user interaction
- AC2: ON_TRACK nodes are collapsed under a disclosure button that shows the count (e.g., "3 safe downstream jobs ▾")
- AC3: "Show N more hops" button appears when the tree has nodes beyond depth 3; clicking it fetches depth 4–6 nodes
- AC4: `FreshnessBadge` FRESH shows green chip with tooltip "Updated X ago. Expected every Nh."
- AC5: `FreshnessBadge` UNKNOWN shows grey chip with tooltip "No SLA configured for the producing job."
- AC6: RTL tests pass; `yarn tsc --noEmit` passes

---

## Story 10: Prometheus Metrics
**Status:** TODO
**Size:** XS
**Modules:** [API]
**Depends on:** Story 3, Story 6

### Context
Per the PRD's non-functional requirements and the Platform agent's scaling prerequisites, three new Prometheus metrics must be emitted. The Platform engineer confirmed: `marquez_eta_poll_duration_seconds` histogram (one observation per poll cycle), `marquez_eta_computation_timeout_total` counter (per-job 200ms timeout breach), and `marquez_sla_alerts_dispatched_total` counter (by `status` label).

### Tasks
- [ ] Add Dropwizard Metrics `Timer` for `BatchPollerService` poll cycle duration → exposed as `marquez_eta_poll_duration_seconds` histogram via the existing `MetricRegistry`
- [ ] Add `Counter` for `marquez_eta_computation_timeout_total` — increment in `EtaComputationService` when DB query exceeds 200ms
- [ ] Add `Counter` for `marquez_sla_alerts_dispatched_total{status="AT_RISK|BREACHED"}` — increment in `AlertService.maybeAlert()` on successful dispatch
- [ ] Update `METRICS.md` with all three new metrics (name, type, labels, description, unit)
- [ ] Write unit tests verifying counters increment on the expected code paths
- [ ] Run `./gradlew check`

### Acceptance Criteria
- AC1: `marquez_eta_poll_duration_seconds` histogram is registered in `MetricRegistry` and emits one observation per complete poll cycle
- AC2: `marquez_eta_computation_timeout_total` increments when `EtaComputationService` detects a query exceeding `etaTimeoutMs`
- AC3: `marquez_sla_alerts_dispatched_total{status="AT_RISK"}` increments on each AT_RISK webhook dispatch
- AC4: `marquez_sla_alerts_dispatched_total{status="BREACHED"}` increments on each BREACHED webhook dispatch
- AC5: `METRICS.md` updated with all three metrics before the PR is merged
- AC6: `./gradlew check` passes

---

## Story 11: Documentation Update
**Status:** TODO
**Size:** S
**Modules:** [DOCS]
**Depends on:** Story 4, Story 5, Story 8

### Context
User-facing Docusaurus docs covering the batch monitor feature, SLA configuration guide, and Prometheus alert runbook. Helm `values.yaml` example must show the poller configuration knobs.

### Tasks
- [ ] Create `docs/docs/features/batch-monitoring.md` covering: feature overview, how to configure an SLA, how to read the dashboard, how to interpret blast radius, deep-link format for alert integrations
- [ ] Create `docs/docs/alerts/batch-sla-runbook.md`: what AT_RISK means, escalation steps, how to check blast radius, how to silence a false-positive alert
- [ ] Update `docs/docs/api-reference.md` (or equivalent) to link to the three new SLA endpoints and the batch summary endpoint
- [ ] Update Helm `values.yaml` example with `batchMonitor.pollIntervalSeconds`, `batchMonitor.atRiskPollIntervalSeconds`, `batchMonitor.etaTimeoutMs`
- [ ] Run `cd docs && yarn build` and confirm no errors
- [ ] Final review: `CHANGELOG.md` has exactly one entry for this feature under `[Unreleased]`

### Acceptance Criteria
- AC1: `cd docs && yarn build` completes with no errors or warnings
- AC2: `docs/docs/features/batch-monitoring.md` includes SLA configuration instructions and a screenshot/diagram placeholder
- AC3: `docs/docs/alerts/batch-sla-runbook.md` exists and covers AT_RISK → escalation → resolution steps
- AC4: `METRICS.md` reflects all new Prometheus metrics (added in Story 10)
- AC5: `CHANGELOG.md` has exactly one entry for this feature under `[Unreleased]`

---

## Completion Summary

| Story | Status | PR | Notes |
|-------|--------|----|-------|
| 1 – DB Migration: SLA and ETA Tables | TODO | — | V107; confirm version before creating |
| 2 – DAO Layer: SlaDao + RunEtaHistoryDao | TODO | — | All reads via `marquez_reader` |
| 3 – EtaComputationService + Poller | TODO | — | 3-tier algorithm; AT_RISK at 10s poll |
| 4 – SLA CRUD API Endpoints | TODO | — | PUT idempotent; validate deadline > start+p90 |
| 5 – Batch Summary API + Run ETA Fields | TODO | — | Served from Guava cache; no extra DB call |
| 6 – AlertService + Webhook Dispatch | TODO | — | Debounce per (runId, status) pair |
| 7 – Frontend: Redux Slice + Types | TODO | — | Two slices: batchMonitor + slaConfig |
| 8 – Frontend: BatchWindowDashboard + SlaConfigPanel | TODO | — | Deep-link ?highlight= required |
| 9 – Frontend: BlastRadiusTree + FreshnessBadge | TODO | — | ON_TRACK nodes collapsed by default |
| 10 – Prometheus Metrics | TODO | — | 3 metrics; update METRICS.md |
| 11 – Documentation Update | TODO | — | Feature docs + SLA runbook |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
