# Issue: Competitor Gap Analysis & UX Proposal (Marquez vs. OpenMetadata/DataHub)

**Participants:** Competitive Analyst, UX Designer
**Context:** Assessing features present in competitor tools (OpenMetadata, DataHub) that Marquez currently lacks, with a specific focus on "data platform grade" enterprise UX and multi-tenant scalability.

---

## 1. Competitive Analyst Assessment

While Marquez has a world-class backend for capturing OpenLineage events, when compared to full-featured "big applications" like OpenMetadata and DataHub, we have significant gaps in our platform offering. If we want to win enterprise deals, we must build the following missing capabilities:

### Missing Feature 1: Data Profiling & Quality Dashboards
*   **Competitor Landscape:** OpenMetadata natively visualizes column-level profiling data (null percentages, distinct counts, min/max values) and Data Quality (DQ) test results directly on the dataset page.
*   **Marquez Gap:** Marquez captures column-level lineage and some facets, but lacks a dedicated UI for data profiling. A user cannot easily see the "health" of a table at a glance.

### Missing Feature 2: Business Glossary & Stewardship (Enterprise Governance)
*   **Competitor Landscape:** DataHub and OpenMetadata both offer rich UIs for non-technical users to define Business Glossaries, tag PII, assign Data Stewards/Owners, and establish certification workflows.
*   **Marquez Gap:** Marquez is heavily focused on the technical metadata (jobs, runs, datasets). We lack the human-curated business metadata layer.

### Missing Feature 3: Native AI Chatbot / AI Discovery Assistant
*   **Competitor Landscape:** Competitors are rolling out LLM-powered interfaces allowing users to "chat with their data catalog" to find tables or understand SQL snippets.
*   **Marquez Gap:** We have the perfect data structure (run-level lineage) to power an AI, but we lack the conversational UI and LLM integration to expose it.

---

## 2. UX Designer Proposal: Enterprise Data Platform Grade UI

To support these new features and ensure Marquez feels like a top-tier enterprise application, we must redesign the UI architecture. It cannot just be a lineage graph anymore.

### UX Epic 1: The Multi-Tenant Control Plane
*   **The Problem:** At "multi-tenant scale" (handling millions of events across workspaces), a single flat UI will break.
*   **The Design:**
    *   Introduce a global "Workspace / Tenant Switcher" in the top navigation, similar to AWS or Slack.
    *   Implement high-performance virtualization in the UI grids/tables so scrolling through 50,000 datasets is instantaneous.
    *   Design an RBAC (Role-Based Access Control) settings page that allows admins to easily partition datasets and jobs by tenant or team.

### UX Epic 2: The "Dataset 360" View
*   **The Design:** Redesign the Dataset Details page to be a comprehensive "360 view".
    *   **Tab 1 - Schema & Lineage:** The current view, but enhanced.
    *   **Tab 2 - Profiling & Health:** A new visual dashboard showing Data Quality over time (addressing Missing Feature 1).
    *   **Tab 3 - Governance:** A new section for applying Business Glossary terms, tags, and assigning Ownership (addressing Missing Feature 2).

### UX Epic 3: Marquez Copilot (AI Chatbot UX)
*   **The Design:** A floating, collapsible chat interface accessible from any screen.
    *   *Interaction:* When a user asks "Show me tables related to revenue," the bot responds with cards that can be clicked to navigate directly to the Dataset 360 view.
    *   *Context Awareness:* If the user is looking at a failed job in the lineage graph, the chatbot should automatically prepopulate prompts like: *"Diagnose this failure based on historical runs."*