# Issue: Data Analyst Feedback on Proposals 2117 and 2676

**Role:** Data Analyst / Data Consumer
**Target Proposals:** `2117-marquez-over-time.md`, `2676-version-dataset-schemas-separately.md`

## Consumer Feedback: Lineage Over Time and Schema Versioning

### Persona Focus
- **Data Analysts / Business Analysts:** Trying to understand why a BI dashboard failed today, when it was working fine yesterday.
- **Support Engineers:** Investigating a data quality issue reported by an end-user without writing code or deciphering logs.

### Initial Reaction to Proposals
- **2117 (Run-Level Lineage):** Highly technical implementation, but the business value is incredible. The ability to see the *exact* state of the data flow at the time my report failed is exactly what I need.
- **2676 (Separating Schema Versions):** Backend optimization, but it solves a huge frustration: When I look at the "History" of a dataset right now, it shows hundreds of thousands of updates just because a job ran. I only care when the *structure* of the data actually changed (e.g., someone deleted a column). This proposal makes finding actual changes possible.

### Discovery Test: "What changed in this dataset?"
- With **2676**, the database will only log a new schema version when a column is added, removed, or changed. This means the UI can finally have a clean "Schema Changelog."
- **Friction Point (2676):** The proposal states: "This is not intended to alter any user-facing behaviour." As a Data Analyst, **I completely disagree**. The UI *must* change to expose this new `dataset_schema_versions` table. I want a button that says "Show me all times the schema changed this month," rather than scrolling through 800,000 identical runs.

### Trust Assessment: "Why did my dashboard break?"
- With **2117**, I can look at my broken dashboard dataset, get the `run:id` of the failure, and query Marquez for the upstream lineage *at that exact moment*.
- **Friction Point (2117):** The `nodeId` formatting (`job:{namespace}:{job}@{version}`) is strictly for developers. If I have to type or copy-paste UUIDs like `run:a03422cf..` to find out why my data is broken, I won't use it. The UI must translate a calendar click ("Tuesday at 9 AM") into these API calls automatically.

### Summary of Needs
1.  **Expose Schema Changes:** Do not hide the optimization in 2676. Use it to power a clean "Schema History" tab in the UI so data consumers can see *when* a column was dropped.
2.  **Visual Time-Travel:** Build a visual calendar or timeline scrubber in the UI that uses the 2117 API under the hood, so I never have to see a UUID or a complex `nodeId` string.