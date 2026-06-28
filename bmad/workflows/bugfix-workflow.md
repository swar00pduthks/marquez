# Bug Fix Workflow

A lightweight spec-driven workflow for investigating and fixing bugs in Marquez.

---

## Phase 0: Triage

**Human action required.**

1. Identify the bug (user report, CI failure, monitoring alert, Snyk alert).
2. Open a GitHub issue with:
   - Steps to reproduce
   - Expected behavior
   - Actual behavior
   - Environment (version, PostgreSQL version, deployment type)
3. Assign severity:
   - **P0 – Critical**: data loss, incorrect lineage stored, security vulnerability, service down
   - **P1 – High**: incorrect API response, UI crash, broken core workflow
   - **P2 – Medium**: edge case incorrect, cosmetic issue, degraded performance
   - **P3 – Low**: minor UX issue, docs error

---

## Phase 1: Investigation

**Agent: Dev Agent** (first pass) or **Architect Agent** (for systemic bugs)

### Steps

```
Prompt: "Act as the Dev agent (see bmad/agents/dev-agent.md).
Investigate bug #NNN: [description].
Steps to reproduce: [steps].
Find the root cause — read the relevant source files and trace the code path.
Produce a short investigation note (5–15 lines) describing:
  1. Root cause
  2. Affected files and line numbers
  3. Proposed fix approach
  4. Risk of regression"
```

### Investigation Note (save to `specs/bugs/<issue-number>/investigation.md`)

```markdown
## Bug #NNN Investigation

**Issue:** [Title]
**Severity:** P0/P1/P2/P3
**Root Cause:** [1–3 sentences]
**Affected Code:** 
  - `api/src/.../SomeClass.java:42` — [what's wrong]
  - `web/src/.../someComponent.tsx:88` — [what's wrong]
**Proposed Fix:** [1–3 sentences]
**Risk of Regression:** Low/Medium/High — [why]
**Test to Add:** [what test would have caught this]
```

---

## Phase 2: Fix Spec (P0/P1 only)

For **P0 and P1 bugs**, write a minimal fix spec before coding.

```
Prompt: "Act as the Architect agent. Read specs/bugs/<NNN>/investigation.md.
Produce a minimal fix spec — what changes are needed, what tests must be added,
and what regression risk exists. Keep it to one page."
```

For **P2/P3 bugs**, skip directly to Phase 3.

---

## Phase 3: Implementation

**Agent: Dev Agent**

```
Prompt: "Act as the Dev agent. Fix bug #NNN per specs/bugs/<NNN>/investigation.md.
Rules:
- Minimal change — fix ONLY what is broken; do not refactor surrounding code
- Write a regression test that would have caught this bug
- Run ./gradlew check (or yarn test) before marking done
- Add a one-line entry to CHANGELOG.md under [Unreleased]"
```

### Implementation Rules for Bug Fixes

- Fix scope: change ONLY the lines needed to fix the bug. Do not improve, clean up, or refactor.
- The regression test is **mandatory** — it must fail before the fix and pass after.
- If the bug is in a Flyway migration, the fix requires a **new migration file** — never edit the broken one.
- If the bug is a security vulnerability, follow the security fix rules below.

---

## Phase 4: Verification

**Agent: QA Agent**

```
Prompt: "Act as the QA agent. Verify bug fix for #NNN.
1. Run the new regression test and confirm it passes
2. Run the full test suite: ./gradlew check
3. Check that the bug is no longer reproducible using the original steps
4. Confirm no regressions in related tests"
```

### Verification Checklist

- [ ] Regression test added and passes
- [ ] Original bug reproduction steps no longer reproduce the bug
- [ ] `./gradlew check` passes (or `yarn test` for web bugs)
- [ ] Jacoco coverage not decreased
- [ ] `CHANGELOG.md` updated
- [ ] PR checklist complete

---

## Phase 5: PR & Merge

**Human action required.**

PR title format: `fix: [short description of bug fix] (#NNN)`

PR description:
```markdown
## Problem
[Description of the bug. Steps to reproduce.]
Closes: #NNN

## Root Cause
[1–2 sentences from investigation note]

## Solution
[What changed and why it fixes the root cause]

## Regression Test
[Name of the new test class/method added]

## Checklist
- [x] Regression test added
- [x] ./gradlew check passes
- [x] CHANGELOG.md updated
- [x] Signed-off-by on commits
```

---

## Security Bug Special Procedure

For bugs that are security vulnerabilities (CVE, data exposure, auth bypass):

1. **Do NOT open a public GitHub issue** — use the private disclosure process (see `SECURITY.md` if present, or email maintainers directly).
2. Fix in a private branch.
3. Coordinate with maintainers on disclosure timeline.
4. Patch notes must not reveal exploit details until after the fix is deployed.
5. Run `snyk test` after fixing — confirm the CVE is resolved.

---

## Hotfix Process (P0 on Production)

When a P0 bug requires an immediate hotfix on a released version:

```bash
# Create hotfix branch from the release tag
git checkout -b hotfix/vX.Y.Z-fix-NNN vX.Y.Z

# Apply the fix
# ...

# Tag the hotfix release
git tag vX.Y.(Z+1)
git push origin hotfix/vX.Y.Z-fix-NNN --tags
```

1. Hotfix PR merges into the release branch AND into `main`.
2. A new patch version is released immediately.
3. `CHANGELOG.md` entry included in both branches.

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
