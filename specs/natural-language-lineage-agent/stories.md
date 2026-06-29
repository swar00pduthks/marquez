# Stories: Natural Language Lineage Agent

**Feature Spec:** `specs/natural-language-lineage-agent/spec.md`
**PRD:** `specs/natural-language-lineage-agent/prd.md`
**UX Design:** `specs/natural-language-lineage-agent/ux.md`
**Created:** 2026-06-28
**SM Agent review:** 2026-06-28

---

## Summary

Add a conversational AI layer to Marquez that lets any user — data engineer, business analyst, or application developer — query data lineage in plain English. The agent uses Claude's tool-use API with six Marquez-backed tools to traverse the lineage graph, retrieve run history, and synthesize answers with entity citations. Sessions are persisted so conversations are shareable and auditable.

---

## Story Map

```
Story 1 (DB Migration: agent tables)
    └── Story 2 (DAO Layer: tool query methods)
            └── Story 3 (AgentService: tool-use loop + Claude API)
                    └── Story 4 (AgentResource: POST /agent/query, GET /agent/sessions)
                            ├── Story 5 (Frontend: Redux slice + types)
                            │       └── Story 6 (Frontend: ChatBar + AnswerThread)
                            │               └── Story 7 (Frontend: EntityAskButton + nav integration)
                            └── Story 8 (Client SDK helpers: Python + Java)
                                    └── Story 9 (Docs update)
```

---

## Stories

---

## Story 1: DB Migration — Agent Session Tables
**Status:** TODO
**Size:** S
**Modules:** [API]
**Depends on:** none

### Context
Three new tables store agent conversation state: `agent_sessions` (one row per conversation), `agent_messages` (one row per user or assistant turn), and `agent_tool_invocations` (one row per Claude tool call within a turn). All three are RANGE-partitioned by `created_at` per the data mesh scaling prerequisite — never add them without partitioning. This story unblocks all backend stories.

The next available Flyway version is V106 (V105 is the current head; confirm by running `ls api/src/main/resources/marquez/db/migration/ | sort | tail -3`).

### Tasks
- [ ] Confirm highest existing migration version with `ls api/src/main/resources/marquez/db/migration/ | sort | tail -3`
- [ ] Create `api/src/main/resources/marquez/db/migration/V106__add_agent_session_tables.sql` with:
  - `agent_sessions` table (uuid PK, namespace, created_at, updated_at, metadata jsonb)
  - `agent_messages` table (uuid PK, session_id FK, role enum, content text, created_at)
  - `agent_tool_invocations` table (uuid PK, message_id FK, tool_name, input jsonb, output jsonb, duration_ms, created_at)
  - RANGE partition by `created_at` on all three tables (monthly partition template)
  - Indexes: `(session_id, created_at)` on agent_messages; `(message_id)` on agent_tool_invocations
- [ ] Verify migration is backward-compatible (no NOT NULL without defaults, no drops)
- [ ] Run `./gradlew :api:flywayMigrate` against a local DB and confirm success
- [ ] Write a test that verifies the migration applies cleanly to a fresh schema

### Acceptance Criteria
- AC1: Migration file named `V106__add_agent_session_tables.sql` (two underscores, correct version)
- AC2: All three tables are RANGE-partitioned by `created_at`; a default partition catches overflow rows
- AC3: Migration applies without error on a fresh PostgreSQL 14 database
- AC4: Migration applies without error on a database with V1–V105 already applied
- AC5: `./gradlew check` passes with the new migration file present

---

## Story 2: DAO Layer — Lineage Tool Query Methods
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 1

### Context
The six Claude tools need DAO methods that read from the existing Marquez tables and the AGE graph. All reads must use the `marquez_reader` (read replica) connection pool — never the writer. AGE Cypher queries require the `marquez_heavy_reader` pool (`work_mem=256MB`, `statement_timeout=30s`). No new SQL may be added to any synchronous write path.

Relevant existing DAOs to extend or reference: `JobDao`, `DatasetDao`, `RunDao`, `OpenLineageDao`.

