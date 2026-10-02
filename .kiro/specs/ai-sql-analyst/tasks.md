# Implementation Plan: AI SQL Analyst

## Overview

This plan converts the approved minimal-stack design into a series of incremental, test-driven coding tasks. Work starts with repository setup and backend scaffolding and configuration, moves through database init, datasources, DTOs, the safety-critical Sql_Validator and Sql_Executor, the LLM client, persistence, orchestration, and the web/filter layer, then the Next.js frontend, and finishes with operational tooling (Dockerfiles, compose, CI, docs) and syncing the spec documents into `docs/`. Each task builds on previous ones and ends by wiring new code into the running system so no code is left orphaned.

Backend is Spring Boot (Java) with spring-boot-starter-web, -validation, -jdbc, JSqlParser, and HikariCP. Frontend is Next.js (App Router, TypeScript) with Tailwind. All automated coverage is provided by JUnit 5 example tests and integration tests against a real PostgreSQL; there are no property-based tests in this plan.

## Tasks

- [x] 0. Repository setup
  - [x] 0.1 Create a repository `.gitignore` covering the backend (e.g. `target/`, `build/`), the frontend (e.g. `node_modules/`, `.next/`), and local env files (e.g. `.env`). _Requirements: 15.5_

- [x] 1. Scaffold Spring Boot backend project and configuration binding
  - [x] 1.1 Create backend project skeleton and build file (deps: web, validation, jdbc, PostgreSQL driver, JSqlParser; tests JUnit 5); main @SpringBootApplication + application.yml with app.* props and two jdbc-url datasource prefixes. _Requirements: 12.1, 12.2_
  - [x] 1.2 Implement @Validated @ConfigurationProperties for all tunables (max-question-length=300, enforced-limit=100, statement-timeout=5s, llm.timeout, rate-limit.requests-per-minute=30, rate-limit.trust-proxy=off, cors.frontend-origin, llm.provider). _Requirements: 10.2,10.3,10.4,10.5,10.6,9.2_
  - [x] 1.3 Fail-fast startup validation of required env vars (DB_URL, DB_RW_USER, DB_RW_PASSWORD, DB_RO_USER, DB_RO_PASSWORD, FRONTEND_ORIGIN always; LLM_API_KEY/LLM_BASE_URL/LLM_MODEL required only when LLM_PROVIDER != mock); name the missing variable. _Requirements: 11.1, 10.1_

- [x] 2. Create database initialization script and schema
  - [x] 2.1 Write schema and seed SQL files (customers, products, orders, order_items; interactions with generated_sql nullable, status, latency_ms, created_at); seed >=2000 rows. _Requirements: 2.1,2.2,2.3_
  - [x] 2.2 Write 01-init.sh wrapper reading RO_DB_PASSWORD; run schema/seed via psql; create read_only_role; GRANT SELECT only on allowlisted tables; withhold all privileges on interactions. _Requirements: 2.4,2.5_

- [ ] 3. Configure dual Hikari datasources and transaction managers
  - [x] 3.1 Define RW and RO DataSource beans (jdbc-url) + a tx manager each, naming the read-only one readOnlyTxManager. _Requirements: 3.1,3.2_
  - [ ] 3.2 Integration test for datasource separation. _Requirements: 3.1,3.2,3.3,3.4_

- [ ] 4. Define DTOs and request-shape validation
  - [ ] 4.1 Create QueryRequest (@NotBlank, no @Size), QueryResponse(table,sql,explanation), InteractionSummary(generatedSql,status,latencyMs,createdAt), ErrorResponse. _Requirements: 12.3,1.1,7.1_
  - [ ] 4.2 Manual Max_Question_Length check + QuestionTooLongException -> 400. _Requirements: 1.2,1.3,10.2_

