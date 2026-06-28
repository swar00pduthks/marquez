# UX Design: Natural Language Lineage Agent

**Phase:** 2b — UX Design
**Agent:** UX Designer (`bmad/agents/ux-designer-agent.md`)
**Status:** Draft
**PRD:** `specs/natural-language-lineage-agent/prd.md`

---

## Design Principle

Graph navigation is dead as the primary UX. The chat bar is the front door. Every user — from CDO to data engineer to app developer — should be able to get their lineage answer in one conversational turn without learning the graph model.

---

## User Flows

### Flow 1 — Data Engineer: Incident debugging
**Persona:** Data Engineer
**Entry point:** Global nav chat bar (visible on all pages)

```
Step 1: [User types] "why did customer_orders_etl fail last night?"
        → [System] sends POST /api/v1/agent/query, shows typing indicator
        → [UI] typing indicator: "Checking run history..." (2s)

Step 2: [System returns answer]
        → [UI] renders:
            Answer text: "The customer_orders_etl job failed at 02:14 UTC on
            run a3f9c (FAILED). Root cause: its input dataset
            [raw_transactions] had not been updated since 2024-03-14 18:00 UTC —
            14 hours before the run started. The job failed at the schema
            validation step."
            Citations: [chip: "run a3f9c"] [chip: "raw_transactions dataset"]
            [chip: "customer_orders_etl job"]
            Suggested follow-ups: [chip: "What depends on customer_orders_etl?"]
                                   [chip: "When was raw_transactions last updated?"]
                                   [chip: "Show me the last 5 runs of this job"]

Step 3: [User clicks suggested follow-up] "What depends on customer_orders_etl?"
        → [System] uses session context (no re-typing the job name)
        → [UI] renders blast radius as ordered list:
            "3 jobs depend on customer_orders_etl:
             1. [revenue_report_daily] — deadline 06:00, currently BLOCKED
             2. [customer_churn_model_training] — deadline 08:00, at risk
             3. [dashboard_refresh] — deadline 10:00, on track"

─────────────────────────────────────────────────────────────────────────
Empty state:    "Ask me anything about your data lineage..."  (placeholder)
Loading state:  Typing indicator with rotating status message ("Checking runs...",
                "Traversing lineage graph...", "Synthesizing answer...")
Error state:    Alert banner: "I couldn't find an answer. [Try rephrasing] or
                [browse the graph manually]." Never show a raw error message.
Success state:  Answer + citations + suggested follow-ups rendered inline
```

---

### Flow 2 — Business User: Report provenance
**Persona:** Business User
**Entry point:** Batch Monitor page → "Ask about this batch window" button

```
Step 1: [User types] "where does the morning revenue report data come from?"
        → [System] interprets "morning revenue report" by searching job names
          matching "revenue" in the current namespace

Step 2: [UI renders]
        Answer: "The Morning Revenue Report is populated by the
        revenue_report_daily job (last run: today 05:47 UTC, COMPLETE ✅).
        That job reads data from:
         • [transactions] — updated today 03:12 UTC (source: Stripe)
         • [customer_profiles] — updated today 01:05 UTC (source: Salesforce)
         • [exchange_rates] — updated yesterday 23:00 UTC (source: ECB feed)"
        Citations: [revenue_report_daily] [transactions] [customer_profiles]
                   [exchange_rates]
        Follow-ups: [chip: "Is the exchange_rates data up to date?"]
                    [chip: "Who owns the transactions dataset?"]

─────────────────────────────────────────────────────────────────────────
Ambiguous entity: If "morning revenue report" matches multiple jobs, show:
    "I found 2 jobs matching 'revenue report'. Which did you mean?"
    [revenue_report_daily] [revenue_report_weekly]
```

---

### Flow 3 — App Developer: Schema impact analysis
**Persona:** App Developer
**Entry point:** Dataset detail page → "Ask about this dataset" contextual button

```
Step 1: [User is on transactions dataset page, clicks "Ask about this dataset"]
        → [Chat bar pre-fills namespace and dataset context]

Step 2: [User types] "what breaks if column user_id is renamed?"
        → [System calls schema impact tool with pre-filled context]

Step 3: [UI renders]
        Answer: "Renaming user_id in transactions would affect:
         Jobs (4):
          • [customer_orders_etl] — references user_id in its column lineage
          • [churn_model_feature_eng] — reads user_id as a feature
          • [revenue_attribution] — joins on user_id
          • [audit_log_sync] — includes user_id in output schema
         Applications (1):
          • [recommendation-api] — registered consumer of transactions"
        Citations: + "View full impact graph →" (opens graph scoped to this)

─────────────────────────────────────────────────────────────────────────
No consumers registered: "No application consumers are registered for this
dataset. [Register your application] to appear in impact analysis."
```

---

## Screen Inventory

