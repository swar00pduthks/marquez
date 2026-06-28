# ADR: [Short Decision Title]

**Number:** ADR-[NNN]
**Date:** [YYYY-MM-DD]
**Status:** `Proposed` | `Accepted` | `Deprecated` | `Superseded by ADR-[NNN]`
**Feature Spec:** `specs/<feature>/spec.md`
**Decision Makers:** [Names / roles]

---

## Context

[Describe the technical situation requiring a decision. What constraints exist?
What are we trying to achieve? Reference the PRD (`specs/<feature>/prd.md`) for product context.
Keep to 2–4 paragraphs.]

---

## Decision Drivers

- [Driver 1 — e.g., "Must not break existing v1 API clients"]
- [Driver 2 — e.g., "Must support Apache AGE Cypher queries for graph traversal"]
- [Driver 3 — e.g., "Must be deployable with zero downtime (rolling update)"]
- [Driver 4]

---

## Options Considered

### Option A: [Name]

**Description:** [What this option involves]

**Pros:**
- [Pro 1]
- [Pro 2]

**Cons:**
- [Con 1]
- [Con 2]

**Estimated Complexity:** `[Low / Medium / High / Very High]`

---

### Option B: [Name]

**Description:** [What this option involves]

**Pros:**
- [Pro 1]
- [Pro 2]

**Cons:**
- [Con 1]
- [Con 2]

**Estimated Complexity:** `[Low / Medium / High / Very High]`

---

### Option C: [Name] *(if applicable)*

[Same structure]

---

## Decision

**Chosen Option:** [Option A / B / C]

**Rationale:** [Why this option was chosen over the alternatives. Be specific — reference the decision drivers above. This is the most important section of the ADR.]

---

## Consequences

### Positive
- [Positive consequence 1]
- [Positive consequence 2]

### Negative / Trade-offs
- [Trade-off 1 — what we give up or accept]
- [Trade-off 2]

### Risks
- [Risk 1 — what could go wrong, and mitigation]
- [Risk 2]

---

## Implementation Notes

[High-level notes for the architect/dev on HOW to implement this decision.
Full implementation detail belongs in `specs/<feature>/spec.md`.]

### Affected Modules
- `api/` — [what changes]
- `web/` — [what changes, or "no changes"]
- `clients/java/` — [what changes, or "no changes"]
- `chart/` — [what changes, or "no changes"]

### Migration / Rollback Strategy
[How do we roll back this decision if it turns out to be wrong?
Is the change reversible? What is the rollback procedure?]

---

## References

- [Link to PRD]
- [Link to relevant GitHub issue(s)]
- [Link to external docs, RFCs, or prior art]
- [Relevant section of `docs/v3-api-investigation-guide.md` if applicable]

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