### Tasks
- [ ] Create `AgentToolDao.java` in `marquez.db` with JDBI3 annotations on the `marquez_reader` handle
- [ ] Implement `getJobLineage(namespace, jobName, depth)` — fetches job + input/output datasets up to `depth` hops
- [ ] Implement `getDatasetLineage(namespace, datasetName, depth)` — fetches dataset + producing/consuming jobs
- [ ] Implement `getRunDetails(runId)` — fetches run row + facets + input/output datasets from existing tables
- [ ] Implement `getColumnLineage(namespace, datasetName, fieldName)` — traces column-level lineage via `column_lineage` table
- [ ] Implement `getDownstreamImpact(namespace, jobName, maxHops)` — traverses AGE graph via `marquez_heavy_reader`; use `ag_catalog.cypher()` with `SET search_path = ag_catalog` preamble; MATCH-only, no writes
- [ ] Create `AgentSessionDao.java` for CRUD on `agent_sessions`, `agent_messages`, `agent_tool_invocations`
- [ ] Create `AgentSessionRow.java`, `AgentMessageRow.java`, `AgentToolInvocationRow.java` in `marquez.db.models`
- [ ] Add Apache 2.0 license header to all new files
- [ ] Write unit tests using TestContainers for all DAO methods
- [ ] Run `./gradlew spotlessApply pmdMain`

### Acceptance Criteria
- AC1: All read methods route through `marquez_reader` datasource (not the write datasource)
- AC2: `getDownstreamImpact` uses `marquez_heavy_reader` datasource and includes `SET search_path = ag_catalog` before `cypher()` call
- AC3: No new SQL touches `OpenLineageDao` or any synchronous write-path class
- AC4: `AgentSessionDao` can persist and retrieve a full session with messages and tool invocations
- AC5: Unit tests cover happy path, empty result, and not-found cases for each method
- AC6: `./gradlew check` passes with no new failures

---

## Story 3: AgentService — Tool-Use Loop and Claude API Integration
**Status:** TODO
**Size:** L
**Modules:** [API]
**Depends on:** Story 2

### Context
`AgentService` orchestrates the multi-turn Claude API call with tool use. It submits the user's question plus the six tool definitions, receives a tool-use response, calls the appropriate `AgentToolDao` method, submits the tool result back to Claude, and loops until Claude returns a final text response. The loop is capped at 10 tool calls per query to prevent runaway cost. Sessions and messages are persisted via `AgentSessionDao` after each turn.

The Claude API key is read from the `MARQUEZ_CLAUDE_API_KEY` environment variable (never hardcoded). Use the Claude `claude-sonnet-4-6` model with `max_tokens=4096`.

### Tasks
- [ ] Add `anthropic-java` SDK dependency to `api/build.gradle` (or use OkHttp if SDK unavailable)
- [ ] Create `AgentService.java` in `marquez.service`
- [ ] Implement `AgentService.query(sessionId, namespace, userQuestion)` → `AgentResponse`:
  - Build `messages` array (load prior session messages if `sessionId` non-null)
  - Build `tools` array with all 6 tool definitions (schemas from PRD section 7.1)
  - POST to Claude API; handle `tool_use` stop reason by dispatching to `AgentToolDao`
  - Loop up to 10 iterations; throw `AgentToolLimitExceededException` if exceeded
  - Persist each user message, assistant message, and tool invocation to DB via `AgentSessionDao`
- [ ] Implement `AgentService.getSession(sessionId)` → `AgentSessionResponse` with full message history
- [ ] Add `MARQUEZ_CLAUDE_API_KEY` to `MarquezConfig.java` (required; start-up fails if absent)
- [ ] Add Prometheus counters: `marquez_agent_queries_total{status}`, `marquez_agent_tool_calls_total{tool}`, histogram `marquez_agent_query_duration_seconds`
- [ ] Write unit tests with mocked Claude API (WireMock) and mocked `AgentToolDao`
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: `query()` returns a final answer after ≤ 10 tool calls; throws `AgentToolLimitExceededException` if limit reached
- AC2: All six tool names dispatch to the correct `AgentToolDao` method; unknown tool names return a graceful error message (not an exception)
- AC3: Session messages are persisted to DB in correct order (user → assistant tool_use → tool_result → assistant final)
- AC4: `MARQUEZ_CLAUDE_API_KEY` absent at startup → `IllegalStateException` with descriptive message (not NPE at query time)
- AC5: Prometheus metrics emitted on every query (success, error, tool-limit)
- AC6: Unit tests cover: happy path (single tool call), multi-tool chain (3 calls), tool limit exceeded, Claude API error (502), tool execution error
- AC7: `./gradlew check` passes

---

## Story 4: AgentResource — REST Endpoints
**Status:** TODO
**Size:** M
**Modules:** [API]
**Depends on:** Story 3

### Context
Two endpoints expose the agent: `POST /api/v1/agent/query` starts or continues a conversation and returns the agent's answer; `GET /api/v1/agent/sessions/{id}` returns a persisted session for sharing/auditing. The namespace is taken from the request body (not the path) so one endpoint works across namespaces.