- [ ] 5. Implement the Sql_Validator (JSqlParser AST)
  - [ ] 5.1 Core validation rules (single statement, SELECT-only, schema-qualified allowlisted tables, reject SELECT INTO, reject locking clauses, default-deny function allowlist, SetOperationList/UNION traversal). _Requirements: 4.1-4.8_
  - [ ] 5.2 JUnit example tests for Sql_Validator (5 cases). _Requirements: 14.1,4.1,4.2,4.3,4.6,4.7_

- [ ] 6. Checkpoint - validator tests green
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 7. Implement the Sql_Executor (read-only, bounded)
  - [ ] 7.1 Bounded read-only execution (@Transactional readOnly, transactionManager="readOnlyTxManager"; SET LOCAL statement_timeout; run parsed statement.toString() wrapped in LIMIT subquery; JDBC maxRows). _Requirements: 4.9,3.2,10.3,10.4_
  - [ ] 7.2 SQLState error classification (class 42 except 42501 correctable; 57014 timeout). Unwrap Spring's DataAccessException to the root java.sql.SQLException and read the SQLState from that root exception, never from the Spring wrapper. _Requirements: 5.1,5.2,5.4_
  - [ ] 7.3 Integration tests for executor bounds and classification. _Requirements: 4.9,10.3,10.4,5.2_

- [ ] 8. Implement the Llm_Client and Schema_Context
  - [ ] 8.1 Define LlmClient interface + LlmResult; build Schema_Context (allowlisted tables + columns) at startup from the SAME schema definition used by the seed/init (single source of truth) so the context and the actual database schema cannot drift. _Requirements: 1.4_
  - [ ] 8.2 Implement optional MockLlmClient via LLM_PROVIDER=mock. The mock MUST return valid, parseable SQL answers for the 4-5 example questions so the frontend example questions work end-to-end in mock mode. _Requirements: 1.4_
  - [ ] 8.3 Implement HttpLlmClient (Llm_Timeout; parse {sql,explanation}; failure mapping 502/503); HttpLlmClient is the default when LLM_PROVIDER != mock. _Requirements: 8.1-8.7_
  - [ ]* 8.4 Unit tests for HttpLlmClient failure mapping. _Requirements: 8.2-8.7_

- [ ] 9. Implement interaction persistence and history
  - [ ] 9.1 InteractionRepository (JdbcTemplate, RW datasource): save + findRecent(50) newest-first; generated_sql nullable. _Requirements: 6.1-6.4,7.1,7.2,3.1_
  - [ ]* 9.2 Integration tests for persistence and history. _Requirements: 6.1,6.2,7.1,7.2_

- [ ] 10. Implement the Query_Service orchestration
  - [ ] 10.1 generate -> validate -> execute -> single retry on correctable error -> persist every outcome. When the single retry also fails, persist a FAILED interaction and throw QueryFailedException (-> 422). _Requirements: 1.1,5.1,5.2,5.3,5.4,4.8,6.1,6.2,6.4_
  - [ ] 10.2 JUnit example tests for Query_Service (6 cases, mocked Llm_Client). _Requirements: 14.2,1.1,4.8,5.1,5.2,5.3,5.4,6.1,8.1_

- [ ] 11. Checkpoint - service and validator tests green
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 12. Implement the web layer (controller, advice, filters)
  - [ ] 12.1 Query_Controller (POST /api/query, GET /api/history). _Requirements: 1.1,7.1,12.1_
  - [ ] 12.2 Global Controller_Advice mapping (400/422/504/502/503, safe bodies). _Requirements: 11.2,11.3,12.4,1.2,1.3,4.8,5.2,5.4,8.2-8.7_
  - [ ] 12.3 CorsFilter at HIGHEST_PRECEDENCE for Frontend_Origin. _Requirements: 10.6_
  - [ ] 12.4 Per-IP RateLimitFilter (in-memory fixed window) -> 429, below CORS precedence; trust-proxy aware. _Requirements: 9.1,9.2_
  - [ ]* 12.5 Unit tests for RateLimitFilter and advice safety. _Requirements: 9.1,9.2,11.2,11.3_

