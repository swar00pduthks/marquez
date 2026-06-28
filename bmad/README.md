# BMAD: Spec-Driven Development for Marquez

**BMAD** (Build, Model, Architect, Develop) is this project's AI-native methodology for taking a feature idea all the way to a merged, tested, documented implementation — driven entirely by specs that AI agents can read, execute, and verify.

---

## Why Spec-Driven?

Marquez is a complex, multi-module project (Java/Dropwizard API, React/TypeScript web, PostgreSQL + Apache AGE graph, Helm chart, Python/Java clients). Unplanned changes across these layers accumulate technical debt fast. Writing the spec first forces clarity before code is touched, gives AI agents a precise contract to implement against, and gives reviewers a lightweight artifact to audit before a line of code changes.

---

## The BMAD Phases

```
Idea → [PM + PO] PRD → [Comparative Analyst] Gap Analysis → [User Agents] Validation
     → [UX Designer] Flows + Component Specs → [Architect] ADR + Spec
     → [SM] Stories → [Dev] Implementation → [QA] Verification → [Tech Writer] Docs → Merge
```

| Phase | Agent | Output | Location |
|-------|-------|--------|----------|
| 1. Discover | PM Agent + PO Agent | PRD + backlog priority | `specs/<feature>/prd.md` |
| 1b. Competitive Check | Comparative Analyst | Feature gap analysis | `specs/<feature>/prd.md` appendix |
| 2. User Validation | User Agents | Feedback on PRD | Inline PRD review |
| 2b. UX Design | UX Designer Agent | User flows + component specs | `specs/<feature>/ux.md` |
| 3. Design | Architect Agent | ADR + Feature Spec | `specs/<feature>/adr.md` + `spec.md` |
| 4. Plan | SM Agent | Story list | `specs/<feature>/stories.md` |
| 5. Test Plan | QA Agent | Test plan | `specs/<feature>/test-plan.md` |
| 6. Build | Dev Agent | Code + tests | `api/`, `web/`, `clients/` |
| 7. Verify | QA + Perf + Security Agents | Test results | `specs/<feature>/test-plan.md` |
| 8. Document | Technical Writer Agent | Docs update | `docs/`, `CHANGELOG.md` |
| 9. Ship | PO Agent + Human | PR acceptance + release | `.github/`, `CHANGELOG.md` |

---

## Agent Roster

### Core Process Agents

| File | Role | Best Model |
|------|------|-----------|
| `agents/pm-agent.md` | Product Manager — writes PRDs, defines scope | Sonnet |
| `agents/product-owner-agent.md` | Product Owner — prioritizes backlog, accepts/rejects stories | Opus |
| `agents/architect-agent.md` | Architect — writes ADRs, system design, API contracts | Opus |
| `agents/ux-designer-agent.md` | UX Designer — user flows, component specs, accessibility, graph UX | Sonnet |
| `agents/dev-agent.md` | Developer (general) — implements from spec, writes tests | Sonnet |
| `agents/sm-agent.md` | Story Manager — breaks specs into granular dev stories | Sonnet |
| `agents/comparative-analyst-agent.md` | Competitive analysis, feature gap identification | Opus |
| `agents/technical-writer-agent.md` | Docs, OpenAPI spec freshness, user guides | Sonnet |

### Specialized Developer Agents

Use these in place of (or alongside) the general `dev-agent.md` when a story is domain-specific. Each agent has deep knowledge of its layer's patterns, pitfalls, and tooling.

| File | Specialization | Layer | Best Model |
|------|---------------|-------|-----------|
| `agents/dev-frontend-agent.md` | React/TypeScript UI — Redux, MUI, Vitest, API client | `web/` | Sonnet |
| `agents/dev-backend-agent.md` | Java/Dropwizard — Resource → Service → DAO, JUnit 5, Mockito | `api/` | Sonnet |
| `agents/dev-database-agent.md` | PostgreSQL/Flyway — migrations, query optimization, partitioning, AGE | `db/migration/` | Sonnet / Opus* |
| `agents/dev-platform-agent.md` | DevOps — Docker, Helm, GitHub Actions, Gradle, Prometheus | `chart/`, `.github/` | Sonnet / Opus* |