### Tasks
- [ ] Create `AgentResource.java` in `marquez.api`
- [ ] Implement `POST /api/v1/agent/query`:
  - Request body: `{ question: string, namespace: string, sessionId?: string }`
  - Response: `{ sessionId, answer, citations: [{entityType, name, uuid, url}], followUps: string[] }`
  - Returns `200` on success; `422` for missing `question`/`namespace`; `503` if Claude API unavailable
- [ ] Implement `GET /api/v1/agent/sessions/{id}`:
  - Response: `{ sessionId, messages: [{role, content, citations, toolInvocations, createdAt}] }`
  - Returns `404` if session not found
- [ ] Register resource in `MarquezApp.java`
- [ ] Instantiate `AgentService` in `MarquezContext.java` (inject `AgentToolDao`, `AgentSessionDao`)
- [ ] Update `docs/openapi.yml` with both endpoint definitions
- [ ] Write `AgentResourceTest.java` (unit, mocked service) and `AgentResourceIntegrationTest.java` (TestContainers)
- [ ] Add entry to `CHANGELOG.md` under `[Unreleased]`
- [ ] Run `./gradlew spotlessApply pmdMain check`

### Acceptance Criteria
- AC1: `POST /api/v1/agent/query` with valid body returns `200` with `sessionId`, `answer`, `citations`, `followUps`
- AC2: `POST /api/v1/agent/query` without `question` or `namespace` returns `422`
- AC3: `POST /api/v1/agent/query` with existing `sessionId` continues the session (prior messages sent to Claude)
- AC4: `GET /api/v1/agent/sessions/{id}` returns full message history for an existing session
- AC5: `GET /api/v1/agent/sessions/{id}` returns `404` for unknown session ID
- AC6: `docs/openapi.yml` updated and passes `swagger-cli validate docs/openapi.yml`
- AC7: Integration test covers round-trip query → session retrieval
- AC8: `CHANGELOG.md` updated under `[Unreleased]`
- AC9: `./gradlew check` passes

---

## Story 5: Frontend — Redux Slice and API Types
**Status:** TODO
**Size:** S
**Modules:** [WEB]
**Depends on:** Story 4

### Context
Wire the two new API endpoints into the React/Redux store. Types must match the OpenAPI response schema exactly. The slice handles three async states (pending, fulfilled, rejected) for both `submitQuery` and `fetchSession` thunks.

### Tasks
- [ ] Add TypeScript types to `web/src/types/agent.ts`:
  - `AgentQuery`, `AgentResponse`, `AgentMessage`, `AgentSession`, `AgentCitation`, `AgentToolInvocation`
- [ ] Create `web/src/requests/agentRequests.ts` with `postAgentQuery(body)` and `getAgentSession(id)`
- [ ] Create `web/src/store/agentSlice.ts`:
  - State: `{ currentSession: AgentSession | null, isLoading: boolean, error: string | null }`
  - Thunks: `submitQuery(question, namespace, sessionId?)`, `fetchSession(sessionId)`
  - Selectors: `selectCurrentSession`, `selectAgentIsLoading`, `selectAgentError`
- [ ] Register slice in `web/src/store/index.ts`
- [ ] Write Jest tests for the slice (all reducers + both thunks in pending/fulfilled/rejected states)
- [ ] Run `cd web && yarn test` and `yarn tsc --noEmit`

### Acceptance Criteria
- AC1: `AgentResponse` TypeScript type matches the `POST /api/v1/agent/query` response schema exactly
- AC2: Redux slice handles `pending`, `fulfilled`, and `rejected` for both thunks
- AC3: `submitQuery` thunk appends new messages to `currentSession` on success
- AC4: Slice tests pass with mocked API responses using MSW
- AC5: `yarn tsc --noEmit` reports zero type errors
- AC6: `yarn test` passes with no new failures

---

## Story 6: Frontend — ChatBar and AnswerThread Components
**Status:** TODO
**Size:** L
**Modules:** [WEB]
**Depends on:** Story 5

### Context
Implements the two primary chat UI components per `ux.md`:
- `LineageAgentChatBar` — persistent `TextField` in the top nav; submits questions; shows `CircularProgress` while loading
- `AgentAnswerThread` — scrollable conversation thread showing user and assistant messages with citation chips and follow-up chips

Both components dispatch to the Redux slice from Story 5. Markdown in agent answers must be rendered via a sanitized renderer (no raw `dangerouslySetInnerHTML`; use `react-markdown` with `rehype-sanitize`).

