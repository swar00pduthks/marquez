### Problem

👋 Thanks for opening a [pull request](https://github.com/MarquezProject/marquez/blob/main/CONTRIBUTING.md#submitting-a-pull-request)! Please include a brief summary of the problem your change is trying to solve, or bug fix. If your change fixes a bug or you'd like to provide context on why you're making the change, please [link the issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue) as follows:

Closes: #ISSUE-NUMBER

### Solution

Please describe your change as it relates to the problem, or bug fix, as well as any dependencies. If your change requires a database schema migration, please describe the schema modification(s) and whether it's a _backwards-incompatible_ or _backwards-compatible_ change.

> **Note:** All database schema changes require discussion. Please [link the issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue) for context.

One-line summary:

### Checklist

**Code quality**
- [ ] You've [signed-off](https://github.com/MarquezProject/marquez/blob/main/CONTRIBUTING.md#sign-your-work) your work
- [ ] Your changes are accompanied by tests (_if relevant_)
- [ ] Your change contains a [small diff](https://kurtisnusbaum.medium.com/stacked-diffs-keeping-phabricator-diffs-small-d9964f4dcfa6) and is self-contained
- [ ] You've included a [header](https://github.com/MarquezProject/marquez/blob/main/CONTRIBUTING.md#copyright--license) in any source code files (_if relevant_)
- [ ] You've included a one-line summary of your change for the [`CHANGELOG.md`](https://github.com/MarquezProject/marquez/blob/main/CHANGELOG.md#unreleased)

**Database migrations** _(skip if no schema change)_
- [ ] Migration file follows Flyway naming: `V{N}__description.sql` (two underscores, sequential number)
- [ ] Migration is backward-compatible — no `NOT NULL` without default, no column drops or renames
- [ ] `marquez_data_model.md` updated to reflect the new schema

**API changes** _(skip if no API surface change)_
- [ ] `docs/openapi.yml` updated with new or modified endpoints, parameters, and response schemas
- [ ] `spec/openapi.yml` kept in sync with `docs/openapi.yml` (`cp docs/openapi.yml spec/openapi.yml`)
- [ ] Existing v1 API response shapes are unchanged (no breaking changes without a version bump)

**Observability** _(skip if no new metrics)_
- [ ] New Prometheus metrics documented in `METRICS.md`

**Documentation**
- [ ] User-facing changes documented in `docs/docs/`
- [ ] `cd docs && yarn build` passes with no errors
