# PRD: Natural Language Lineage Agent

**Author(s):** BMAD PM Agent
**Created:** 2026-06-28
**Last Updated:** 2026-06-28
**Status:** `Draft`
**Linked Proposal/Issue:** #TBD
**Target Release:** unscheduled

---

## 1. Overview

### 1.1 Problem Statement

Marquez's primary interaction model is a graph visualizer: users navigate nodes and edges to understand data lineage. This model breaks down for four reasons at data mesh scale: (1) graphs with 100+ nodes from 50+ tenant teams are visually illegible; (2) non-technical users (analysts, business users, app developers) cannot interpret a graph; (3) operational questions ("why did this job fail?", "what breaks if I change this schema?") require multiple clicks across multiple pages; and (4) AI/LLM pipeline engineers need to ask questions that don't map to graph traversal at all. The result is that the most valuable lineage knowledge in Marquez is locked behind an interface most users cannot effectively use.

### 1.2 Proposed Solution

A conversational **Lineage Agent** — a natural language interface powered by the Claude API — that answers lineage questions in plain English. The agent has access to the Marquez REST API and the Apache AGE graph as tools, translates user questions into structured queries, and returns clear, cited answers. It is the default entry point for lineage exploration, with the graph available as a drill-down detail view, not the primary interface.

Example interactions:
- *"What datasets does the `customer_orders_spark` job depend on?"*
- *"Why did the ETL pipeline fail last night and what is the blast radius?"*
- *"If I rename column `user_id` in `transactions`, what jobs and apps break?"*
- *"Which team owns the dataset feeding the ML training pipeline in the `ml_platform` namespace?"*
- *"Show me all jobs in the `analytics` namespace that took longer than 2 hours this week."*
- *"Trace the lineage of the data in my last API response back to its source."* (AI engineer use case)
- *"Are all my batch jobs on track to complete by 6 AM?"* (batch ops use case)

### 1.3 Background & Context

Marquez already has the data to answer all of these questions: Apache AGE graph (marquez_v3 schema, 8 vertex labels, 14 edge types) stores the full lineage graph; the REST API exposes runs, datasets, jobs, and column lineage. The missing layer is a natural language query translator that converts a user question into Cypher graph queries + REST API calls and returns a synthesized answer.

Claude API with tool use is the right implementation vehicle: it can generate Cypher queries as tool calls, call the Marquez REST API, and synthesize results into natural language. The agent does not need its own storage — it is a stateless query layer over existing Marquez data.

Competitive context: DataHub has introduced "DataHub AI Assistant"; OpenMetadata has "OpenMetadata AI"; neither has deep graph traversal capability or multi-turn conversational context. This is a meaningful differentiator for Marquez.

---

## 2. Goals & Non-Goals

### Goals

- Enable any Marquez user — regardless of technical level — to get lineage answers in plain English without learning the graph model.
- Reduce time-to-answer for the 10 most common lineage questions from "several clicks over 2-3 pages" to "one conversational turn."
- Support AI engineer, batch ops, application developer, and analyst personas out of the box with domain-appropriate question understanding.
- Integrate with the Apache AGE graph for deep multi-hop traversal, not just REST API lookups.
- Provide cited answers — every claim references the specific Marquez entity (job name, dataset name, run UUID) it came from.

### Non-Goals

- This is NOT a replacement for the existing REST API — the API remains the programmatic interface.
- This is NOT training a custom model — the agent uses Claude API with tool use; no model training or fine-tuning.
- This is NOT a general-purpose SQL or Cypher query interface — it is a natural language interface with curated tool capabilities.
- This does NOT replace the lineage graph view — the graph remains available as a detail drill-down.
- Multi-language support (non-English) is out of scope for v1.

---

## 3. User Stories