| Route | Change | New / Modified |
|---|---|---|
| All pages | Persistent chat bar in top navigation | Modified (global nav) |
| `/` (home) | Chat bar prominent, replaces "explore graph" as CTA | Modified |
| `/agent` | Full-page chat view for extended conversations | New |
| `/agent/sessions/{id}` | Shareable conversation permalink | New |
| Entity detail pages (jobs, datasets, runs) | "Ask about this [entity]" contextual button | Modified |
| Batch Monitor | "Ask about this batch window" button | Modified |

---

## Component Specs

### Component: `LineageAgentChatBar`
**MUI base:** `TextField` (outlined, rounded) + `IconButton` (send) + `Tooltip`
**Placement:** Top navigation, always visible

```
Props:
  onSubmit: (question: string, sessionId?: string) => void
  namespace: string
  placeholder?: string

States:
  idle:     TextField with placeholder "Ask about your data lineage..."
  loading:  TextField disabled, send button replaced with CircularProgress (size=20)
  error:    TextField border turns error color; helper text: "Try rephrasing your question"

Interactions:
  Enter key: submits question
  Shift+Enter: newline (for multi-sentence questions)
  Esc: clears input

Accessibility:
  aria-label: "Ask a lineage question"
  role: "search"
  Loading state: aria-busy="true" on the TextField
```

---

### Component: `AgentAnswerThread`
**MUI base:** `Box` (scroll container) + `Paper` (per message) + `Chip` (citations + follow-ups)

```
Props:
  messages: AgentMessage[]    // from src/types/agent.ts
  onFollowUp: (question: string) => void
  isLoading: boolean

States:
  empty:     Empty state illustration: magnifying glass + "Ask your first question above"
  loading:   Skeleton for last message (3 lines, citation row)
  populated: Scrollable thread; newest message at bottom; auto-scroll on new message

Message anatomy:
  ┌─────────────────────────────────────────────────────┐
  │ You                              [timestamp]         │
  │ "why did customer_orders_etl fail last night?"       │
  ├─────────────────────────────────────────────────────┤
  │ Marquez                          [timestamp]         │
  │ [answer text — rendered as Markdown]                 │
  │                                                      │
  │ Sources: [run a3f9c ↗] [raw_transactions ↗]         │
  │ Follow-ups: [What depends on this job?] [Show runs]  │
  └─────────────────────────────────────────────────────┘

Interactions:
  Citation chip click: navigates to entity detail page (new tab)
  Follow-up chip click: calls onFollowUp(question)
  Copy icon on answer: copies answer text to clipboard

Accessibility:
  role="log" aria-live="polite" on the scroll container
  Each message: role="article"
  Citation chips: role="link" with descriptive aria-label ("View run a3f9c")
  Follow-up chips: role="button"
  Focus: after new message renders, focus moves to the new message
```

---

### Component: `EntityAskButton`
**MUI base:** `Button` variant="outlined" size="small"
**Placement:** Top-right of Job, Dataset, Run detail pages

```
Props:
  entityType: 'job' | 'dataset' | 'run'
  entityName: string
  namespace: string

Behaviour:
  Click → opens /agent with pre-filled context:
    "Tell me about [entityName] in [namespace]"
  Opens in same tab (not new tab) to preserve session context

Accessibility:
  aria-label: "Ask about [entityType] [entityName]"
```

---

### Component: `AgentTypingIndicator`
**MUI base:** `Box` + `Typography` + animated `CircularProgress`

```
States (rotate every 2s):
  "Checking run history..."
  "Traversing lineage graph..."
  "Synthesizing answer..."

Accessibility:
  aria-live="polite"
  aria-label="Marquez is generating an answer"
```

---

## Accessibility Checklist

| Element | ARIA label | Keyboard | Focus management |
|---|---|---|---|
| Chat bar input | "Ask a lineage question" | Enter=submit, Shift+Enter=newline, Esc=clear | Focus retained after submit |
| Send button | "Submit question" | Enter/Space | — |
| Loading indicator | "Marquez is generating an answer" (live region) | n/a | — |
| Answer scroll container | role=log, aria-live=polite | Arrow keys scroll | Focus moves to new message after load |
| Citation chips | "View [entity type] [name]" | Enter/Space=navigate | Returns focus to chip on back-navigate |
| Follow-up chips | "Ask: [question text]" | Enter/Space=submit | Returns focus to chat bar after submit |
| Entity ask button | "Ask about [type] [name]" | Enter/Space | Focus moves to chat bar in /agent |

---

## Open Questions for Architect

1. Should the chat bar maintain its session across page navigations (SPA routing), or start a new session when the user navigates to a different entity page?
2. How do we render Markdown in the answer text safely (XSS risk if answer contains user-generated entity names)?
3. Citation links open entity pages — do they share the current session context, or are they independent navigations?

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
