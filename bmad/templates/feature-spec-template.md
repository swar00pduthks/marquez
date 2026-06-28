# Feature Spec: [Feature Title]

**Version:** 1.0
**Author:** [Architect agent / Name]
**Date:** [YYYY-MM-DD]
**PRD:** `specs/<feature>/prd.md`
**ADR:** `specs/<feature>/adr.md`
**Status:** `Draft` | `Ready for Dev` | `In Progress` | `Complete`

---

## 1. Summary

[One paragraph: what is being built, which modules are touched, and the key technical approach.
No more than 5 sentences.]

---

## 2. Affected Modules

| Module | Change Type | Estimated Size |
|--------|-------------|----------------|
| `api/` | `New feature / Modified / No change` | `[S/M/L/XL]` |
| `web/` | `New feature / Modified / No change` | `[S/M/L/XL]` |
| `clients/java/` | `New feature / Modified / No change` | `[S/M/L/XL]` |
| `clients/python/` | `New feature / Modified / No change` | `[S/M/L/XL]` |
| `chart/` | `New feature / Modified / No change` | `[S/M/L/XL]` |
| `docs/` | `Updated / No change` | `[S/M/L/XL]` |

---

## 3. API Specification

### 3.1 New Endpoints

```yaml
# OpenAPI 3.0 snippet — will be merged into docs/openapi.yml
paths:
  /api/v1/your-resource:
    get:
      summary: [Summary]
      operationId: getYourResource
      tags:
        - YourResource
      parameters:
        - name: namespaceId
          in: query
          required: true
          schema:
            type: string
      responses:
        '200':
          description: [Description]
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/YourResourceResponse'
        '404':
          $ref: '#/components/responses/NotFound'
```

### 3.2 Modified Endpoints

| Method | Path | Change Description | Breaking? |
|--------|------|--------------------|-----------|
| `GET` | `/api/v1/...` | [What changes] | No |

### 3.3 Response Schemas

```json
{
  "yourResource": {
    "id": "uuid",
    "name": "string",
    "createdAt": "2024-01-01T00:00:00Z",
    "updatedAt": "2024-01-01T00:00:00Z"
  }
}
```

---

## 4. Database Schema Changes

### 4.1 New Tables

```sql
-- Flyway: api/src/main/resources/marquez/db/migration/V{N}__add_your_table.sql
-- SPDX-License-Identifier: Apache-2.0

CREATE TABLE IF NOT EXISTS your_table (
    uuid        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(255) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_your_table_name ON your_table(name);
```

### 4.2 Modified Tables

| Table | Change | Type | Online-Safe? |
|-------|--------|------|--------------|
| `datasets` | Add column `quality_score FLOAT` | Additive (nullable) | Yes |
| `runs` | Add index on `created_at` | Index creation | Yes (CONCURRENTLY) |

### 4.3 Migration Notes

- Migration file: `V{N}__[description].sql` (next sequential number after `V{current}`)
- All column additions must be nullable OR have a default — no `NOT NULL` without default on existing tables.
- Index creation on large tables must use `CREATE INDEX CONCURRENTLY`.
- **NEVER** drop a column or rename a column in a migration — mark as deprecated first.

---

## 5. Data Flow

```
[Client/OpenLineage]
        │
        ▼
POST /api/v1/lineage
        │
        ▼
OpenLineageResource.java
        │
        ▼
LineageService.java ──────────► YourNewService.java
        │                               │
        ▼                               ▼
LineageDao.java                  YourNewDao.java
        │                               │
        ▼                               ▼
[runs table]                    [your_new_table]
        │
        ▼ (if AGE enabled)
V3GraphService.java
        │
        ▼
Apache AGE / Cypher query
```

[Adjust the diagram to reflect your actual data flow.]

---

## 6. Backend Implementation Details

### 6.1 New Classes

| Class | Package | Responsibility |
|-------|---------|----------------|
| `YourResource` | `marquez.api` | REST endpoint handler |
| `YourService` | `marquez.service` | Business logic |
| `YourDao` | `marquez.db` | JDBI3 database access |
| `YourRow` | `marquez.db.models` | JDBI row model |
| `YourResponse` | `marquez.api.models` | API response DTO |

