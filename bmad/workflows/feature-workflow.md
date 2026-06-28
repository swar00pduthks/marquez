# Feature Development Workflow

This workflow describes the end-to-end process for delivering a new feature in Marquez using spec-driven development with BMAD agents.

---

## Phase 0: Intake

**Human action required.**

1. Identify the feature idea (user request, competitive gap, internal need).
2. Open a GitHub issue with a brief description.
3. Check `proposals/` and `CHANGELOG.md` for prior art.
4. Decide if this is:
   - **Small** (< 1 story, touches one module) → skip to Phase 2, write a minimal spec
   - **Medium** (2–5 stories, 1–2 modules) → follow this workflow
   - **Large** (> 5 stories, cross-module) → use BMAD in full; write the full proposal first

---

## Phase 1: Product Spec

**Agent: PM Agent** (`bmad/agents/pm-agent.md`)

### Inputs
- GitHub issue description
- Existing `proposals/` entries
- Competitive context (OpenMetadata, DataHub)

### Steps

```
Prompt: "Act as the PM agent (see bmad/agents/pm-agent.md).
I want to build [describe feature]. The GitHub issue is #NNN.
Fill in specs/<feature>/prd.md using the template at
bmad/templates/prd-template.md."
```

### Outputs
- `specs/<feature>/prd.md` — complete PRD

### Exit Criteria
- All sections filled (no `[placeholder]` text)
- All open questions in section 8 have owners and due dates
- Scope is explicitly bounded (Goals + Non-Goals both filled)
- **Human review and approval required before Phase 2**

---

## Phase 2: Architecture

**Agent: Architect Agent** (`bmad/agents/architect-agent.md`)

### Inputs
- `specs/<feature>/prd.md` (approved)
- `docs/openapi.yml` (existing API surface)
- `marquez_data_model.md` (data model)
- `docs/v3-api-investigation-guide.md` (if touching graph layer)

### Steps

```
Prompt: "Act as the Architect agent (see bmad/agents/architect-agent.md).
Read specs/<feature>/prd.md and produce:
1. specs/<feature>/adr.md — architecture decision record
2. specs/<feature>/spec.md — full technical specification
Use the templates in bmad/templates/."
```

### Outputs
- `specs/<feature>/adr.md` — decision record with rationale
- `specs/<feature>/spec.md` — full technical spec with API contracts, DB schema, data flow

### Exit Criteria
- ADR status set to `Accepted`
- All open technical questions resolved
- API contracts (OpenAPI snippets) match existing API conventions
- Flyway migration version number assigned
- **Human review required before Phase 3**

---

## Phase 3: Story Decomposition

**Agent: SM Agent** (`bmad/agents/sm-agent.md`)

### Inputs
- `specs/<feature>/spec.md` (approved)

### Steps

```
Prompt: "Act as the SM agent (see bmad/agents/sm-agent.md).
Read specs/<feature>/spec.md and produce specs/<feature>/stories.md.
Break the spec into atomic developer stories ordered by dependency.
All stories must be [M] or smaller — split anything [L] or larger."
```

### Outputs
- `specs/<feature>/stories.md` — ordered story list with tasks and acceptance criteria

### Exit Criteria
- All stories ≤ [M] size
- Dependency order is correct (infrastructure before application code, API before UI)
- Every story has 3+ measurable acceptance criteria
- CHANGELOG task included in every story
- **SM agent review complete; no human approval required**

---

## Phase 4: Test Plan

**Agent: QA Agent** (`bmad/agents/qa-agent.md`)

*Written BEFORE implementation begins.*

### Inputs
- `specs/<feature>/spec.md`
- `specs/<feature>/stories.md`

### Steps

```
Prompt: "Act as the QA agent (see bmad/agents/qa-agent.md).
Read specs/<feature>/spec.md and specs/<feature>/stories.md.
Produce specs/<feature>/test-plan.md using bmad/templates/test-plan-template.md.
Write test cases for all acceptance criteria. Include unit, integration,
load, and security test cases."
```

### Outputs
- `specs/<feature>/test-plan.md` — complete test plan (Results section blank)

### Exit Criteria
- Every story acceptance criterion mapped to at least one test case
- Load test cases defined for all new endpoints
- Security test cases present
- **QA Agent signs off on test plan completeness**

---

## Phase 5: Implementation

**Agent: Dev Agent** (`bmad/agents/dev-agent.md`)

*Work story-by-story, in dependency order.*

### Steps (per story)

```
Prompt: "Act as the Dev agent (see bmad/agents/dev-agent.md).
Implement Story N from specs/<feature>/stories.md.
The full spec is at specs/<feature>/spec.md.
Follow all patterns in AGENTS.md."
```

After each story:

1. Run `./gradlew check` (Java) or `yarn test` (web)
2. Run `./gradlew spotlessApply` (Java)
3. Update story status to `[DONE]` in `stories.md`
4. Commit with `Signed-off-by`

### Exit Criteria (per story)
- All tasks in the story `[x]` complete
- All acceptance criteria verifiably met
- Tests passing, coverage not decreased
- Story marked `[DONE]` in `stories.md`

---

## Phase 6: QA Verification

**Agent: QA Agent** (`bmad/agents/qa-agent.md`)

### Steps

```
Prompt: "Act as the QA agent. Execute the test plan at
specs/<feature>/test-plan.md. Run:
  ./gradlew check jacocoTestReport
  cd web && yarn test --coverage
  .circleci/api-load-test.sh
Fill in the Results section of the test plan with actual outcomes."
```

### Outputs
- `specs/<feature>/test-plan.md` Results section filled in
- `specs/<feature>/load-test-results.json`

### Exit Criteria
- All P0 test cases: PASS
- Coverage targets met (see test plan section 8)
- Load test thresholds met
- No HIGH/CRITICAL security findings
- QA verdict: `PASS`

---

## Phase 7: Final Review & Merge

**Human action required.**

1. Run `bmad/checklists/feature-checklist.md` — all items checked
2. Open PR against `main` using `.github/pull_request_template.md`
3. Attach test plan results and Jacoco report to PR
4. Request review from committers
5. Respond to review comments
6. Merge after CI green + approval

### PR Description Template

```markdown
## Problem
[From PRD section 1.1]

## Solution
[From spec.md summary]

## Test Plan
See specs/<feature>/test-plan.md

## Checklist
[Copy from .github/pull_request_template.md]

Closes: #ISSUE-NUMBER
```

---

## Phase 8: Post-Merge

1. Update `specs/<feature>/prd.md` status to `Superseded` (feature shipped)
2. Verify `CHANGELOG.md` entry is accurate
3. Tag release if this is a release commit
4. Close the GitHub issue
5. Archive `specs/<feature>/` folder to `specs/archive/<feature>/` (optional)

---

## Timeline Guide

| Phase | Typical Duration | Blocking? |
|-------|-----------------|-----------|
| 0 – Intake | < 1h | Human |
| 1 – PRD | 1–2h | Human approval |
| 2 – Architecture | 2–4h | Human approval |
| 3 – Stories | 1h | Auto |
| 4 – Test Plan | 1–2h | Auto |
| 5 – Implementation | Varies by story count | Per story |
| 6 – QA | 1–3h | Auto |
| 7 – Review & Merge | 1–5 days | Human |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
