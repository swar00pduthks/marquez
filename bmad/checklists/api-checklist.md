# API Endpoint Development Checklist

Use this checklist for every new or significantly modified REST endpoint.

---

## Design (Before Writing Code)

- [ ] Endpoint defined in `specs/<feature>/spec.md` section 3 with full OpenAPI 3.0 snippet
- [ ] Response schema reviewed against existing schemas in `docs/openapi.yml`
- [ ] Endpoint follows naming convention: `/api/v{N}/{plural-resource}/{id}/{sub-resource}`
- [ ] HTTP method semantics correct: `GET` = read, `POST` = create, `PUT`/`PATCH` = update, `DELETE` = delete
- [ ] Idempotency behaviour defined for `PUT`/`PATCH` endpoints
- [ ] Error responses defined: 400, 404, 409, 422, 500 at minimum
- [ ] Non-breaking: does not change existing endpoint response shapes

---

## Implementation

### Resource Class (`marquez.api.{Entity}Resource`)
- [ ] Class annotated with `@Path`, `@Produces(MediaType.APPLICATION_JSON)`
- [ ] Constructor takes service dependency via `@NonNull` injection
- [ ] Each handler method annotated with `@GET`/`@POST`/`@PUT`/`@DELETE`, `@Path`, `@Timed`
- [ ] Path parameters use `@PathParam`, query parameters use `@QueryParam`
- [ ] Input validation via Bean Validation (`@Valid`, `@NotNull`, `@NotBlank`)
- [ ] Registered in `MarquezApp.java`
- [ ] Apache 2.0 license header present

### Service Class (`marquez.service.{Entity}Service`)
- [ ] Business logic encapsulated here — resource class only delegates
- [ ] Service returns domain objects, not DB row models
- [ ] `Optional` returned for find-by-id operations (not null)
- [ ] Prometheus counter/histogram metrics emitted on key paths
- [ ] Instantiated in `MarquezContext.java`

### DAO Interface (`marquez.db.{Entity}Dao`)
- [ ] JDBI3 `@RegisterBeanMapper` or `@RegisterRowMapper` configured
- [ ] All SQL uses `@SqlQuery`/`@SqlUpdate`/`@SqlBatch` — no raw JDBC
- [ ] Parameters bound with `@Bind`/`@BindBean`/`@BindList`
- [ ] `Optional` return type for single-row queries

---

## Error Handling

- [ ] `404 Not Found` — returned when resource UUID/name does not exist
- [ ] `422 Unprocessable Entity` — returned for validation failures (bad input)
- [ ] `409 Conflict` — returned when a unique constraint would be violated
- [ ] `500 Internal Server Error` — caught at resource level and logged before re-throw
- [ ] Error responses follow existing Marquez error body format:
  ```json
  { "code": 404, "message": "dataset not found: my-dataset" }
  ```

---

## OpenAPI Documentation

- [ ] Endpoint added/updated in `docs/openapi.yml`
- [ ] `summary`, `operationId`, `tags` all filled in
- [ ] All path/query parameters documented with `description` and `schema`
- [ ] All response codes documented with `description` and `content` schema
- [ ] New schema components added to `components/schemas` (not inlined)
- [ ] `swagger-cli validate docs/openapi.yml` passes

---

## Tests

### Unit Tests (`{Entity}ResourceTest.java`)
- [ ] Happy path: correct status code + response body
- [ ] Not found: 404 with error body
- [ ] Invalid input: 422 with validation message
- [ ] Conflict: 409 (if applicable)
- [ ] Service is mocked — resource test does not hit the database
- [ ] `@ExtendWith(MockitoExtension.class)` used
- [ ] Tests use AssertJ for assertions

### Integration Tests (`{Entity}ResourceIntegrationTest.java`)
- [ ] TestContainers spins up real PostgreSQL
- [ ] Full HTTP call made via Dropwizard test client
- [ ] DB seeded with fixture data before test
- [ ] DB cleaned up after each test (`@AfterEach` or `@Transactional`)
- [ ] Tests for both happy path and error paths

---

## Load Test

- [ ] k6 script created in `api/load-testing/` for the new endpoint
- [ ] `GET` endpoints: p95 ≤ 200ms at 100 RPS
- [ ] `POST` endpoints: p95 ≤ 500ms at 100 RPS
- [ ] Results stored in `specs/<feature>/load-test-results.json`

---

## Security

- [ ] No raw string concatenation into SQL (use JDBI bind params)
- [ ] No sensitive data (tokens, passwords) in response bodies or logs
- [ ] If endpoint returns data from multiple namespaces, namespace isolation is enforced
- [ ] Request size limits enforced (Dropwizard `maxRequestEntitySize`)

---

SPDX-License-Identifier: Apache-2.0
Copyright 2018-2024 contributors to the Marquez project.