> \* Use **Opus** for the Database and Platform agents when the decision has high blast radius — e.g., repartitioning a large table, redesigning the CI pipeline, or planning a zero-downtime schema migration.

### QA Agents

| File | Role | Best Model |
|------|------|-----------|
| `agents/qa-agent.md` | General QA — functional test plans, coverage, integration tests | Sonnet |
| `agents/qa-performance-agent.md` | Performance — k6 load tests, query profiling, Azure baseline | Sonnet |
| `agents/qa-security-agent.md` | Security — OWASP review, Snyk, injection testing | Opus |

### User Persona Agents

Use these to validate specs and features from the perspective of real users before implementation begins.

| File | Persona | Technical Level |
|------|---------|----------------|
| `agents/users/data-engineer-user.md` | Data Engineer — pipelines, OpenLineage instrumentation | High |
| `agents/users/data-analyst-user.md` | Data Analyst — dataset discovery, trust, freshness | Medium |
| `agents/users/platform-engineer-user.md` | Platform Engineer — deployment, ops, Helm, Prometheus | Very High |
| `agents/users/ml-engineer-user.md` | ML Engineer — model lineage, experiment tracking, Python client | High |
| `agents/users/ai-engineer-user.md` | AI/LLM Engineer — agentic lineage, RAG pipelines, prompt versioning | Very High |
| `agents/users/batch-ops-engineer-user.md` | Batch Ops Engineer — SLA monitoring, predictive ETA, blast radius | High |
| `agents/users/app-developer-user.md` | Application Developer — app-to-data lineage, schema change alerts, consumer registration | High |
| `agents/users/business-user.md` | Business User / CDO — governance, compliance, plain language | Low |
| `agents/users/oss-contributor-user.md` | OSS Contributor — onboarding, contributing experience | High |

> **Tip:** Run all user persona agents against a PRD before moving to architecture. A feature that satisfies the Data Engineer but is incomprehensible to the Business User — or fails the Batch Ops Engineer's SLA monitoring needs — requires scope adjustment before implementation begins.

---

## Model Selection Guide

Different phases need different model capabilities. Don't use a heavier model when a lighter one is sufficient.

| Task | Recommended Model | Reason |
|------|------------------|--------|
| PRD writing | Sonnet | Strong writing, straightforward synthesis |
| Backlog decisions, PO role | Opus | Cross-feature consequences, strategic reasoning |
| Architecture decisions | Opus | Multi-system trade-offs, deep reasoning |
| Story decomposition | Sonnet | Methodical, pattern-following |
| Code implementation | Sonnet | Code generation is well within Sonnet's capability |
| Functional test plan | Sonnet | Systematic, table-driven |
| Performance analysis | Sonnet | Tool-driven, interpretive |
| Security review | Opus | Adversarial reasoning, subtle pattern recognition |
| Competitive analysis | Opus | Broad market context synthesis |
| Documentation writing | Sonnet | Writing quality + code accuracy |
| User persona simulation | Sonnet | Empathy + domain knowledge |

---

## Templates

| Template | Use |
|----------|-----|
| `templates/prd-template.md` | Product Requirements Document |
| `templates/adr-template.md` | Architecture Decision Record |
| `templates/feature-spec-template.md` | Full technical feature specification |
| `templates/story-template.md` | Developer story list |
| `templates/test-plan-template.md` | QA test plan with results |

---

## Checklists

| Checklist | Use |
|-----------|-----|
| `checklists/feature-checklist.md` | Definition of Done for any feature |
| `checklists/api-checklist.md` | API endpoint readiness |
| `checklists/db-migration-checklist.md` | Flyway migration safety |

---

## Workflows

