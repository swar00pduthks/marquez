# Parallel Execution Guide for AI Agents

To make these different AI agent roles (Product Owner, Architect, Developer, Tester, DevOps) work parallelly and collaboratively, you need to transition from running a single prompt to using a **Multi-Agent Orchestration Framework**.

Here is a guide on how to conceptualize and implement parallel, collaborative agent workflows.

---

## 1. The Concept of Multi-Agent Systems

In a multi-agent system, each "agent" is initialized with a specific persona (the Markdown files you created), a set of tools (e.g., ability to read files, run code, search the web), and a specific task.

Instead of waiting for one agent to finish the entire project, you design a workflow where agents work concurrently or in a highly optimized sequence (like an assembly line).

### Key Architectural Patterns for Parallelism
*   **Hierarchical / Managerial:** A "Manager" agent breaks down a large goal into sub-tasks and assigns them to worker agents (Developer, DevOps) simultaneously. The manager waits for all to complete before proceeding.
*   **Publish-Subscribe (Event-Driven):** Agents subscribe to an "event bus". When the Architect finishes a design document, it publishes an event. Both the Developer (to write code) and Tester (to write test plans) react to this event and start working at the same time.
*   **Graph-based Workflows (State Machines):** You define a rigid graph of execution where certain nodes (agents) can execute in parallel if they don't have dependencies on each other.

---

## 2. Recommended Frameworks

To actually write the code that makes these agents work together, you should use one of the popular multi-agent frameworks.

### A. CrewAI
CrewAI is designed specifically for role-playing agents. It uses the concepts of "Agents", "Tasks", and a "Crew".

*   **How it enables parallelism:** In CrewAI, you can set `process=Process.hierarchical` to allow a manager to delegate tasks. More importantly, when defining tasks, you can set `async_execution=True`. This means the task will be executed asynchronously, allowing other tasks to start while it finishes.
*   **Best for:** When you have well-defined roles and want a system that feels like managing a human team.

### B. AutoGen (by Microsoft)
AutoGen focuses on conversational patterns between agents. Agents chat with each other to solve problems.

*   **How it enables parallelism:** AutoGen supports asynchronous programming (`asyncio` in Python). You can have a "UserProxy" agent initiate multiple chats with different specialized agents simultaneously using `await asyncio.gather(...)`.
*   **Best for:** Complex problem-solving where agents need to debate, review each other's work back-and-forth, or execute code securely.

### C. LangGraph
LangGraph (built on top of LangChain) treats multi-agent workflows as a cyclic graph (state machine).

*   **How it enables parallelism:** When defining the graph, you can route the execution flow to multiple nodes (agents) at the same time. If Node A (Architect) outputs a design, the graph can transition to *both* Node B (Developer) and Node C (Tester) concurrently, passing the design state to both. The graph then waits at a "Join" node until both are done.
*   **Best for:** Highly deterministic workflows where you need strict control over the state, loops (e.g., Developer -> Tester -> Developer), and parallel branches.

---

## 3. Example Scenario: The Parallel Workflow

Here is how you would use the Markdown files you created in a parallel workflow (conceptualized using a LangGraph-style approach):

**Phase 1: Sequential Planning**
1.  **Product Owner** reads user requirements and outputs a formatted `requirements.json`.
2.  **Architect** reads `requirements.json` and outputs `system_design.md`.

**Phase 2: Parallel Execution (The "Assembly Line")**
*Once `system_design.md` is generated, the workflow splits into three parallel tracks:*

*   **Track A (Development):** The **Developer** agent is given the design and instructed to write the backend API code.
*   **Track B (Testing Preparation):** The **Tester** agent is given the same design and instructed to write the E2E test suite (even before the code is finished).
*   **Track C (Infrastructure):** The **DevOps** agent is given the design and instructed to write the Terraform scripts and CI/CD pipeline YAML.

**Phase 3: Synchronization & Verification**
1.  The system waits for Track A, B, and C to finish.
2.  Once Track A (Code) and Track B (Tests) are done, a new task is triggered: Execute the tests against the code.
3.  If tests fail, the workflow loops back to the Developer. If tests pass, the code is packaged using the artifacts from the DevOps agent.

---

## 4. How to Apply Your Markdown Files

When you initialize your agents in python (e.g., using `langchain`), you inject these markdown files as the `system_prompt`.

```python
# Example pseudo-code
import os

def load_prompt(role):
    with open(f"agents/{role}.md", "r") as f:
        return f.read()

architect_agent = create_agent(
    model="gpt-4",
    system_prompt=load_prompt("architect"),
    tools=[read_file_tool, web_search_tool]
)

developer_agent = create_agent(
    model="gpt-4",
    system_prompt=load_prompt("developer"),
    tools=[read_file_tool, write_file_tool, bash_tool]
)

# ... use an orchestrator (CrewAI/LangGraph) to run them concurrently
```

## 5. Executing in Copilot Workspace or Antigravity

Currently, IDE-integrated tools like **GitHub Copilot Workspace** and **Antigravity** operate primarily as single-agent copilots that assist you directly, rather than multi-agent orchestrators where agents talk to each other independently in the background.

However, you can simulate parallel workflows or apply these personas using those tools in the following ways:

### Copilot Workspace
Copilot Workspace is designed to draft plans and implement them across a repository based on an issue.
*   **Applying Personas:** While you cannot spin up a "Tester" and a "Developer" simultaneously on different threads, you can instruct the Workspace plan to explicitly act sequentially *as* those personas.
*   **Usage:** In the Copilot Workspace prompt, you can paste the contents of the `architect.md` and `developer.md` and tell it:
    > "First, act as the Architect and write a design document in `design.md`. Once complete, act as the Developer and implement the code according to the design."
*   **Parallelism:** Copilot Workspace does not currently support true parallel execution of distinct, specialized AI agents working on different tasks at the exact same time.

### Antigravity (and similar terminal copilots)
*   **Usage:** You can open multiple terminal instances and start an Antigravity session in each.
*   **Applying Personas:** In Terminal 1, paste the `developer.md` prompt and ask it to write code. In Terminal 2, paste the `tester.md` prompt and give it the same specification to write tests.
*   **Parallelism:** The parallelism here is driven by *you* (the human orchestrator). You are manually managing multiple copilot sessions concurrently rather than the AI managing itself.

To achieve true, hands-off parallel agent execution (where the Developer writes code *while* the Tester writes tests autonomously, and they synchronize automatically), a dedicated framework like CrewAI, AutoGen, or LangGraph is required.

## Summary
To make these roles work in parallel autonomously:
1.  Choose a multi-agent framework (CrewAI, AutoGen, or LangGraph).
2.  Load the `.md` files as the core "System Prompt" for each respective agent.
3.  Design a workflow graph or asynchronous task list where non-dependent tasks (like writing tests based on a spec and writing code based on a spec) are executed concurrently by the orchestrator.