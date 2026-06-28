# PRD: Batch Monitoring & Predictive ETA

**Author(s):** BMAD PM Agent
**Created:** 2026-06-28
**Last Updated:** 2026-06-28
**Status:** `Draft`
**Linked Proposal/Issue:** #TBD
**Target Release:** unscheduled

---

## 1. Overview

### 1.1 Problem Statement

Marquez knows when batch jobs start, finish, and fail — but it offers no operational view of batch window health. Operations engineers must check Airflow, Grafana, and Marquez separately to understand whether the overnight batch window is on track. There is no SLA definition, no ETA prediction, and no pre-breach alerting. By the time a job is confirmed late, the SLA is already breached and downstream reports are delayed. For a data mesh with 50+ teams and hundreds of batch jobs, this is a reliability crisis waiting to happen on any given night.

### 1.2 Proposed Solution

A **Batch Monitoring** module within Marquez that adds three capabilities:

1. **SLA definition**: operators configure expected start time, expected duration (p90 from history), and hard deadline per job.
2. **Predictive ETA**: for every running job, compute a predicted completion time using historical run duration data (percentile-based regression on duration vs. input data size and time-of-day). Alert when predicted completion exceeds the deadline, before the breach occurs.
3. **Batch window dashboard**: a single operational view showing all jobs in a batch window, their current state, predicted completion, SLA status (on-track / at-risk / breached), and downstream blast radius for any failing job.

### 1.3 Background & Context

Marquez already stores all the data needed: `runs` table has start/end timestamps and state for every historical run; `run_lineage_denormalized` has job-level statistics; the Apache AGE graph has the full dependency tree. The batch monitoring layer is a query and alert layer over existing data — it requires new configuration tables (SLA definitions) and new computed columns (ETA), but does not require new instrumentation from pipeline authors.

The Batch Ops Engineer user persona (see `bmad/agents/users/batch-ops-engineer-user.md`) has driven these requirements through explicit persona reviews.

---

## 2. Goals & Non-Goals

### Goals

- Enable operators to define SLA targets per job (deadline, expected duration) without modifying pipeline code.
- Predict completion time for every running job using historical duration data; surface the prediction in the UI and via API.
- Fire a predictive alert when a job's predicted completion exceeds its SLA deadline — before the breach, not after.
- Provide a single-screen batch window health view replacing the current multi-tool workflow.
- Show downstream blast radius: if job X is at risk, which jobs and datasets are transitively blocked?

### Non-Goals

- Does not instrument pipelines — SLA configuration is done in Marquez UI/API, not in Airflow/Spark code.
- Does not replace Airflow's scheduler or retry logic — Marquez is an observer, not an orchestrator.
- ML-based anomaly detection is out of scope for v1 — use percentile-based historical statistics, not a trained model.
- Real-time progress percentage within a running job (e.g., "Stage 3/10") requires pipeline-level instrumentation; v1 uses time-elapsed / expected-duration as a proxy.
- Multi-cloud or cross-region batch windows are out of scope for v1.

---

## 3. User Stories

| ID | Persona | Story | Priority |
|----|---------|-------|----------|
| US-1 | Batch Ops Engineer | As a Batch Ops Engineer, I want to see a single dashboard showing all jobs in my batch window with predicted completion times and SLA status so I can identify at-risk jobs before they breach. | `P0` |
| US-2 | Batch Ops Engineer | As a Batch Ops Engineer, I want to receive an alert when a running job's predicted completion exceeds its SLA deadline so I can intervene proactively. | `P0` |
| US-3 | Batch Ops Engineer | As a Batch Ops Engineer, I want to see the full downstream blast radius of a failing or delayed job so I know which downstream jobs and reports are at risk. | `P0` |
| US-4 | Data Engineer | As a Data Engineer, I want to define SLA targets for my jobs (expected duration, deadline) through the Marquez API or UI so the ops team can monitor them without changing my pipeline code. | `P0` |
| US-5 | Data Analyst | As a Data Analyst, I want to see whether the dataset feeding my report was produced by a successful, on-time job so I know whether to trust this morning's data. | `P1` |
| US-6 | Batch Ops Engineer | As a Batch Ops Engineer, I want to see a reliability score per job (% of runs that met SLA over the last 30 days) so I can prioritize which pipelines to harden. | `P1` |
| US-7 | Platform Engineer | As a Platform Engineer, I want Prometheus metrics for SLA breach counts and ETA prediction accuracy so I can build Grafana dashboards and set up PagerDuty alerts. | `P1` |
| US-8 | Business User | As a Business User, I want to see a simple "morning reports: all on time / 2 delayed / 1 failed" summary without needing to understand individual jobs. | `P2` |

