# Issue: Collaborative AI Product Roadmap (Phase 1 & Phase 2)

**Participants:** Product Manager, UX Designer, Competitive Analyst, Data Analyst
**Context:** Brainstorming based on existing proposals (2117, 2676) and new mandates to compete as a full metadata platform with AI chatbots, advanced search, and business-user accessibility.

---

## Part 1: Persona Feedback & Brainstorming

### Competitive Analyst
"Looking at Proposal 2117 (Run-Level Lineage) and 2676 (Schema Versioning), we have the backend foundation. But OpenMetadata and DataHub are winning the enterprise market because they aren't just logging this data; they make it instantly searchable and interactive via AI. **We need to stop thinking of Marquez as just an OpenLineage consumer.** We need a 'Data Discovery' layer. We need an AI Chatbot that can parse the run-level lineage and answer: *'Why did my dashboard break today?'*"

### UX Designer
"I agree with the Competitive Analyst. The backend optimizations in 2676 are great, but the UI must abstract away `dataset_schema_versions` for non-developers. I want to build a highly polished 'Schema Evolution' timeline view. Furthermore, for the AI Chatbot, we need a premium chat interface overlaid on the lineage graph. When a user asks a question, the graph should dynamically highlight the nodes the AI is talking about."

### Data Analyst / Data Consumer
"From the business side, I don't care about UUIDs or API endpoints. If you build an AI chatbot, I want to ask it business questions: *'Which datasets contain PII?'* or *'What upstream table caused the delay in the Daily Revenue report?'* If Marquez can use run-level lineage to answer that in plain English, our support tickets will drop by 80%."

### Product Manager
"This is excellent alignment. We will split this into two phases. Phase 1 will focus on exposing the backend improvements (2117, 2676) into an intuitive, accessible UI with advanced search. Phase 2 will introduce the AI Chatbot to establish true competitive parity/superiority against DataHub and OpenMetadata."

---

## Part 2: The Roadmap Proposals

### Phase 1 (Short-Term): Semantic Search & Schema Evolution UI

**Epic: Advanced Discovery & Visual Time-Travel**
- **Market Fit Justification:** Data consumers and support teams currently struggle to find relevant datasets and trace historical failures using raw UUIDs. We need to match competitors' basic data discovery capabilities.
- **Competitive Advantage:**
    - *Short Term:* Closes the gap with OpenMetadata's schema history tracking.
    - *Long Term:* Builds the necessary semantic search index required for the Phase 2 AI features.
- **High-Level Requirements:**
    1. **Semantic Search Engine:** Implement an advanced dataset search (e.g., Elasticsearch/OpenSearch integration) that searches across tags, descriptions, and historical schema changes, not just exact name matches.
    2. **Schema Evolution UI:** Expose the data from Proposal 2676 in a new "Schema Changelog" tab. Visually diff schemas (Green for added, Red for removed) over time.
    3. **Time-Scrubber UX:** Implement the UX Designer's vision for Proposal 2117: A visual timeline slider on the lineage graph to "go back in time" without typing UUIDs.

### Phase 2 (Long-Term): AI-Powered Metadata Assistant

**Epic: The 'Marquez Copilot' (AI Chatbot)**
- **Market Fit Justification:** In the era of AI, manual root-cause analysis is too slow. Business teams want conversational interfaces to query metadata.
- **Competitive Advantage:**
    - *Short Term:* Matches the AI features recently announced by major commercial data catalogs.
    - *Long Term:* Creates a massive strategic moat. By combining our unique run-level lineage (2117) with an LLM, Marquez can autonomously diagnose pipeline failures.
- **High-Level Requirements:**
    1. **AI Chatbot Interface:** Build a premium, sliding chat panel in the Marquez UI.
    2. **Context-Aware Graph Interaction:** When the AI explains a pipeline failure, the UI must dynamically highlight the specific nodes and edges in the lineage graph being referenced.
    3. **Natural Language to Lineage Query:** The backend must translate natural language (e.g., "Why is the revenue table late?") into complex `run:id` lineage queries, analyze the diff, and return a plain-English explanation.
    4. **Multi-Cloud LLM Support:** Ensure the AI integration is cloud-agnostic (can plug into AWS Bedrock, Azure OpenAI, or local models) to maintain our zero vendor lock-in architectural mandate.