| ID | Persona | Story | Priority |
|----|---------|-------|----------|
| US-1 | Data Engineer | As a Data Engineer, I want to ask "what does job X depend on?" and get a list with freshness indicators so I can debug pipeline failures without clicking through the graph. | `P0` |
| US-2 | Batch Ops Engineer | As a Batch Ops Engineer, I want to ask "what is the blast radius if job X fails?" and get an ordered list of blocked downstream jobs and datasets so I can triage during an incident. | `P0` |
| US-3 | Business User | As a Business User, I want to ask "where does the revenue report data come from?" in plain English and get a clear answer with source systems named, without needing to understand lineage graphs. | `P0` |
| US-4 | AI Engineer | As an AI Engineer, I want to ask "why did this agent chain produce an incorrect output on run X?" and trace back through the prompt, retrieved context, and model version involved. | `P1` |
| US-5 | App Developer | As an Application Developer, I want to ask "what happens to my service if the transactions table schema changes?" and get a list of affected jobs and applications. | `P1` |
| US-6 | Data Analyst | As a Data Analyst, I want to ask "is the dataset I'm using in my report up to date?" and get the last successful run time and freshness status. | `P1` |
| US-7 | Platform Engineer | As a Platform Engineer, I want to ask "which namespaces are producing the most lineage events this week?" to understand system load by team. | `P2` |

---

## 4. Functional Requirements

### 4.1 Must Have (P0)

- `[FR-1]` Natural language question input in the Marquez web UI — a chat-style interface accessible from the global navigation.
- `[FR-2]` Agent uses Claude API with tool use. Tools include: `get_job_lineage`, `get_dataset_lineage`, `get_run_details`, `get_column_lineage`, `execute_cypher_query` (read-only, against AGE graph), `get_downstream_impact`.
- `[FR-3]` Every answer cites the specific Marquez entities (job name, dataset name, namespace, run UUID) it was derived from, as clickable links to the existing detail pages.
- `[FR-4]` Multi-turn context within a session — the agent remembers the context of the conversation ("what about its upstream?" refers to the dataset mentioned in the previous turn).
- `[FR-5]` Namespace-scoped access — the agent only queries data within the user's authorized namespace(s). Never returns data across tenant boundaries.
- `[FR-6]` The `execute_cypher_query` tool enforces read-only access (`MATCH` only; `CREATE`, `MERGE`, `DELETE` are blocked at the tool layer).
- `[FR-7]` API endpoint: `POST /api/v1/agent/query` accepts `{ "question": "...", "namespace": "...", "session_id": "..." }`, returns `{ "answer": "...", "citations": [...], "suggested_followups": [...] }`.

### 4.2 Should Have (P1)

- `[FR-8]` Suggested follow-up questions after each answer (e.g., after "what does job X depend on?", suggest "which of those datasets was last updated more than 24 hours ago?").
- `[FR-9]` Schema impact analysis tool: given a dataset and a proposed column change (rename/type change/drop), return the list of jobs and registered application consumers that would be affected.
- `[FR-10]` AI workload context: understand AI-specific entities (prompt template, model name, token usage, retrieval source) when querying runs from AI pipelines that emit the appropriate OpenLineage facets.
- `[FR-11]` Batch window context: understand "is job X on track?" by consulting historical run duration data and current run state. Delegate to predictive ETA (see batch monitoring PRD) when available.

### 4.3 Nice to Have (P2)

- `[FR-12]` Slack / webhook integration — route answers to a Slack channel via `/marquez what datasets does job X depend on?`.
- `[FR-13]` Saved questions — save a question + answer snapshot as a permalink for sharing.
- `[FR-14]` Answer confidence indicator — when the agent is uncertain (e.g., the lineage graph has gaps), state the uncertainty explicitly rather than guessing.

---

## 5. Non-Functional Requirements

| Attribute | Requirement |
|-----------|-------------|
| Latency | p95 answer latency ≤ 5 seconds for questions that require ≤ 3 tool calls. Complex multi-hop traversals ≤ 15 seconds. |
| Scalability | Stateless API layer; scales horizontally with API pods. Claude API rate limits are the external constraint. |
| Security | `execute_cypher_query` tool: `MATCH`-only, parameterized, namespace-scoped. No raw user input concatenated into Cypher. RLS enforced at the PostgreSQL layer. |
| Cost | Claude API token cost per query must be bounded. Max 4 tool call rounds per query; context window limited to last 10 turns. Log token usage per query to `METRICS.md` Prometheus counter. |
| Backward Compatibility | New endpoint `/api/v1/agent/query` — no existing endpoints modified. |
| Availability | Agent unavailability (Claude API down) must degrade gracefully: return HTTP 503 with a clear message, not a 500. The rest of Marquez is unaffected. |
| Observability | Prometheus metrics: `marquez_agent_query_total{result="success|error"}`, `marquez_agent_query_duration_seconds`, `marquez_agent_tool_calls_total{tool="..."}`, `marquez_agent_claude_tokens_total{type="input|output"}`. |

