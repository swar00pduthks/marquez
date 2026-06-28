# Test Plan: [Feature Title]

**Version:** 1.0
**QA Agent / Author:** [Name]
**Date:** [YYYY-MM-DD]
**Feature Spec:** `specs/<feature>/spec.md`
**Stories:** `specs/<feature>/stories.md`
**Status:** `Draft` | `Ready` | `Executed` | `Passed` | `Failed`

---

## 1. Scope

### In Scope
- [List the stories and components being tested]
- All endpoints defined in `spec.md` section 3
- All database changes defined in `spec.md` section 4
- Frontend components defined in `spec.md` section 7

### Out of Scope
- [Explicitly excluded, e.g., "Load testing of pre-existing endpoints not modified by this feature"]
- [e.g., "End-to-end browser automation (will be addressed in follow-up)"]

---

## 2. Test Environment

| Component | Value |
|-----------|-------|
| Java version | 17 |
| PostgreSQL version | 14 |
| Docker Compose config | `docker-compose.db.yml` |
| API base URL | `http://localhost:5000` |
| Test framework (Java) | JUnit 5 + Mockito + TestContainers |
| Test framework (Web) | Jest / Vitest |
| Load test tool | k6 (`api/load-testing/`) |

### Setup Commands

```bash
# Start test database
docker-compose -f docker-compose.db.yml up -d

# Run Java tests
./gradlew :api:test

# Run web tests
cd web && yarn test

# Run load tests (requires running API)
docker-compose up -d
.circleci/api-load-test.sh
```

---

## 3. Unit Test Cases

### 3.1 [ClassName] — Unit Tests

| ID | Test Name | Precondition | Action | Expected Result | Priority |
|----|-----------|--------------|--------|-----------------|----------|
| UT-1 | `should_return200_whenResourceExists` | Resource with ID exists in DB (mocked DAO) | Call `GET /api/v1/{resource}/{id}` | HTTP 200 + correct JSON body | P0 |
| UT-2 | `should_return404_whenResourceNotFound` | No resource with given ID (mocked DAO) | Call `GET /api/v1/{resource}/{id}` | HTTP 404 + error body | P0 |
| UT-3 | `should_return422_whenNameIsBlank` | — | Call `POST /api/v1/{resource}` with `{"name": ""}` | HTTP 422 + validation error | P0 |
| UT-4 | `should_return409_whenResourceAlreadyExists` | Resource with same name exists (mocked DAO) | Call `POST /api/v1/{resource}` | HTTP 409 + conflict error | P0 |
| UT-5 | `should_emitPrometheusCounter_onSuccess` | — | Call service method successfully | Prometheus counter incremented | P1 |
| UT-6 | `should_emitPrometheusCounter_onError` | DAO throws exception | Call service method | Prometheus error counter incremented | P1 |

[Add test cases for each new class. Negative tests are mandatory.]

---

## 4. Integration Test Cases

### 4.1 API Integration Tests

| ID | Test Name | Precondition | Action | Expected Result | Priority |
|----|-----------|--------------|--------|-----------------|----------|
| IT-1 | `testGet_returnsResource_fromDatabase` | Seed DB with test resource | `GET /api/v1/{resource}/{id}` | 200 + correct data from DB | P0 |
| IT-2 | `testPost_createsResource_inDatabase` | Empty DB | `POST /api/v1/{resource}` with valid body | 201 + resource persisted in DB | P0 |
| IT-3 | `testPost_isIdempotent_withSameName` | Resource already exists | `POST /api/v1/{resource}` same name | 200 (or 409) + no duplicate in DB | P0 |
| IT-4 | `testGet_returnsEmpty_whenNoData` | Empty DB / empty namespace | `GET /api/v1/{resource}?namespace=x` | 200 + empty array | P1 |
| IT-5 | `testMigration_appliesCleanly` | Fresh PostgreSQL 14 DB | Run Flyway migration | Migration succeeds, schema correct | P0 |
| IT-6 | `testMigration_appliesOnExistingData` | DB with production-like data fixture | Run Flyway migration | Migration succeeds, existing data intact | P0 |

---

## 5. Frontend Test Cases

### 5.1 Redux Slice Tests

| ID | Test Name | Action | Expected Result | Priority |
|----|-----------|--------|-----------------|----------|
| FE-1 | `should set loading state on pending` | Dispatch `fetchResource.pending` | `state.loading === true` | P0 |
| FE-2 | `should populate data on fulfilled` | Dispatch `fetchResource.fulfilled` with payload | `state.data` matches payload | P0 |
| FE-3 | `should set error on rejected` | Dispatch `fetchResource.rejected` | `state.error` set, `state.loading === false` | P0 |

### 5.2 Component Tests

