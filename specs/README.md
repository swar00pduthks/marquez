# Specs Directory

This directory contains all active and historical feature specifications for Marquez.

---

## Directory Structure

```
specs/
  <feature-name>/
    prd.md          # Product Requirements Document (PM agent output)
    adr.md          # Architecture Decision Record (Architect agent output)
    spec.md         # Full technical specification (Architect agent output)
    stories.md      # Developer stories (SM agent output)
    test-plan.md    # QA test plan + results (QA agent output)
    notes.md        # (optional) investigation notes, scratch pad
    load-test-results.json  # (optional) k6 output
  bugs/
    <issue-number>/
      investigation.md   # Bug investigation note (Dev/Architect agent output)
  archive/
    <completed-feature>/  # Moved here after feature ships
```

---

## Naming Convention

Feature directories use kebab-case matching the GitHub issue title or epic name:

| Good | Bad |
|------|-----|
| `column-lineage-v2/` | `ColumnLineageV2/` |
| `dataset-quality-metrics/` | `dataset_quality_metrics/` |
| `v3-graph-api/` | `v3GraphApi/` |

Bug directories use the GitHub issue number:

```
specs/bugs/2345/investigation.md
```

---

## Spec Lifecycle

```
Draft → In Review → Approved → In Progress → Complete → Archived
```

Each spec document has a `Status:` field in its header. Keep it updated.

| Status | Meaning |
|--------|---------|
| `Draft` | Being written — not ready for review |
| `In Review` | Shared for human/agent review |
| `Approved` | Ready for implementation |
| `In Progress` | Dev agent is implementing stories |
| `Complete` | All stories done, QA passed, PR merged |
| `Superseded` | Replaced by a newer spec |

---

## Active Specs

| Feature | Status | PRD | Spec | Stories | Test Plan |
|---------|--------|-----|------|---------|-----------|
| Natural Language Lineage Agent | `Draft` | [prd.md](natural-language-lineage-agent/prd.md) | — | — | — |
| Batch Monitoring & Predictive ETA | `Draft` | [prd.md](batch-monitoring-eta/prd.md) | — | — | — |

---

## How to Start a New Spec

See `bmad/workflows/feature-workflow.md` for the full process. Quick version:

```bash
# 1. Create directory
mkdir -p specs/my-feature

# 2. Copy templates
cp bmad/templates/prd-template.md specs/my-feature/prd.md
cp bmad/templates/adr-template.md specs/my-feature/adr.md
cp bmad/templates/feature-spec-template.md specs/my-feature/spec.md
cp bmad/templates/story-template.md specs/my-feature/stories.md
cp bmad/templates/test-plan-template.md specs/my-feature/test-plan.md

# 3. Prompt the PM agent to fill in the PRD
# See bmad/README.md for agent prompts
```

---

## Important: Keeping Reference Docs Current

> **Warning for agents and developers:** The following reference documents may be stale. Always verify against the actual source code before relying on them:
>
> - `marquez_data_model.md` — May not reflect recent schema migrations. Cross-check against the latest Flyway migration files in `api/src/main/resources/marquez/db/migration/`.
> - `docs/openapi.yml` — Developers frequently add or modify endpoints without updating the OpenAPI spec. Cross-check against the actual `marquez/api/*Resource.java` files.
>
> When writing a spec that touches the data model or API surface, **regenerate or manually verify** these documents first. The Architect agent is responsible for flagging discrepancies.

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