- [ ] 13. Checkpoint - backend wired end-to-end
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Scaffold the Next.js frontend
  - [ ] 14.1 Next.js App Router + Tailwind + API client (NEXT_PUBLIC_API_URL); responsive layout. _Requirements: 13.10_
  - [ ] 14.2 QuestionForm client-side validation (NEXT_PUBLIC_MAX_QUESTION_LENGTH) + char counter. _Requirements: 13.1,13.3,13.4_
  - [ ] 14.3 ExampleQuestions (4-5 clickable, populate+submit) + wire submit to POST /api/query. _Requirements: 13.1,13.2,13.6_
  - [ ] 14.4 LoadingIndicator + disabled submit. _Requirements: 13.5_
  - [ ] 14.5 ResultsPanel (table, explanation, collapsible SQL collapsed by default) + ErrorBanner. _Requirements: 13.6,13.7,13.8_
  - [ ] 14.6 RecentQuestions from GET /api/history. _Requirements: 13.9_

- [ ] 15. Checkpoint - frontend integrated with backend
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 16. Operational tooling and documentation
  - [ ] 16.1 Multi-stage non-root backend Dockerfile. _Requirements: 15.2_
  - [ ] 16.2 Multi-stage non-root frontend Dockerfile (ARG NEXT_PUBLIC_* -> ENV before next build). _Requirements: 15.1_
  - [ ] 16.3 docker-compose (frontend+backend+postgres, one command; frontend build.args; RO_DB_PASSWORD; mount init scripts). _Requirements: 15.3_
  - [ ] 16.4 GitHub Actions CI: build both apps and run tests. The workflow MUST start a PostgreSQL service container and run the init script before tests, because the integration tests (3.2, 7.3, 9.2) require a real PostgreSQL; state this dependency explicitly in the workflow. _Requirements: 15.4,14.3_
  - [ ] 16.5 README + committed .env.example (setup, env vars incl conditional LLM + NEXT_PUBLIC build args, local run, cloud deploy, architecture overview, design choices/challenges, 3 operational notes). _Requirements: 15.5,15.6_

- [ ] 17. Final checkpoint - full build and tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 18. Sync spec documents into docs/
  - [ ] 18.1 Copy/sync requirements.md, design.md, and tasks.md from .kiro/specs/ai-sql-analyst/ into the docs/ directory so the committed docs reflect the current spec. _Requirements: 15.5_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP (8.4, 9.2, 12.5).
- Each task references specific requirements for traceability.
- Checkpoints ensure incremental validation.
- All automated coverage is via JUnit example tests and integration tests against a real PostgreSQL; there are no property-based tests in this plan.
- The read-only safety path (Sql_Validator, Sql_Executor) and the Query_Service orchestration carry non-optional test tasks because they are safety-critical.
- Integration tests (3.2, 7.3, 9.2) and CI (16.4) require a real PostgreSQL instance with the init script applied.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["0.1", "1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.1"] },
    { "id": 2, "tasks": ["2.2", "3.1", "4.1", "4.2"] },
    { "id": 3, "tasks": ["3.2", "5.1", "8.1"] },
    { "id": 4, "tasks": ["5.2", "7.1", "8.2"] },
    { "id": 5, "tasks": ["7.2", "8.3", "9.1"] },
    { "id": 6, "tasks": ["7.3", "8.4", "9.2", "10.1"] },
    { "id": 7, "tasks": ["10.2", "12.1", "12.2", "12.3", "12.4"] },
    { "id": 8, "tasks": ["12.5", "14.1"] },
    { "id": 9, "tasks": ["14.2", "14.3", "14.4", "14.5", "14.6"] },
    { "id": 10, "tasks": ["16.1", "16.2", "16.3", "16.4", "16.5"] },
    { "id": 11, "tasks": ["18.1"] }
  ]
}
```