---

## 6. API / UX Surface Changes

### 6.1 New API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/agent/query` | Submit a natural language question; returns answer + citations + follow-ups |
| `GET` | `/api/v1/agent/sessions/{session_id}` | Retrieve conversation history for a session |
| `DELETE` | `/api/v1/agent/sessions/{session_id}` | Clear session context |

### 6.2 UI Changes

- **Global navigation**: replace "Lineage Graph" as the default landing with a **search/chat bar** prominently placed. The graph becomes a drill-down available from entity detail pages and from agent citations.
- **Chat interface**: full-width, conversational panel. Input at bottom; scrollable answer thread above. Citations render as chips linking to Marquez entity pages.
- **Suggested follow-ups**: rendered as clickable chips below each answer.
- **Namespace selector**: persistent in the chat header so the user's scope is always visible.

### 6.3 Client Library Impact

- Python client: add `MarquezClient.agent.query(question, namespace, session_id)` helper.
- Java client: add `AgentClient.query(AgentQuery)` returning `AgentAnswer`.

---

## 7. Data Model Impact

| Change | Type | Backward Compatible? |
|--------|------|----------------------|
| New table `agent_sessions(session_id UUID PK, namespace TEXT, created_at TIMESTAMPTZ, last_active_at TIMESTAMPTZ)` | Additive | Yes |
| New table `agent_messages(id UUID PK, session_id UUID FK, role TEXT, content TEXT, citations JSONB, created_at TIMESTAMPTZ)` — RANGE partitioned by created_at | Additive | Yes |
| New table `agent_tool_invocations(id UUID PK, message_id UUID FK, tool_name TEXT, input JSONB, output JSONB, duration_ms INT, created_at TIMESTAMPTZ)` | Additive | Yes |

Session data retention: 30 days (partition drop). Tool invocation logs: 90 days.

---

## 8. Open Questions

| # | Question | Owner | Resolution |
|---|----------|-------|------------|
| 1 | Which Claude model for the agent? Opus for better Cypher generation vs Sonnet for cost/latency? | Architect | Pending — recommend starting with Sonnet, A/B test against Opus on complex queries |
| 2 | How do we handle the case where the AGE graph has not yet been backfilled (async consumer lag)? | Architect | Agent should state "lineage data may be up to N minutes delayed" when querying graph |
| 3 | Should session history be stored server-side or client-side (browser localStorage)? | PM + Architect | Server-side preferred for cross-device, audit, and future sharing features |
| 4 | Cypher query safety: should we use a Cypher parser to validate MATCH-only before execution, or rely on PostgreSQL RLS? | Security QA | Both: parse at agent tool layer + RLS at DB layer |
| 5 | Does the agent need to support questions about AI workload lineage (prompt versions, token counts) in v1, or is that a v2 scope? | PM | Recommend v1 includes the query support if the run facets exist; adding AI facets to the data model is a separate story |

---

## 9. Definition of Done

- [ ] All P0 functional requirements implemented and verified
- [ ] `POST /api/v1/agent/query` responds correctly to the 7 canonical questions in the user stories above
- [ ] Cypher injection test suite passes (Security QA agent sign-off)
- [ ] Namespace isolation verified: agent cannot return data from a namespace the requesting user is not authorized for
- [ ] Claude API token cost per query logged and within budget (< $0.01/query at Sonnet pricing)
- [ ] `docs/openapi.yml` updated with new agent endpoints
- [ ] `METRICS.md` updated with new Prometheus metrics
- [ ] Python and Java client helpers implemented and tested
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] All open questions resolved

---

## 10. Out of Scope (Explicit Exclusions)

- **Graph visualization replacement**: the graph view remains; this adds a query layer, it does not remove the graph.
- **Write operations via the agent**: the agent is read-only. Users cannot create, update, or delete Marquez entities through the chat interface.
- **Custom model training**: no fine-tuning; Claude API with tool use only.
- **Non-English language support**: v1 English only.
- **Real-time streaming answers**: v1 returns complete answers; streaming (SSE) is a v2 optimization.

---

## Revision History

| Date | Author | Change |
|------|--------|--------|
| 2026-06-28 | BMAD PM Agent | Initial draft |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