| ID | Test Name | Precondition | Expected Result | Priority |
|----|-----------|--------------|-----------------|----------|
| FE-4 | `renders loading spinner when fetching` | Store state: `loading: true` | Spinner visible | P0 |
| FE-5 | `renders data when available` | Store state: `data: [{name: 'test'}]` | Row with "test" visible | P0 |
| FE-6 | `renders empty state when no data` | Store state: `data: []` | Empty state message visible | P1 |
| FE-7 | `renders error message on failure` | Store state: `error: 'Network error'` | Error message visible | P0 |

---

## 6. Load Test Cases

**Threshold:** p95 latency ≤ 200ms (GET), ≤ 500ms (POST) at 100 RPS sustained for 5 minutes.

| ID | Endpoint | Load Profile | Success Criteria |
|----|----------|-------------|-----------------|
| LT-1 | `GET /api/v1/{resource}/{id}` | 100 RPS, 5 min ramp | p95 ≤ 200ms, error rate < 0.1% |
| LT-2 | `GET /api/v1/{resource}?namespace=x` | 100 RPS, 5 min | p95 ≤ 200ms, error rate < 0.1% |
| LT-3 | `POST /api/v1/{resource}` | 50 RPS, 5 min | p95 ≤ 500ms, error rate < 0.1% |

### Load Test Commands

```bash
# Ensure API is running
docker-compose up -d

# Run k6 (from project root)
k6 run api/load-testing/your-feature-load-test.js \
  --out json=specs/<feature>/load-test-results.json
```

---

## 7. Security Test Cases

| ID | Test | Expected Result | Priority |
|----|------|-----------------|----------|
| SEC-1 | SQL injection via `name` parameter (`name='; DROP TABLE--`) | 400 or 422 — no DB error | P0 |
| SEC-2 | Oversized payload (`name` = 100KB string) | 400 or 413 | P0 |
| SEC-3 | Access cross-namespace resource (if multi-tenant) | 403 or 404 | P0 |
| SEC-4 | `Snyk test` on new dependencies | No new HIGH/CRITICAL CVEs | P0 |

---

## 8. Coverage Requirements

| Module | Target Line Coverage | Measured By |
|--------|---------------------|-------------|
| `marquez.api.YourResource` | ≥ 90% | Jacoco |
| `marquez.service.YourService` | ≥ 85% | Jacoco |
| `marquez.db.YourDao` | ≥ 80% | Jacoco |
| `web/src/store/yourSlice.ts` | ≥ 85% | Jest |
| `web/src/components/YourComponent.tsx` | ≥ 80% | Jest |

### Running Coverage

```bash
# Java coverage
./gradlew jacocoTestReport
open api/build/reports/jacoco/test/html/index.html

# Web coverage
cd web && yarn test --coverage
open web/coverage/lcov-report/index.html
```

---

## 9. Acceptance Criteria Traceability

| Story AC | Test Case IDs | Status |
|----------|---------------|--------|
| Story 1 - AC1 (migration naming) | IT-5 | TODO |
| Story 1 - AC2 (fresh DB migration) | IT-5 | TODO |
| Story 1 - AC3 (existing data migration) | IT-6 | TODO |
| Story 4 - AC1 (GET 200) | UT-1, IT-1 | TODO |
| Story 4 - AC2 (GET 404) | UT-2, IT-? | TODO |
| Story 4 - AC3 (POST 201) | UT-?, IT-2 | TODO |
| Story 4 - AC4 (POST 422) | UT-3 | TODO |
| [Continue for all story ACs...] | | |

---

## 10. Results

*To be filled in by QA agent after test execution.*

### Test Execution Summary

| Category | Total | Passed | Failed | Skipped |
|----------|-------|--------|--------|---------|
| Unit Tests | — | — | — | — |
| Integration Tests | — | — | — | — |
| Frontend Tests | — | — | — | — |
| Load Tests | — | — | — | — |
| Security Tests | — | — | — | — |

### Coverage Results

| Module | Target | Actual | Pass? |
|--------|--------|--------|-------|
| `YourResource` | ≥ 90% | —% | — |
| `YourService` | ≥ 85% | —% | — |
| `YourDao` | ≥ 80% | —% | — |

### Load Test Results

| Endpoint | p50 | p95 | p99 | Error Rate | Pass? |
|----------|-----|-----|-----|------------|-------|
| GET /api/v1/{resource}/{id} | — | — | — | — | — |
| POST /api/v1/{resource} | — | — | — | — | — |

### Report Artifacts

- Jacoco HTML: `api/build/reports/jacoco/test/html/index.html`
- Web coverage: `web/coverage/lcov-report/index.html`
- Load test JSON: `specs/<feature>/load-test-results.json`

### Overall QA Verdict

`[ ] PASS — Feature meets all P0 criteria and coverage targets`
`[ ] FAIL — See failures listed above`

**Signed off by:** [QA Agent / Name]
**Date:** [YYYY-MM-DD]

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
