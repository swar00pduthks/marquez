# Prioritized Backlog: Marquez Next-Gen Roadmap

**Author:** Product Manager AI Agent (Synthesized from Architect, UX, Data Scientist, and Analyst feedback)
**Context:** This document translates the collaborative proposals (`2117`, `2676`, and AI brainstorming sessions) into prioritized, actionable GitHub/Jira epics and issues.

---

## 🔴 P0: Critical Path (Foundational Architecture & UX)
*These issues are required to fix current technical debt, support multi-tenant scaling, and establish the baseline for future AI features.*

### Issue 1: Implement Dataset Schema Versioning Separation
*   **Reference:** Proposal 2676
*   **Description:** Separate `dataset_schema_versions` from `dataset_versions` to stop database bloat (e.g., 864k rows for a stable schema).
*   **Acceptance Criteria:**
    *   New Flyway migration created (do NOT modify existing scripts).
    *   Equality logic implemented (comparing ordered `name`+`type` pairs).
    *   API returns the correct schema version without impacting OpenLineage spec.
*   **Assignee:** Core Engineer, Chief Architect

### Issue 2: Enterprise Multi-Tenant Control Plane (UI)
*   **Reference:** Competitive Gap Analysis
*   **Description:** Redesign the top-level UI to support millions of tenants seamlessly.
*   **Acceptance Criteria:**
    *   Implement "Workspace/Tenant Switcher" dropdown in the global header.
    *   Ensure dataset tables use virtualized lists for instantaneous scrolling.
    *   Create RBAC configuration screens for tenant isolation.
*   **Assignee:** UX Designer, Frontend Engineer

### Issue 3: Run-Level Lineage API Extensions
*   **Reference:** Proposal 2117
*   **Description:** Extend the lineage API to accept a specific `run:{id}` or `dataset@{version}` to return the exact state of the graph at that point in time.
*   **Acceptance Criteria:**
    *   Update `/api/v1/namespaces/.../lineage` to accept versioned `nodeId`s.
    *   Ensure backwards compatibility if version is omitted.
    *   Unit tests cover edge cases where historical runs are queried.
*   **Assignee:** Core Engineer, QA Engineer

---

## 🟡 P1: High Priority (AI Foundation & Autonomous Ingestion)
*These issues transition Marquez from a passive catalog to an active context engine and ingestion hub.*

### Issue 4: Enterprise AI Context API (LLM Optimized)
*   **Reference:** Enterprise Agent Context Store Proposal
*   **Description:** Build a highly cached, low-latency API endpoint designed specifically for external enterprise AI agents to query context (lineage, DQ metrics) for RAG architectures.
*   **Acceptance Criteria:**
    *   Design `api/v1/context/` endpoints.
    *   Implement Redis caching layer in front of PostgreSQL.
    *   Performance Test: Must handle 50k RPS with sub-millisecond P99 latency.
*   **Assignee:** Chief Architect, Performance Tester, DevOps Engineer

### Issue 5: Autonomous GitHub/GitLab Lineage Scanners
*   **Reference:** Connector Engineer Profile
*   **Description:** Build bots that statically analyze repo code (SQL, Python, dbt) to auto-generate and push OpenLineage events to Marquez.
*   **Acceptance Criteria:**
    *   Scanner can parse basic SQL `INSERT INTO... SELECT FROM` statements.
    *   Context Engineering: Emitted OpenLineage event must contain the exact Git commit hash.
*   **Assignee:** Connector Engineer

### Issue 6: Predictive Lineage Engine (ETA & DQ Impact)
*   **Reference:** Data Scientist Profile
*   **Description:** Build ML models (as dynamically loadable Agent Skills) to predict job ETAs and calculate the blast radius of Data Quality failures.
*   **Acceptance Criteria:**
    *   Provide LangChain Tool definition for `predict_job_eta`.
    *   Predictions are appended to the lineage graph using custom OpenLineage facets.
*   **Assignee:** Data Scientist

---

## 🔵 P2: Enhancements (Premium UX & Competitive Parity)
*These features bring Marquez to full feature parity with commercial tools like DataHub and OpenMetadata.*

### Issue 7: The "Dataset 360" View & Schema Changelog
*   **Reference:** Data Analyst Feedback, UX Gap Analysis
*   **Description:** Revamp the dataset details page to include Data Profiling, Business Glossary tags, and a human-readable Schema Evolution timeline.
*   **Acceptance Criteria:**
    *   UI exposes the backend separation from Issue 1 as a visual diff (Green/Red highlights for added/removed columns).
    *   Add a "Data Health" tab for profiling metrics.
*   **Assignee:** UX Designer, Frontend Engineer

### Issue 8: Marquez Copilot & Skill Builder UI
*   **Reference:** AI Roadmap Proposal
*   **Description:** Introduce a native AI chatbot overlay inside the Marquez UI, and provide a low-code "Skill Builder" for users to add custom functions to it.
*   **Acceptance Criteria:**
    *   Chatbot can answer: "Why did this dataset fail?" by querying the Run-Level Lineage API (Issue 3).
    *   Graph dynamically highlights nodes the chatbot references.
    *   Admin UI to register new Agent Skills dynamically.
*   **Assignee:** Frontend Engineer, UX Designer