### Tasks
- [ ] Create `web/src/components/lineage-agent/LineageAgentChatBar.tsx`:
  - MUI `TextField` (outlined, rounded) + `IconButton` (send) + `CircularProgress` (loading state)
  - Enter submits; Shift+Enter adds newline; Esc clears
  - `aria-label="Ask a lineage question"`, `role="search"`, `aria-busy` on loading
- [ ] Create `web/src/components/lineage-agent/AgentAnswerThread.tsx`:
  - `role="log"` `aria-live="polite"` scroll container
  - Each message: `role="article"` with user/assistant distinction
  - Citation chips: MUI `Chip` with `role="link"` and descriptive `aria-label`
  - Follow-up chips: MUI `Chip` with `role="button"`; clicking dispatches `submitQuery`
  - Auto-scroll to latest message on new assistant response
  - Markdown rendered via `react-markdown` + `rehype-sanitize`
- [ ] Create `web/src/components/lineage-agent/AgentTypingIndicator.tsx`:
  - Rotates status text every 2s: "Checking run history..." → "Traversing lineage graph..." → "Synthesizing answer..."
  - `aria-live="polite"` `aria-label="Marquez is generating an answer"`
- [ ] Create `/agent` page at `web/src/pages/agent/index.tsx` combining ChatBar + AnswerThread
- [ ] Add `react-markdown` and `rehype-sanitize` to `web/package.json`
- [ ] Write React Testing Library tests for all three components (idle, loading, error, populated states)
- [ ] Run `cd web && yarn test` and manually verify in browser: happy path, error state, empty state, Markdown rendering, XSS attempt in answer text

### Acceptance Criteria
- AC1: `LineageAgentChatBar` submits on Enter, adds newline on Shift+Enter, clears on Esc
- AC2: `LineageAgentChatBar` shows `CircularProgress` (not send button) while `isLoading` is true
- AC3: `AgentAnswerThread` auto-scrolls to the newest message after each response
- AC4: Citation chips navigate to the entity detail page in a new tab
- AC5: Follow-up chips submit the pre-filled question via the Redux thunk (no re-typing)
- AC6: Agent answers containing Markdown (bold, lists, code spans) render correctly
- AC7: An answer containing `<script>alert(1)</script>` does NOT execute (XSS blocked by `rehype-sanitize`)
- AC8: RTL tests cover: idle empty state, loading indicator visible, answer rendered, citation chip present, follow-up chip dispatches action
- AC9: `yarn tsc --noEmit` and `yarn test` both pass

---

## Story 7: Frontend — EntityAskButton and Global Nav Integration
**Status:** TODO
**Size:** M
**Modules:** [WEB]
**Depends on:** Story 6

### Context
Adds the chat interface to the rest of the application: the `LineageAgentChatBar` is mounted in the global top navigation (visible on all pages), and `EntityAskButton` appears on Job, Dataset, and Run detail pages to open `/agent` with pre-filled context. The Batch Monitor page gets an "Ask about this batch window" button.

### Tasks
- [ ] Mount `LineageAgentChatBar` in the global top navigation component (locate via `Glob web/src/components/Navigation*`)
- [ ] Create `web/src/components/lineage-agent/EntityAskButton.tsx`:
  - Props: `entityType: 'job' | 'dataset' | 'run'`, `entityName: string`, `namespace: string`
  - Click navigates to `/agent` with pre-filled question `"Tell me about [entityName] in [namespace]"`
  - `aria-label="Ask about [entityType] [entityName]"`
- [ ] Add `EntityAskButton` to Job detail page (top-right, alongside existing actions)
- [ ] Add `EntityAskButton` to Dataset detail page
- [ ] Add `EntityAskButton` to Run detail page
- [ ] Add "Ask about this batch window" button to Batch Monitor page (wired to `EntityAskButton` with `entityType='namespace'`)
- [ ] Add "Batch Monitor" link to global sidebar nav (this fulfills batch-monitoring-eta UX requirement too)
- [ ] Write RTL tests for `EntityAskButton` (click → navigate with correct pre-filled text)
- [ ] Run `cd web && yarn test`, verify nav in browser across Job/Dataset/Run pages