---

## 4. Functional Requirements

### 4.1 Must Have (P0)

- `[FR-1]` SLA configuration API: `PUT /api/v1/jobs/{namespace}/{name}/sla` accepts `{ "expected_start": "HH:MM", "expected_runtime_p90_minutes": N, "deadline": "HH:MM", "timezone": "TZ" }`.
- `[FR-2]` SLA configuration stored in new `job_slas` table; multiple SLA profiles supported per job (e.g., weekday vs weekend batch windows).
- `[FR-3]` Predictive ETA computation: for every `RUNNING` job, compute `predicted_completion = run_started_at + ETA`. ETA is calculated as: median historical duration for the same job and day-of-week combination, adjusted by the ratio of current elapsed time to historical median start-to-first-event latency. Fallback to p90 duration when insufficient history (< 5 runs).
- `[FR-4]` ETA surfaced in: `GET /api/v1/runs/{id}` response (new `predictedCompletionAt` field), batch window dashboard, and the Lineage Agent tool.
- `[FR-5]` SLA status classification per running job: `ON_TRACK` (predicted completion ≤ deadline), `AT_RISK` (predicted completion within 15 min of deadline), `BREACHED` (predicted completion > deadline or run failed).
- `[FR-6]` Predictive alert: when status transitions from `ON_TRACK` → `AT_RISK` or `AT_RISK` → `BREACHED`, fire a webhook to the configured alert URL. Payload: `{ job_name, namespace, sla_deadline, predicted_completion, status, blast_radius_summary }`.
- `[FR-7]` Downstream blast radius API: `GET /api/v1/jobs/{namespace}/{name}/blast-radius` traverses the AGE graph up to 5 hops downstream and returns all transitively dependent jobs and datasets, annotated with their own SLA status.
- `[FR-8]` Batch window dashboard: new route `/batch-monitor` in the web UI. Shows a table of all jobs with configured SLAs, sorted by deadline. Columns: job name, namespace, status badge, current run started at, predicted completion, deadline, SLA buffer (minutes remaining / overdue). Row click expands to show blast radius.

### 4.2 Should Have (P1)

