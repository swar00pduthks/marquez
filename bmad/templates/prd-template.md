# PRD: [Feature Title]

**Author(s):** [Name(s)]
**Created:** [YYYY-MM-DD]
**Last Updated:** [YYYY-MM-DD]
**Status:** `Draft` | `In Review` | `Approved` | `Superseded`
**Linked Proposal/Issue:** [#ISSUE-NUMBER or proposals/link]
**Target Release:** [vX.Y.Z or "unscheduled"]

---

## 1. Overview

### 1.1 Problem Statement
[What problem does this solve? Who experiences it? What is the impact if it is NOT solved?
Keep to 3–5 sentences.]

### 1.2 Proposed Solution
[One paragraph describing the solution at the product level — no implementation details.]

### 1.3 Background & Context
[Prior art, related proposals, competitor analysis, or user research that informs this PRD.
Link to relevant `proposals/` entries or external references.]

---

## 2. Goals & Non-Goals

### Goals
- [Goal 1 — measurable outcome]
- [Goal 2]
- [Goal 3]

### Non-Goals
- [Explicitly excluded scope item 1 — with reason]
- [Explicitly excluded scope item 2]

---

## 3. User Stories

| ID | Persona | Story | Priority |
|----|---------|-------|----------|
| US-1 | Data Engineer | As a Data Engineer, I want [goal] so that [outcome]. | `P0` |
| US-2 | Data Analyst | As a Data Analyst, I want [goal] so that [outcome]. | `P1` |
| US-3 | Platform Engineer | As a Platform Engineer, I want [goal] so that [outcome]. | `P1` |
| US-4 | ML Engineer | As an ML Engineer, I want [goal] so that [outcome]. | `P2` |

[Add/remove rows as needed. Mark each `P0` / `P1` / `P2`.]

---

## 4. Functional Requirements

### 4.1 Must Have (P0)
- `[FR-1]` [Requirement description — specific, testable]
- `[FR-2]` [...]

### 4.2 Should Have (P1)
- `[FR-3]` [...]
- `[FR-4]` [...]

### 4.3 Nice to Have (P2)
- `[FR-5]` [...]

---

## 5. Non-Functional Requirements

| Attribute | Requirement |
|-----------|-------------|
| Performance | [e.g., "API endpoint p95 latency ≤ 200ms at 100 RPS"] |
| Scalability | [e.g., "Must support 10,000 namespaces without schema change"] |
| Backward Compatibility | [e.g., "Existing v1 API responses must not change shape"] |
| Security | [e.g., "No new unauthenticated endpoints that expose dataset content"] |
| Availability | [e.g., "No downtime deployment — migration must be online-safe"] |
| Observability | [e.g., "New Prometheus metrics for X"] |

---

## 6. API / UX Surface Changes

### 6.1 New or Modified API Endpoints
[List proposed endpoint changes at a high level — full spec belongs in the ADR/Feature Spec.]

| Method | Path | Change |
|--------|------|--------|
| `GET` | `/api/v1/...` | New |
| `POST` | `/api/v1/...` | Modified — add `field` to response |

### 6.2 UI Changes
[Describe UI changes at the product level — screenshots/wireframes if available.]

### 6.3 Client Library Impact
[Does this require changes to `clients/java` or `clients/python`? If so, what?]

---

## 7. Data Model Impact

[Does this feature require new database tables, columns, or schema changes?
If yes, describe at a conceptual level — exact SQL belongs in the Feature Spec.]

| Change | Type | Backward Compatible? |
|--------|------|----------------------|
| Add column `X` to table `Y` | Additive | Yes |
| New table `Z` | Additive | Yes |

---

## 8. Open Questions

| # | Question | Owner | Due | Resolution |
|---|----------|-------|-----|------------|
| 1 | [Question that must be resolved before implementation] | [Name] | [Date] | [Pending / Answer] |
| 2 | [...] | | | |

---

## 9. Definition of Done

A feature is complete when ALL of the following are true:

- [ ] All P0 functional requirements are implemented and verified
- [ ] Unit and integration tests written; Jacoco coverage not decreased
- [ ] Load test results meet NFR thresholds (p95 latency targets)
- [ ] `docs/openapi.yml` updated for any API changes
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] PR checklist in `.github/pull_request_template.md` fully completed
- [ ] All open questions resolved
- [ ] Signed-off-by present on all commits (DCO)

---

## 10. Out of Scope (Explicit Exclusions)

[Repeat non-goals with one sentence of rationale each to prevent scope creep.]

---

## Revision History

| Date | Author | Change |
|------|--------|--------|
| [YYYY-MM-DD] | [Name] | Initial draft |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
