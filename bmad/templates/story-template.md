# Stories: [Feature Title]

**Feature Spec:** `specs/<feature>/spec.md`
**Created:** [YYYY-MM-DD]
**SM Agent review:** [YYYY-MM-DD]

---

## Summary

[2–3 sentences on what this feature delivers and why it's being built. Copied or condensed from the PRD.]

---

## Story Map

```
Story 1 (DB Migration)
    └── Story 2 (DAO Layer)
            └── Story 3 (Service Layer)
                    └── Story 4 (API Endpoint)
                            ├── Story 5 (Frontend - API integration)
                            └── Story 6 (Frontend - UI Component)
                                        └── Story 7 (Docs update)
```

[Replace with actual dependency graph for your feature.]

---

## Stories

---

## Story 1: [Infrastructure / Migration Title]
**Status:** TODO
**Size:** [XS/S/M/L]
**Modules:** [API]
**Depends on:** none

### Context
[Why this story must come first. What does it unblock?]

### Tasks
- [ ] Create Flyway migration `V{N}__[description].sql` in `api/src/main/resources/marquez/db/migration/`
- [ ] Verify migration is backward-compatible (no NOT NULL without default, no drops)
- [ ] Run `./gradlew :api:flywayMigrate` against a local DB and confirm success
- [ ] Write a test that verifies the migration applies cleanly to a fresh schema

### Acceptance Criteria
- AC1: Migration file follows naming convention `V{N}__[description].sql` (two underscores)
- AC2: Migration applies without error on a fresh PostgreSQL 14 database
- AC3: Migration applies without error on a database with existing data (populated test fixture)
- AC4: Rolling back (dropping the new column/table) leaves existing data intact
- AC5: `./gradlew check` passes after the migration file is added

---

## Story 2: [DAO Layer Title]
**Status:** TODO
**Size:** [S/M]
**Modules:** [API]
**Depends on:** Story 1

### Context
[What data access methods are needed. Reference `spec.md` section 6.1.]

### Tasks
- [ ] Create `{Entity}Row.java` in `marquez.db.models`
- [ ] Create `{Entity}Dao.java` in `marquez.db` with JDBI3 annotations
- [ ] Add license header to all new files
- [ ] Write unit tests for all DAO methods (use TestContainers for real DB tests)
- [ ] Run `./gradlew spotlessApply` and stage changes

### Acceptance Criteria
- AC1: `{Entity}Dao` has methods for all CRUD operations required by the spec
- AC2: All SQL queries use JDBI3 `@SqlQuery`/`@SqlUpdate` annotations (no raw JDBC)
- AC3: Unit tests cover happy path and not-found cases for each DAO method
- AC4: `./gradlew check` passes with no new failures
- AC5: Jacoco report shows ≥ 80% line coverage on new DAO class

---

## Story 3: [Service Layer Title]
**Status:** TODO
**Size:** [S/M]
**Modules:** [API]
**Depends on:** Story 2

### Context
[What business logic lives here. Error handling rules from spec section 6.3.]

### Tasks
- [ ] Create `{Entity}Service.java` in `marquez.service`
- [ ] Implement business logic per `spec.md` section 6.3
- [ ] Add license header
- [ ] Wire up Prometheus counter/histogram per `spec.md` section 10
- [ ] Write unit tests with mocked DAO
- [ ] Run `./gradlew spotlessApply pmdMain`

### Acceptance Criteria
- AC1: Service enforces all validation rules defined in spec error-handling table
- AC2: Service returns correct HTTP-mapped exceptions (not-found → 404, conflict → 409)
- AC3: Prometheus metrics emitted on success and error paths
- AC4: Unit test covers all branches (happy path, not-found, conflict, validation error)
- AC5: `./gradlew check` passes

---

## Story 4: [API Endpoint Title]
**Status:** TODO
**Size:** [M]
**Modules:** [API]
**Depends on:** Story 3

### Context
[REST layer implementation. References spec section 3 (API spec) and section 6.2.]

### Tasks
- [ ] Create `{Entity}Resource.java` in `marquez.api`
- [ ] Register resource in `MarquezApp.java`
- [ ] Instantiate service in `MarquezContext.java`
- [ ] Add license header to all new/modified files
- [ ] Update `docs/openapi.yml` with new/modified endpoint definitions
- [ ] Write `{Entity}ResourceTest.java` with unit tests
- [ ] Write integration test in `{Entity}ResourceIntegrationTest.java`
- [ ] Run `./gradlew spotlessApply pmdMain check`
- [ ] Add one-line entry to `CHANGELOG.md` under `[Unreleased]`

### Acceptance Criteria
- AC1: `GET /api/v1/{resource}/{id}` returns `200` with correct response schema for existing resource
- AC2: `GET /api/v1/{resource}/{id}` returns `404` with error body for unknown ID
- AC3: `POST /api/v1/{resource}` returns `201` with created resource for valid input
- AC4: `POST /api/v1/{resource}` returns `422` for invalid/missing required fields
- AC5: `docs/openapi.yml` updated and validates with `swagger-cli validate docs/openapi.yml`
- AC6: Integration test passes against a real PostgreSQL database (TestContainers)
- AC7: `CHANGELOG.md` updated under `[Unreleased]`
- AC8: `./gradlew check` passes

---

## Story 5: [Frontend API Integration Title]
**Status:** TODO
**Size:** [S/M]
**Modules:** [WEB]
**Depends on:** Story 4

### Context
[Wire the new API endpoint into the React frontend via Redux Toolkit.]

### Tasks
- [ ] Add TypeScript types to `web/src/types/index.ts`
- [ ] Create API request function in `web/src/requests/{entity}Requests.ts`
- [ ] Create Redux slice in `web/src/store/{entity}Slice.ts`
- [ ] Write Jest tests for the slice (reducers + async thunks)
- [ ] Run `cd web && yarn test`

### Acceptance Criteria
- AC1: TypeScript types match the OpenAPI response schema exactly
- AC2: Redux slice handles `pending`, `fulfilled`, and `rejected` states
- AC3: Slice tests pass with mocked API responses
- AC4: `yarn test` passes with no new failures
- AC5: No TypeScript type errors (`yarn tsc --noEmit`)

---

## Story 6: [Frontend UI Component Title]
**Status:** TODO
**Size:** [M/L]
**Modules:** [WEB]
**Depends on:** Story 5

### Context
[React component implementation. References spec section 7.]

### Tasks
- [ ] Create component at `web/src/components/{EntityComponent}.tsx`
- [ ] Integrate with Redux slice from Story 5
- [ ] Implement loading state, error state, and empty state per spec
- [ ] Write component tests
- [ ] Run `cd web && yarn test`
- [ ] Manually verify in browser: happy path, error state, empty state

### Acceptance Criteria
- AC1: Component renders data from Redux state correctly
- AC2: Loading spinner shown while data is fetching
- AC3: Error message shown when API returns error
- AC4: Empty state shown when there is no data
- AC5: Component tests cover all three states (loading, data, error)
- AC6: No TypeScript errors, no console errors in browser

---

## Story 7: [Documentation Update]
**Status:** TODO
**Size:** [XS/S]
**Modules:** [DOCS]
**Depends on:** Story 4, Story 6

### Context
[Update user-facing documentation to reflect the new feature.]

### Tasks
- [ ] Update or add page in `docs/docs/` describing the feature
- [ ] Verify Docusaurus builds without errors (`cd docs && yarn build`)
- [ ] Update `METRICS.md` if new Prometheus metrics were added (Story 3)
- [ ] Final review of `CHANGELOG.md` entry

### Acceptance Criteria
- AC1: `cd docs && yarn build` completes with no errors or warnings
- AC2: New docs page accurately describes the feature and links to the API reference
- AC3: `METRICS.md` reflects all new Prometheus metrics (if any)
- AC4: `CHANGELOG.md` has exactly one entry for this feature under `[Unreleased]`

---

## Completion Summary

| Story | Status | PR | Notes |
|-------|--------|----|-------|
| 1 – [Title] | TODO | — | |
| 2 – [Title] | TODO | — | |
| 3 – [Title] | TODO | — | |
| 4 – [Title] | TODO | — | |
| 5 – [Title] | TODO | — | |
| 6 – [Title] | TODO | — | |
| 7 – [Title] | TODO | — | |

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
