# Issue: Competitive Analyst Feedback on Proposals 2117 and 2676

**Role:** Competitive Analyst
**Target Proposals:** `2117-marquez-over-time.md`, `2676-version-dataset-schemas-separately.md`

## Competitive Analysis: Time-Travel Lineage and Schema Versioning

### Competitor Focus
- OpenMetadata
- DataHub (LinkedIn)
- Amundsen (Lyft)

### Current Capabilities
- **DataHub & OpenMetadata:** Both have strong historical tracking for metadata. OpenMetadata, for example, natively treats schema changes as distinct versions and explicitly surfaces a "Version History" tab where users can visually diff schemas over time. DataHub provides a comprehensive timeline of all metadata changes. Both use a combination of URNs and semantic versioning (v1.0, v1.1).
- **The Gap:** Neither tool handles run-level lineage ("Time-Travel" for entire pipelines based on a specific run ID) perfectly. They primarily focus on the state of an entity, rather than the state of a complex graph at a microsecond in time.

### Marquez Comparison
- **Proposal 2117 (Run-Level Lineage):** This is a massive competitive advantage. If Marquez can query an entire lineage graph based on a specific `run:id`, we leapfrog competitors who struggle to recreate the exact pipeline state during an incident.
- **Proposal 2676 (Separating Schema Versions):** This is a necessary architectural catch-up. Competitors already separate schema evolution from data freshness updates. The current design in Marquez (versioning schemas constantly) creates massive database bloat that competitors do not suffer from as heavily.

### Proposed Competitive Features

- **Feature:** Semantic Schema Versioning & Diffing UI (Offensive/Defensive)
  - **Rationale:** OpenMetadata already does this beautifully. Since Proposal 2676 gives us the backend capability to isolate actual schema changes, we MUST expose this in the API/UI. Instead of just logging the UUID, generate a sequential version number (v1, v2) for the dataset based on `dataset_schema_versions` changes, and expose a diff API.
  - **Urgency:** High. Without a clear schema evolution history, we fall behind DataHub and OpenMetadata in data governance capabilities.

- **Feature:** Automated Incident Root Cause Analysis API (Offensive)
  - **Rationale:** Using Proposal 2117 (lineage over time), we can introduce an AI-driven or heuristic-based "What Changed?" feature. When a job fails, we query the graph using `run:id`, compare it to the last successful `run:id`, and immediately output: "Upstream dataset schema changed (from 2676), causing this failure." This is something no competitor does autonomously out-of-the-box today.
  - **Urgency:** Medium-High. This solidifies Marquez as the premier tool for operational data lineage, not just a passive catalog.