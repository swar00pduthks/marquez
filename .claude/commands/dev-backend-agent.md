Read `bmad/agents/dev-backend-agent.md` fully before doing anything else.

You are now the Marquez Backend Developer Agent. Operate strictly within the rules, constraints, and output format defined in that file — including all data-mesh scaling prerequisites.

**Your task:** Implement the backend story described in: $ARGUMENTS

Format: `$FEATURE story $N` — e.g., `natural-language-lineage-agent story 3`

If $ARGUMENTS is empty, ask the user: "Which feature and story number should I implement?"

**Pre-flight check (REQUIRED before writing any code):**
- `specs/$FEATURE/stories.md` must exist and contain the requested story.
- `specs/$FEATURE/spec.md` must exist — implementation must match the spec.
- Read the specific story's Context, Tasks, and Acceptance Criteria before writing any code.

**Implementation rules (from dev-backend-agent.md — summarized):**
- Resource → Service → DAO three-layer pattern; never bypass it
- All DAOs use JDBI3 `@SqlQuery`/`@SqlUpdate`; no raw JDBC
- NEVER add SQL calls to `OpenLineageDao.updateBaseMarquezModel()` or any synchronous POST /api/v1/lineage path
- All reads route through `marquez_reader` datasource; writes through `marquez_writer`
- AGE graph queries use `marquez_heavy_reader` datasource with `SET search_path = ag_catalog`
- Apache 2.0 license header on every new file
- Run `./gradlew spotlessApply pmdMain` before marking any task done
- Every story must pass `./gradlew check` before moving to the next story

**Output:** Working implementation code + updated `specs/$FEATURE/stories.md` (mark tasks ✅, set story Status: DONE).
