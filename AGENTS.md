# Agent Guidelines & Project Architecture (AGENTS.md)

This document provides a detailed understanding of the project's architecture, patterns, and strict guidelines for AI agents working on this repository.

## 1. Project Understanding & Architecture

- **Project Context**: This repository is a custom fork of Marquez, an open-source metadata service for data ecosystems. It acts as an Enterprise AI Context Store, providing data lineage, metadata, and data quality metrics.
- **Framework & Core Tech**: The core backend components are implemented using Java, Dropwizard, and PostgreSQL. It uses Gradle for build management. The frontend is built using React and TypeScript.
- **Competitors**: We are directly competing with enterprise metadata platforms like OpenMetadata and DataHub. You must always consider these competitors and strive to improve our documentation, UX, and web docs to maintain a competitive edge.
- **Architecture**: The project follows a 12-factor application design and strict microservice standards. We use a multi-tenant architecture designed for extreme scale.
- **AI Integration**: The platform supports running AI agent personas and parallel subagents locally. ML logic and prediction algorithms are authored as dynamically loadable 'Agent Skills'.

## 2. General Agent Guidelines

- **Follow Existing Patterns**: Always adhere strictly to the existing codebase patterns, standards, and Dropwizard framework conventions. Do not introduce new paradigms unless explicitly requested.
- **Community & Contribution**: Always follow community guidelines and contribution guidelines.

## 3. Testing and Verification

- **Creating Tests**: AI agents frequently struggle with creating new unit and integration tests. Pay special attention to test coverage. You MUST write robust unit and integration tests (using JUnit/Mockito for Java) for every new feature or bug fix.
- **Load Testing**: Always run a full comprehensive load test for all `GET` and `POST` endpoints to ensure performance at scale.
- **Azure Environment Baseline**: Use the user-provided Azure environment to do pre- and post-test comparisons, or capture a performance baseline and compare your changes against that baseline.
- **Jacoco Reports**: Always run Jacoco reports. You must attach the test results, performance test results, and Jacoco reports to the release documents in the published `docs`.

## 4. Database Migrations (Flyway)

- Database migrations use Flyway.
- **NEVER modify existing/previous migration files.**
- All database changes require a new migration script with a subsequent version number.

## 5. Versioning and Breaking Changes

- **Backward Compatibility**: When making massive changes, always move to a new version (e.g., V2) without breaking the existing version (V1). APIs must remain backward-compatible.

## 6. Security & Vulnerabilities

- **Vulnerabilities**: Keep all vulnerabilities fixed. Regularly audit dependencies and ensure no new vulnerabilities are introduced during your tasks. Prioritize security in all your implementations.
