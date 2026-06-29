# Feature Definition of Done Checklist

Use this checklist before marking a feature complete and opening the final PR.
Every `[ ]` must be `[x]` unless explicitly marked N/A with a reason.

---

## Spec & Design

- [ ] `specs/<feature>/prd.md` exists and is approved
- [ ] `specs/<feature>/adr.md` exists and documents the architecture decision
- [ ] `specs/<feature>/spec.md` is complete with API contracts, DB schema, data flow
- [ ] `specs/<feature>/stories.md` — all stories marked `[DONE]`
- [ ] All open questions in the PRD and spec are resolved

---

## Implementation

- [ ] All P0 functional requirements from the PRD are implemented
- [ ] Code follows existing Marquez patterns (Resource → Service → DAO chain)
- [ ] All new Java code passes `./gradlew spotlessApply` (Google Java Style)
- [ ] All new Java code passes `./gradlew pmdMain` (no PMD violations)
- [ ] All new source files have Apache 2.0 license headers
- [ ] No new external dependencies introduced without security review
- [ ] No Flyway migration files were modified — only new files added
- [ ] New Flyway migration file uses correct naming (`V{N}__description.sql`)

---

## API

- [ ] `docs/openapi.yml` updated for all new/modified endpoints
- [ ] New endpoints follow REST conventions (`/api/v1/{plural-resource}/{id}`)
- [ ] No breaking changes to existing v1 API response schemas
- [ ] New endpoints registered in `MarquezApp.java`
- [ ] New services instantiated in `MarquezContext.java`

---

## Database

- [ ] Migration is backward-compatible (no NOT NULL without default, no drops)
- [ ] Migration tested on fresh DB: `./gradlew :api:flywayMigrate` ✓
- [ ] Migration tested on populated DB with existing data ✓
- [ ] New indexes created with `CONCURRENTLY` on large tables
- [ ] No existing migration files modified

---

## Tests

- [ ] Unit tests written for all new Java classes (JUnit 5 + Mockito)
- [ ] Integration tests written for all new API endpoints (TestContainers)
- [ ] Frontend unit tests written for new Redux slices and components
- [ ] `./gradlew check` passes with zero new failures
- [ ] `cd web && yarn test` passes with zero new failures
- [ ] Jacoco report: line coverage not decreased on changed files
- [ ] Negative test cases present for all endpoints (404, 422, 409, etc.)
- [ ] `specs/<feature>/test-plan.md` Results section filled in and signed off

---

## Load Testing

- [ ] Load test run for all new `GET` endpoints: p95 ≤ 200ms at 100 RPS
- [ ] Load test run for all new `POST` endpoints: p95 ≤ 500ms at 100 RPS
- [ ] Load test results attached to `specs/<feature>/test-plan.md`

---

## Security

- [ ] No new unauthenticated endpoints that expose sensitive data
- [ ] SQL injection not possible (JDBI parameterized queries used)
- [ ] `Snyk test` run — no new HIGH/CRITICAL CVEs introduced
- [ ] Input validation present on all user-controlled parameters

---

## Observability

- [ ] New Prometheus metrics added per spec section 10
- [ ] `METRICS.md` updated with new metric definitions
- [ ] Key operations emit INFO logs with relevant context
- [ ] Error paths emit WARN/ERROR logs

---

## Documentation

- [ ] `CHANGELOG.md` updated under `[Unreleased]` with one-line summary
- [ ] `docs/docs/` updated with user-facing documentation
- [ ] `cd docs && yarn build` passes with no errors
- [ ] Code comments added only where WHY is non-obvious (not what)

---

## Git & PR

- [ ] All commits have `Signed-off-by` (DCO compliance)
- [ ] Branch is up to date with `main`
- [ ] PR description follows `.github/pull_request_template.md`
- [ ] PR description references the linked issue (`Closes: #NNN`)
- [ ] PR diff is self-contained (one feature per PR)
- [ ] No debug code, `TODO` comments, or commented-out code left in

---

## Final Sign-Off

| Role | Name | Date | Status |
|------|------|------|--------|
| Dev Agent | | | `[ ] Approved` |
| QA Agent | | | `[ ] Approved` |
| Architect | | | `[ ] Approved` |
| PM | | | `[ ] Approved` |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
