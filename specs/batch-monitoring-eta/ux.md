# UX Design: Batch Monitoring & Predictive ETA

**Phase:** 2b — UX Design
**Agent:** UX Designer (`bmad/agents/ux-designer-agent.md`)
**Status:** Draft
**PRD:** `specs/batch-monitoring-eta/prd.md`

---

## Design Principle

Operations engineers during an incident do not browse — they scan. The batch window dashboard must communicate the answer to "is everything OK?" in under 3 seconds without clicking anything. Color, sort order, and a single number (minutes until SLA breach) are the primary information carriers — not prose, not graphs.

---

## User Flows

### Flow 1 — Batch Ops Engineer: Morning SLA check
**Persona:** Batch Ops Engineer
**Entry point:** Direct link to `/batch-monitor` (bookmarked or on-call dashboard embed)

```
Step 1: [User opens /batch-monitor]
        → [System loads SLA status for all jobs in current namespace]
        → [UI renders] within 1 second:
            Summary bar:  ✅ 14 ON TRACK   ⚠️ 2 AT RISK   ❌ 1 BREACHED
            Table: jobs sorted by deadline ASC, AT_RISK and BREACHED pinned to top

Step 2: [User sees AT_RISK row for customer_orders_etl]
        Row shows:
          job name | namespace | ⚠️ AT RISK | started 02:15 | ETA 05:48 | deadline 06:00
          → Buffer: 12 min (shown in amber)

Step 3: [User clicks AT_RISK row]
        → [Row expands inline (no page navigation)] showing:
            Predicted completion: 05:48 UTC (based on p90 historical: 3h 20m, elapsed: 3h 02m)
            SLA deadline: 06:00 UTC — 12 minutes buffer
            Historical reliability: 94% (last 30 runs met SLA)
            Blast radius (collapsed by default, expand on click):
              ↳ revenue_report_daily [deadline 06:30 — ON TRACK if parent completes by 06:00]
              ↳ customer_churn_model_training [deadline 08:00 — ON TRACK]

Step 4: [User wants to act — clicks "Ask Marquez"]
        → [Opens lineage agent chat with context pre-filled]
        → "Tell me about customer_orders_etl and its current run"

─────────────────────────────────────────────────────────────────────────
Empty state:    "No SLA-configured jobs in this namespace.
                [Configure SLAs →] to start monitoring."
Loading state:  Table skeleton (5 rows × 6 columns)
All on track:   Full-width green banner: "All 16 jobs on track ✅ — next deadline in 47 min"
Error state:    Alert: "Could not load batch status. [Retry]"
```

---

### Flow 2 — Data Engineer: Configuring an SLA
**Persona:** Data Engineer
**Entry point:** Job detail page → "Configure SLA" button

```
Step 1: [User clicks "Configure SLA" on job detail page]
        → [Slide-over panel opens] (not full page — user keeps job context)

Step 2: [Panel shows SLA form]
        Fields:
          Profile name:           [default          ]  (text, defaults to "default")
          Days active:            [☑ Mon ☑ Tue ☑ Wed ☑ Thu ☑ Fri ☐ Sat ☐ Sun]
          Expected start:         [02:00            ]  (time picker, 24h)
          Expected runtime (p90): [3h 20m           ]  (duration picker)
          SLA deadline:           [06:00            ]  (time picker, 24h)
          Timezone:               [UTC               ▾] (select)

        Preview (updates live):
          "Alert will fire if predicted completion exceeds 06:00 UTC on weekdays.
           Based on today's history: p50 = 3h 10m, p90 = 3h 22m."

Step 3: [User clicks Save]
        → PUT /api/v1/jobs/{namespace}/{name}/sla
        → [Success toast] "SLA configured. Monitoring active for next run."
        → [Slide-over closes; job detail page shows SLA badge]

─────────────────────────────────────────────────────────────────────────
Validation:  Deadline must be > (expected_start + runtime_p90). Show inline error if not.
Edit flow:   Same panel, pre-populated with existing config.
Delete:      "Remove SLA" link at bottom of panel with confirmation dialog.
```

---