### Acceptance Criteria
- AC1: `LineageAgentChatBar` is visible in the top navigation on every page (home, jobs, datasets, runs, batch monitor)
- AC2: `EntityAskButton` present on Job, Dataset, and Run detail pages; clicking opens `/agent` with the correct pre-filled question
- AC3: Pre-filled question format: `"Tell me about [entityName] in [namespace]"` (verified in RTL test)
- AC4: Batch Monitor "Ask about this batch window" button opens `/agent` with namespace context pre-filled
- AC5: `aria-label="Ask about [entityType] [entityName]"` present on all `EntityAskButton` instances
- AC6: `yarn tsc --noEmit` and `yarn test` both pass with no regressions

---

## Story 8: Client SDK Helpers — Python and Java
**Status:** TODO
**Size:** S
**Modules:** [CLIENTS]
**Depends on:** Story 4

### Context
Add a thin `AgentClient` helper to both the Python and Java Marquez clients so programmatic users (e.g., CI pipelines, data platforms) can query the lineage agent without constructing raw HTTP requests.

### Tasks
**Python client** (`clients/python/`):
- [ ] Add `agent_query(question, namespace, session_id=None)` method to `MarquezClient`
- [ ] Add `get_agent_session(session_id)` method
- [ ] Write unit tests (mock `requests` session)
- [ ] Run `cd clients/python && python -m pytest`

**Java client** (`clients/java/`):
- [ ] Add `agentQuery(AgentQueryRequest request)` method returning `AgentResponse` to `MarquezClient`
- [ ] Add `getAgentSession(String sessionId)` method
- [ ] Write unit tests (mock OkHttp client)
- [ ] Run `./gradlew :clients:java:check`

### Acceptance Criteria
- AC1: Python `agent_query(question, namespace)` returns a dict with `session_id`, `answer`, `citations`, `follow_ups`
- AC2: Python `agent_query(question, namespace, session_id=existing_id)` sends `sessionId` in the request body
- AC3: Java `agentQuery()` returns a typed `AgentResponse` POJO
- AC4: Both client method tests pass with mocked HTTP responses
- AC5: No new dependencies added to either client (reuse existing HTTP libraries)

---

## Story 9: Documentation Update
**Status:** TODO
**Size:** S
**Modules:** [DOCS]
**Depends on:** Story 4, Story 6

### Context
Update user-facing Docusaurus docs to cover the new lineage agent feature. Update `METRICS.md` for the three new Prometheus metrics. Final `CHANGELOG.md` check.

### Tasks
- [ ] Create `docs/docs/features/lineage-agent.md` covering: what it is, how to use the chat bar, how to use `EntityAskButton`, how to use the Python/Java client helpers, example Q&A
- [ ] Update `docs/docs/api-reference.md` (or equivalent) to link to the two new endpoints
- [ ] Update `METRICS.md` with: `marquez_agent_queries_total`, `marquez_agent_tool_calls_total`, `marquez_agent_query_duration_seconds`
- [ ] Set `MARQUEZ_CLAUDE_API_KEY` in the Helm `values.yaml` example (as an env var reference, not a hardcoded value)
- [ ] Run `cd docs && yarn build` and confirm no errors
- [ ] Final review: `CHANGELOG.md` has exactly one entry for this feature under `[Unreleased]`

### Acceptance Criteria
- AC1: `cd docs && yarn build` completes with no errors or warnings
- AC2: `docs/docs/features/lineage-agent.md` exists and includes the chat bar, `EntityAskButton`, and client helper sections
- AC3: `METRICS.md` documents all three new Prometheus metrics with labels and units
- AC4: `CHANGELOG.md` has exactly one entry for the lineage agent under `[Unreleased]`
- AC5: Helm `values.yaml` shows `MARQUEZ_CLAUDE_API_KEY` as `"{{ .Values.agent.claudeApiKey }}"` (not a hardcoded key)

---

## Completion Summary

| Story | Status | PR | Notes |
|-------|--------|----|-------|
| 1 – DB Migration: Agent Session Tables | TODO | — | Blocks all backend stories |
| 2 – DAO Layer: Lineage Tool Query Methods | TODO | — | 6 tool queries + session CRUD |
| 3 – AgentService: Tool-Use Loop + Claude API | TODO | — | Core orchestration; WireMock for tests |
| 4 – AgentResource: REST Endpoints | TODO | — | POST /agent/query, GET /agent/sessions |
| 5 – Frontend: Redux Slice + Types | TODO | — | |
| 6 – Frontend: ChatBar + AnswerThread | TODO | — | react-markdown + rehype-sanitize required |
| 7 – Frontend: EntityAskButton + Nav Integration | TODO | — | Touches global nav; risk of regressions |
| 8 – Client SDK Helpers: Python + Java | TODO | — | Thin wrappers; low risk |
| 9 – Documentation Update | TODO | — | |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
