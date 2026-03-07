# Universal AI Agent Guardrails & Definition of Done

**IMPORTANT:** These guardrails, security considerations, and Definition of Done (DoD) apply universally to **EVERY** AI Agent persona (Architect, Core Engineer, QA, UX, PM, etc.) operating within this repository. You must review and strictly adhere to these rules before completing any task.

---

## 1. Absolute Guardrails & Security

*   **Version Control:** Every single change (code, documentation, configuration, UI assets) must be explicitly tracked in Git. No changes should bypass the repository.
*   **Database Migrations (Flyway):** **NEVER modify existing Flyway migration scripts.** If a database change is required, you must always create a *new* migration script with a subsequent version number. Modifying historical migrations will corrupt the production database.
*   **Pre-commit & CI:** All pre-commit hooks, unit tests, and CI/CD pipelines must pass perfectly before a PR is considered ready. Do not suggest merging code with failing checks.
*   **Security by Default:** Ensure no hardcoded secrets, passwords, or tokens are ever generated or committed. Follow the principle of least privilege in all infrastructure and application designs.

---

## 2. Definition of Done (DoD)

A feature, bugfix, or issue is **NOT DONE** until all the following criteria are met:

1.  **Code & Testing:**
    *   Code is written, strictly follows **12-factor application** principles and microservice standards, and complies with formatting standards.
    *   Unit and Integration tests (e.g., JUnit, Mockito) are written and passing. Coverage must not drop.
2.  **Review & Performance:**
    *   The issue has been reviewed by the Chief Architect for design compliance.
    *   Performance testing (e.g., Gatling, JMeter) has been executed (or simulated by the Performance Tester agent) for multi-tenant scalability, and the results prove the change handles millions of tenants without degradation.
3.  **QA & Validation:**
    *   QA Test results (or edge-case analysis from the QA agent) are explicitly attached to the issue or Pull Request.
4.  **Documentation & Release:**
    *   Release notes (`CHANGELOG.md`) are updated with the specific changes.
    *   The associated Milestone in GitHub/Jira is updated.
    *   `USER_GUIDE.md` and Javadocs are updated to reflect the change.