### Flow 3 — Blast Radius During Incident
**Persona:** Batch Ops Engineer
**Entry point:** Alert notification (Slack/PagerDuty webhook) → deep link to job

```
Step 1: [Ops engineer receives Slack alert]
        "⚠️ AT RISK: customer_orders_etl — predicted 05:52, SLA 06:00 (8 min buffer)
         [View in Marquez →]"
        → [Deep link opens /batch-monitor?highlight=customer_orders_etl]
        → [Job row auto-expanded; blast radius expanded by default when accessed via alert link]

Step 2: [Blast radius shows]
        Blocking (deadline at risk):
          ❌ revenue_report_daily — deadline 06:00 (0 min buffer if parent is late)
        At risk (will miss if parent > 30 min late):
          ⚠️ customer_churn_model_training — deadline 08:00
        Safe (enough buffer):
          ✅ dashboard_refresh — deadline 10:00

Step 3: [User clicks revenue_report_daily in blast radius]
        → [Navigates to revenue_report_daily job detail page]
        → [SLA badge shows BLOCKED (dependency not yet complete)]

─────────────────────────────────────────────────────────────────────────
Max depth:    Blast radius shows 3 hops by default. "Show more (2 more hops)" expands.
No SLA data: Downstream jobs without SLA config show grey badge "No SLA configured".
```

---

## Screen Inventory

| Route | Change | New / Modified |
|---|---|---|
| `/batch-monitor` | New — batch window dashboard | New |
| `/jobs/{namespace}/{name}` | Add: SLA badge, "Configure SLA" button, reliability score | Modified |
| `/runs/{id}` | Add: `Predicted completion` field, `SLA status` field | Modified |
| `/datasets/{namespace}/{name}` | Add: `Freshness status` badge | Modified |
| Global nav sidebar | Add: "Batch Monitor" link with status dot (red if any BREACHED) | Modified |

---

## Component Specs

### Component: `BatchWindowDashboard`
**MUI base:** `Table` + `TableBody` + `Collapse` (row expansion) + `Alert` (summary bar)

```
Props:
  namespace: string
  jobs: SlaJobStatus[]    // from src/types/batchMonitor.ts
  isLoading: boolean

Table columns:
  1. Status badge   — Chip: ON_TRACK (green) / AT_RISK (amber) / BREACHED (red) / NO_SLA (grey)
  2. Job name       — link to job detail page
  3. Namespace      — text
  4. Started        — relative time ("2h 03m ago") with absolute on hover
  5. ETA            — absolute time ("05:48 UTC") with amber color if AT_RISK
  6. Deadline       — absolute time ("06:00 UTC")
  7. Buffer         — "12 min" in green; "4 min" in amber; "OVERDUE 8m" in red

Sort order:  BREACHED first, then AT_RISK, then ON_TRACK, all sorted by deadline ASC within group

Row expansion (Collapse):
  - ETA explanation: "Based on p90 historical duration (3h 20m). Elapsed: 3h 02m."
  - Historical reliability: LinearProgress bar + "94% of last 30 runs met SLA"
  - Blast radius: TreeView with status badges per downstream job (collapsed by default)
  - Actions: [Ask Marquez ↗] [View job →] [View current run →]

Accessibility:
  Table has aria-label="Batch window status"
  Status badges: aria-label="Status: AT RISK" (not just color)
  Expanded row: aria-expanded="true" on the trigger row
  Status dot in sidebar: aria-label="1 job breaching SLA" when red
```

---

### Component: `SlaStatusBadge`
**MUI base:** `Chip` size="small"

```
Props:
  status: 'ON_TRACK' | 'AT_RISK' | 'BREACHED' | 'NO_SLA' | 'BLOCKED'

Rendering:
  ON_TRACK: green chip,  icon=CheckCircle,   label="On track"
  AT_RISK:  amber chip,  icon=Warning,       label="At risk"
  BREACHED: red chip,    icon=Error,         label="Breached"
  BLOCKED:  grey chip,   icon=Block,         label="Blocked"
  NO_SLA:   grey chip,   icon=HelpOutline,   label="No SLA"

Accessibility:
  aria-label: "SLA status: [label]" (never color-only)
```