- `[FR-9]` Job reliability score: `GET /api/v1/jobs/{namespace}/{name}/sla-history` returns per-run SLA outcome (met/missed) for the last 90 days, plus a summary `reliability_pct`.
- `[FR-10]` ETA history table: store `(run_uuid, predicted_at, predicted_completion, actual_completion)` per run for ETA accuracy tracking and model improvement.
- `[FR-11]` Prometheus metrics: `marquez_sla_status_total{namespace,job,status}`, `marquez_eta_error_minutes{namespace,job}` (actual minus predicted completion), `marquez_blast_radius_jobs_total{namespace,job}`.
- `[FR-12]` Alert webhook supports Slack incoming webhook format as a first-class option (envelope matches Slack's `{ "text": "..." }` format).
- `[FR-13]` Dataset freshness status: `GET /api/v1/datasets/{namespace}/{name}` adds `freshnessStatus` field (`FRESH` / `STALE` / `UNKNOWN`) based on whether the last producing job completed successfully within the dataset's expected update frequency.

### 4.3 Nice to Have (P2)

- `[FR-14]` Business-level batch window summary: `GET /api/v1/batch-windows/summary` returns `{ "on_time": N, "at_risk": N, "breached": N, "as_of": "..." }` for use in executive dashboards.
- `[FR-15]` Natural language integration: Lineage Agent tool `get_batch_window_status` uses the batch monitoring API to answer "are all my jobs on track?" questions.
- `[FR-16]` Anomaly detection: flag runs where elapsed time at the 50% historical mark has exceeded the p90 historical elapsed time — i.e., the job is running abnormally slowly even if ETA hasn't breached yet.

---

## 5. Non-Functional Requirements

| Attribute | Requirement |
|-----------|-------------|
| Performance | ETA computation must complete in < 100ms per job. Batch window dashboard must load in < 1 second for up to 500 jobs. Blast radius traversal ≤ 2 seconds for 5-hop graphs. |
| Scalability | SLA computation runs as a background job polling `runs` every 60 seconds. Must handle 10,000 configured SLAs without degrading primary write path. |
| Backward Compatibility | `GET /api/v1/runs/{id}` response adds optional `predictedCompletionAt` field. Existing clients that don't read it are unaffected. |
| Security | Webhook URLs stored encrypted at rest. Alert payloads must not include raw run arguments or facets (may contain secrets). |
| Availability | SLA monitoring uses read replica for all historical queries — never the primary write path. SLA status polling is best-effort; Marquez ingestion must not be affected by monitoring latency. |
| Observability | All new Prometheus metrics documented in `METRICS.md`. ETA accuracy tracked per job so the prediction model can be evaluated over time. |

---

## 6. API / UX Surface Changes

### 6.1 New or Modified API Endpoints

| Method | Path | Change |
|--------|------|--------|
| `PUT` | `/api/v1/jobs/{namespace}/{name}/sla` | New — configure SLA for a job |
| `GET` | `/api/v1/jobs/{namespace}/{name}/sla` | New — retrieve SLA configuration |
| `GET` | `/api/v1/jobs/{namespace}/{name}/blast-radius` | New — downstream impact graph |
| `GET` | `/api/v1/jobs/{namespace}/{name}/sla-history` | New — per-run SLA outcomes + reliability score |
| `GET` | `/api/v1/batch-windows/summary` | New — aggregate batch window health |
| `GET` | `/api/v1/runs/{id}` | Modified — add `predictedCompletionAt`, `slaStatus` to response |
| `GET` | `/api/v1/datasets/{namespace}/{name}` | Modified — add `freshnessStatus` to response |

### 6.2 UI Changes

- New route: `/batch-monitor` — the batch window dashboard (table, status badges, ETA countdown, drill-down blast radius).
- Run detail page: add `Predicted completion` and `SLA status` fields when SLA is configured.
- Job detail page: add SLA configuration panel and reliability score chart.
- Dataset detail page: add freshness status badge.
- Global navigation: add `Batch Monitor` link.

### 6.3 Client Library Impact

- Python client: `MarquezClient.jobs.set_sla(namespace, name, sla_config)` and `MarquezClient.jobs.get_blast_radius(namespace, name)`.
- Java client: `JobClient.setSla(SlaConfig)` and `JobClient.getBlastRadius(BlastRadiusRequest)`.

---

## 7. Data Model Impact

| Change | Type | Backward Compatible? |
|--------|------|----------------------|
| New table `job_slas` — see schema below | Additive | Yes |
| New table `run_eta_history` — RANGE partitioned by computed_at | Additive | Yes |
| New table `sla_alert_configs` | Additive | Yes |
| `runs` table: add computed view `run_sla_status` (no column change on base table) | Additive | Yes |
| `GET /api/v1/runs/{id}`: new optional response fields `predictedCompletionAt`, `slaStatus` | Additive | Yes |

```sql
-- job_slas: SLA configuration per job
CREATE TABLE job_slas (
    uuid              UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_uuid          UUID        NOT NULL REFERENCES jobs(uuid),
    profile_name      TEXT        NOT NULL DEFAULT 'default',
    day_of_week       INT[]       NULL,         -- NULL = all days; [1,2,3,4,5] = weekdays
    expected_start    TIME        NULL,
    runtime_p90_min   INT         NOT NULL,
    deadline          TIME        NOT NULL,
    timezone          TEXT        NOT NULL DEFAULT 'UTC',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_uuid, profile_name)
);

-- run_eta_history: tracks ETA predictions vs actuals for model accuracy
CREATE TABLE run_eta_history (
    uuid                 UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    run_uuid             UUID        NOT NULL,
    computed_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    predicted_completion TIMESTAMPTZ NOT NULL,
    actual_completion    TIMESTAMPTZ NULL,   -- filled in when run ends
    eta_basis            TEXT        NOT NULL  -- 'p50_historical', 'p90_fallback', 'elapsed_ratio'
) PARTITION BY RANGE (computed_at);

-- sla_alert_configs: webhook configuration per job SLA
CREATE TABLE sla_alert_configs (
    uuid         UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    job_sla_uuid UUID        NOT NULL REFERENCES job_slas(uuid),
    alert_type   TEXT        NOT NULL,  -- 'webhook', 'slack'
    endpoint_url TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 8. Open Questions

| # | Question | Owner | Resolution |
|---|----------|-------|------------|
| 1 | Should ETA be computed on-demand (per API request) or pre-computed by a background poller? | Architect | Recommend background poller every 60s writing to `run_eta_history`; API reads latest value. Avoids repeated historical queries per user request. |
| 2 | How many historical runs are needed before ETA is reliable? Suggest 5 as minimum; what's the fallback? | PM | Fallback to p90 of all runs for that job (ignoring day-of-week), then to the configured `runtime_p90_min` if fewer than 3 runs exist. |
| 3 | Should SLA breach alerts be fired via Prometheus Alertmanager integration rather than direct webhooks? | Platform Agent | Support both: native webhook for simplicity, Alertmanager config for teams that already have it. |
| 4 | Does blast radius traversal use the AGE graph (async, may lag) or the normalized tables (always current)? | Architect | Normalized tables for real-time accuracy; AGE graph for deep traversal when normalized data doesn't cover enough hops. |
| 5 | Is `freshnessStatus` on datasets a derived field or stored? | DB Agent | Derived at query time from last successful run's `ended_at` vs a per-dataset `expected_update_frequency` config. No stored column needed. |

---

## 9. Definition of Done

- [ ] All P0 functional requirements implemented and verified
- [ ] ETA prediction tested against 30 days of historical run data; prediction error (MAE) < 15 minutes for jobs with ≥ 10 historical runs
- [ ] Blast radius traversal tested with a lineage graph of 500 jobs, 5 hops — response ≤ 2 seconds
- [ ] Webhook alert fired within 60 seconds of ETA crossing the deadline threshold
- [ ] Batch window dashboard loads in < 1 second for 500 jobs
- [ ] All new API endpoints in `docs/openapi.yml` and `spec/openapi.yml`
- [ ] All new tables in `marquez_data_model.md`
- [ ] `METRICS.md` updated with all new Prometheus metrics
- [ ] Python and Java client helpers implemented and tested
- [ ] Integration tests cover SLA breach → alert → blast radius full flow
- [ ] `CHANGELOG.md` updated under `[Unreleased]`

---

## 10. Out of Scope (Explicit Exclusions)

- **Pipeline orchestration**: Marquez will not trigger job retries or backfills — it observes and alerts only.
- **ML-based ETA model**: v1 uses percentile statistics. A trained regression model (e.g., accounting for input data volume) is a v2 enhancement.
- **Real-time progress tracking**: requires pipeline-level instrumentation emitting progress events. v1 uses elapsed-time-based proxy only.
- **Cross-namespace batch windows**: v1 scopes the batch window dashboard to a single namespace.

---

## Revision History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-28 | BMAD PM Agent | Initial draft |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
