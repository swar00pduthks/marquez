# Issue: UX Designer Feedback on Proposals 2117 and 2676

**Role:** UX Designer
**Target Proposals:** `2117-marquez-over-time.md`, `2676-version-dataset-schemas-separately.md`

## UX Design Specification: Time-Travel & Schema Evolution

### User Problem
1. **Marquez Over Time (2117):** Users (data engineers, but also support and business analysts) need to "go back in time" to see what a lineage graph looked like at a specific moment when an error occurred, but querying by raw UUIDs (`@947c0388`) or explicit datetimes is highly technical and error-prone.
2. **Separating Schema Versions (2676):** While optimizing the database by separating schema versions from dataset runs is great for performance, the UI must abstract this complexity. A non-technical user checking data quality doesn't want to decipher the difference between a `dataset_version` and a `dataset_schema_version`.

### User Journey
1. A Support Engineer gets a ticket that a BI dashboard was broken "last Tuesday morning."
2. They open Marquez, locate the dataset, and need to view its state and upstream lineage *at that specific time*.
3. They notice the dataset schema changed sometime on Monday, which caused the failure. They need to easily diff the schema.

### Wireframe / Layout Description
- **Header:** Add a persistent global "Time Context" toggle. Default is "Now".
- **Main Content Area (Lineage Graph):**
  - When "Time Context" is changed to a past date/run, the entire graph dims slightly, and a banner appears: *"Viewing historical lineage as of [Date/Run]"*.
  - Nodes in the graph must visually indicate if they were active/failing at that specific time.
- **Sidebar Navigation (Dataset Details):**
  - **Schema Tab:** Must combine the logic of 2676. Show the schema for the *selected run/time*.
  - Add a "Schema History" sub-tab showing a simple, human-readable timeline of *only* when the schema actually changed (ignoring the 864,000 redundant runs).

### Interaction Details
- **Action (Scanning over time):** Proposal 2117 assumes "the ability to scan over time can be implemented within UI." We should implement this as a visual "scrubber" or slider at the bottom of the screen (similar to video editing software) with markers showing where significant events happened (e.g., job failures, *schema changes* based on 2676).
- **Action (Schema Diff):** Clicking a node on the timeline where a schema changed opens a modal highlighting exactly what columns were added/removed/type-changed in plain English (e.g., "Column `user_id` changed from `INT` to `STRING`").

### Accessibility Considerations
- UUIDs are completely inaccessible to human memory. The UI must map runs and versions to human-readable timestamps and semantic tags (e.g., "Failed Run on Tuesday 9:00 AM" instead of `run:a03422cf`).
- Schema diffs must use clear color coding (Green for added, Red/Strikethrough for removed) with high contrast for colorblind users.