---

### Component: `SlaConfigPanel`
**MUI base:** `Drawer` anchor="right" + `TextField` + `ToggleButtonGroup` (days) + `TimePicker`

```
Props:
  jobName: string
  namespace: string
  existingConfig?: SlaConfig
  onSave: (config: SlaConfig) => void
  onClose: () => void

States:
  create:  empty form, "Configure SLA" title
  edit:    pre-populated form, "Update SLA" title
  saving:  submit button shows CircularProgress, form disabled
  saved:   panel closes, success toast

Live preview section (below form):
  "Alert will fire if predicted completion exceeds [deadline] [timezone]
   on [selected days]."
  Shows p50/p90 historical durations if ≥ 5 runs exist.
  If < 5 runs: "Not enough run history for preview. SLA will use configured
   runtime_p90 until 5 runs are recorded."

Accessibility:
  Drawer: role="dialog" aria-label="Configure SLA for [jobName]"
  Focus trap: Tab cycles within drawer; Esc closes (with discard confirmation if dirty)
  Time pickers: aria-label="Expected start time" / "SLA deadline"
```

---

### Component: `BlastRadiusTree`
**MUI base:** `TreeView` + `TreeItem` + `SlaStatusBadge`

```
Props:
  rootJob: string
  nodes: BlastRadiusNode[]   // { jobName, namespace, slaStatus, deadline, depth }
  maxDepth: number           // default 3
  onJobClick: (job) => void

States:
  empty:    "No downstream dependencies found."
  shallow:  Shows all nodes within maxDepth
  deep:     Shows maxDepth nodes + "Show N more hops" button

Node rendering:
  [SlaStatusBadge] [job name link] [namespace chip] [deadline if configured]

Grouping (optional expand):
  BLOCKED / AT_RISK nodes are always visible
  ON_TRACK nodes collapsed under "N safe downstream jobs ▾"

Accessibility:
  TreeView: aria-label="Downstream blast radius for [rootJob]"
  Each TreeItem: aria-label="[jobName]: [status]"
```

---

### Component: `FreshnessBadge` (Dataset detail page)
**MUI base:** `Chip` size="small" with `Tooltip`

```
Props:
  status: 'FRESH' | 'STALE' | 'UNKNOWN'
  lastUpdated: string       // ISO timestamp
  expectedCadenceHours?: number

Rendering:
  FRESH:   green chip, "Fresh" — tooltip: "Updated [relative] ago. Expected every [N]h."
  STALE:   amber chip, "Stale" — tooltip: "Last updated [relative] ago. Expected every [N]h."
  UNKNOWN: grey chip,  "Unknown" — tooltip: "No SLA configured for the producing job."
```

---

## Accessibility Checklist

| Element | ARIA label | Keyboard | Focus management |
|---|---|---|---|
| Summary bar | "Batch window summary: X on track, Y at risk, Z breached" | n/a (display) | — |
| Table row (expandable) | aria-expanded on trigger | Enter/Space to expand | Focus stays on row trigger |
| Status badges | "Status: [ON_TRACK/AT_RISK/BREACHED]" | n/a (display) | — |
| Status dot in sidebar | "1 job breaching SLA" (dynamic) | n/a | — |
| SLA config drawer | role=dialog, aria-label="Configure SLA for [job]" | Esc=close, Tab=cycle | Focus trap; on close returns to trigger button |
| Blast radius tree | aria-label="Downstream blast radius for [job]" | Arrow keys=navigate | — |
| Freshness badge | "Freshness status: [FRESH/STALE/UNKNOWN]" | n/a | — |

---

## Open Questions for Architect

1. Should the batch window dashboard auto-refresh on a timer (e.g., every 30s) or require manual refresh? Auto-refresh risks disorienting the user mid-read during an incident.
2. The `BlastRadiusTree` uses the AGE graph which may lag behind the real-time runs table. Should we show a "graph data as of [timestamp]" disclaimer?
3. Deep links from Slack alerts need to expand the relevant row automatically. Does the router support hash-based expansion (`/batch-monitor#customer_orders_etl`)?

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