| Workflow | Use |
|----------|-----|
| `workflows/feature-workflow.md` | End-to-end new feature delivery |
| `workflows/bugfix-workflow.md` | Bug investigation and fix |

---

## Quick Start

### Starting a new feature

```bash
# 1. Create the spec directory
mkdir -p specs/<feature-name>

# 2. Copy templates
cp bmad/templates/prd-template.md specs/<feature-name>/prd.md

# 3. PM Agent: write the PRD
# Prompt: "Act as the PM agent (see bmad/agents/pm-agent.md).
# Fill in specs/<feature-name>/prd.md for: <describe the feature>"

# 4. Comparative Analyst: add competitive context
# Prompt: "Act as the Comparative Analyst (see bmad/agents/comparative-analyst-agent.md).
# Review specs/<feature-name>/prd.md and add a competitive analysis appendix."

# 5. User Agents: validate the PRD
# Prompt: "Act as the Data Engineer user agent. Review specs/<feature-name>/prd.md
# and tell me what's missing or wrong from your perspective."
# (Repeat for each relevant user persona)

# 6. PO Agent: prioritize and approve
# Prompt: "Act as the PO agent. Review specs/<feature-name>/prd.md,
# set the priority, and approve or request changes."

# 7. Architect: design the solution
# Prompt: "Act as the Architect agent. Read specs/<feature-name>/prd.md
# and produce specs/<feature-name>/adr.md and specs/<feature-name>/spec.md"

# 8. SM Agent: break into stories
# Prompt: "Act as the SM agent. Read specs/<feature-name>/spec.md
# and produce specs/<feature-name>/stories.md"

# 9. QA Agent: write the test plan
# Prompt: "Act as the QA agent. Read specs/<feature-name>/spec.md
# and produce specs/<feature-name>/test-plan.md"

# 10. Dev Agent: implement story by story
# Use the specialized agent that matches the story's layer:
#   Backend story  → dev-backend-agent.md
#   Frontend story → dev-frontend-agent.md
#   DB migration   → dev-database-agent.md
#   Infra/CI story → dev-platform-agent.md
#   Cross-layer    → dev-agent.md (general)
# Prompt: "Act as the Backend Dev agent (bmad/agents/dev-backend-agent.md).
# Implement story 1 from specs/<feature-name>/stories.md"

# 11. Performance + Security QA
# Prompt: "Act as the QA Performance agent. Run load tests for the new endpoints
# per specs/<feature-name>/test-plan.md"
# Prompt: "Act as the QA Security agent. Review the implementation of specs/<feature-name>/spec.md"

# 12. Technical Writer: update docs
# Prompt: "Act as the Technical Writer agent. Update docs/ for the new feature
# and verify docs/openapi.yml is current."
```

---

## Important: Stale Reference Documents

> **Warning for all agents:** These two documents are frequently out of date because developers add migrations and endpoints without updating them:
>
> - **`marquez_data_model.md`** — Cross-check against the latest Flyway migration files in `api/src/main/resources/marquez/db/migration/` before using this as a reference.
> - **`docs/openapi.yml`** — Cross-check against `api/src/main/java/marquez/api/*Resource.java` files. The Technical Writer agent is responsible for auditing and fixing discrepancies before each release.

---

## Integration with Existing Marquez Process

- **Proposals**: A BMAD PRD is the content of a `proposals/` entry. For community proposals, use the proposal first; for internal features, the PRD replaces the proposal.
- **AGENTS.md**: The Dev agent persona enforces all rules in `AGENTS.md` — tests, Flyway migrations, backward compatibility, Jacoco.
- **PR Template**: The QA agent's test plan output maps directly onto the PR checklist in `.github/pull_request_template.md`.
- **CHANGELOG**: The SM agent adds a CHANGELOG entry as part of each story's acceptance criteria.
- **Copilot Standards**: All BMAD outputs must satisfy the rules in `copilot/standards.yml`.

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