### 6.2 Modified Classes

| Class | File | Change |
|-------|------|--------|
| `MarquezApp` | `api/src/main/java/marquez/MarquezApp.java` | Register `YourResource` |
| `MarquezContext` | `api/src/main/java/marquez/MarquezContext.java` | Instantiate `YourService` |

### 6.3 Key Algorithms / Logic

[Describe any non-trivial business logic. Use pseudocode if helpful.]

```
FUNCTION processYourFeature(input):
  1. Validate input (throw ValidationException if invalid)
  2. Check if resource already exists (DAO.findByName)
  3. If exists: update (DAO.update) and return existing UUID
  4. If new: insert (DAO.insert) and return new UUID
  5. Emit Prometheus counter: marquez_your_feature_total
```

### 6.4 Error Handling

| Condition | HTTP Status | Error Message |
|-----------|-------------|---------------|
| Resource not found | `404 Not Found` | `"your-resource not found: {id}"` |
| Invalid input | `422 Unprocessable Entity` | `"field 'name' is required"` |
| Conflict | `409 Conflict` | `"your-resource already exists: {name}"` |
| Internal error | `500 Internal Server Error` | `"An unexpected error occurred"` |

---

## 7. Frontend Implementation Details

### 7.1 New Components

| Component | Path | Purpose |
|-----------|------|---------|
| `YourComponent` | `web/src/components/YourComponent.tsx` | [Purpose] |

### 7.2 State Management

- **New Redux slice**: `web/src/store/yourSlice.ts`
- **New API request**: `web/src/requests/yourRequests.ts`
- **New TypeScript types**: `web/src/types/index.ts` — add `YourType`

### 7.3 UI Behavior

[Describe user interactions, loading states, error states, and empty states.]

---

## 8. Security Considerations

| Concern | Mitigation |
|---------|-----------|
| [e.g., Endpoint exposes all namespaces] | [e.g., Filter by authenticated user's tenant] |
| [e.g., SQL injection via name parameter] | [e.g., Parameterized query via JDBI @Bind] |
| [e.g., New dependency with CVE] | [e.g., Pin to patched version X.Y.Z] |

---

## 9. Performance Considerations

| Concern | Expected Impact | Mitigation |
|---------|-----------------|-----------|
| [e.g., Full table scan on `datasets`] | [e.g., Slow at > 100k rows] | [e.g., Add index on `namespace_uuid`] |
| [e.g., N+1 query in lineage graph] | [e.g., Latency spike per hop] | [e.g., Batch fetch with JOIN] |

**Load test targets (must be verified by QA agent):**
- `GET` endpoints: p95 ≤ 200ms at 100 RPS
- `POST` endpoints: p95 ≤ 500ms at 100 RPS

---

## 10. Observability

### New Prometheus Metrics

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `marquez_your_feature_total` | Counter | `result=[success\|error]` | Total your-feature operations |
| `marquez_your_feature_duration_seconds` | Histogram | — | Latency of your-feature processing |

### Logging

[Describe key log events at INFO and WARN level that operators will rely on.]

```java
log.info("Processing your-feature for namespace={}", namespace);
log.warn("your-feature not found for id={}", id);
```

---

## 11. Testing Requirements

[Brief summary — full test plan in `specs/<feature>/test-plan.md`]

| Test Type | Coverage Target | Notes |
|-----------|-----------------|-------|
| Unit tests | All new classes | Mock DAO layer |
| Integration tests | All new endpoints | TestContainers + real DB |
| Load tests | All new `GET`/`POST` | k6, 100 RPS, 5 min |

---

## 12. Open Technical Questions

| # | Question | Owner | Resolved? |
|---|----------|-------|-----------|
| 1 | [Technical question blocking implementation] | [Name] | No |

---

## 13. Out of Scope

[List anything that was explicitly excluded from this spec. Reference the PRD Non-Goals.]

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
