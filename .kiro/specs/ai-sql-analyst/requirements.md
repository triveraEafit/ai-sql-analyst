# Requirements Document

## Introduction

AI SQL Analyst is a web application that lets a user ask a plain-English question about a sample e-commerce PostgreSQL database and receive a verified answer consisting of a results table, the generated SQL statement, and a short explanation. The system translates natural language to SQL using a generative AI model, validates the generated SQL for safety, executes it through a read-only database role, and records each interaction for later review.

The application is split into a Next.js frontend and a Spring Boot backend that communicate over a REST API. Safety and correctness are central: only single read-only SELECT statements are executed, credentials and API keys are supplied through environment variables, and execution is bounded by an enforced row limit and a statement timeout.

This document defines the requirements for the core question-to-answer flow, interaction history, backend safety controls, backend structure, configuration, frontend experience, and the operational tooling planned as a final increment (containerization, CI, and documentation). Authentication, conversation memory, charting, and retrieval-augmented generation are explicitly out of scope.

## Glossary

- **System**: The complete AI SQL Analyst application, comprising the Frontend and the Backend.
- **Frontend**: The Next.js web application that presents the user interface and calls the Backend REST API.
- **Backend**: The Spring Boot application that exposes the REST API and orchestrates question processing, validation, execution, and persistence.
- **Query_Controller**: The Backend REST component that handles the `POST /api/query` and `GET /api/history` endpoints.
- **Query_Service**: The Backend component that orchestrates the question-to-answer flow, including LLM invocation, validation, execution, retry, and persistence.
- **Llm_Client**: The Backend component that calls the external generative AI provider to translate a Question into SQL.
- **Sql_Validator**: The Backend component that inspects generated SQL and accepts or rejects it against safety rules.
- **Sql_Executor**: The Backend component that executes validated SQL against the Read_Only_Datasource.
- **Controller_Advice**: The Backend global `@ControllerAdvice` component that provides centralized error handling and maps failures to HTTP status codes and Error_Advice messages.
- **Request_DTO**: The Backend data transfer object that models an incoming request payload for the REST API.
- **Response_DTO**: The Backend data transfer object that models an outgoing response payload for the REST API.
- **Schema_Context**: The description of the Allowlisted_Tables and their columns supplied to the Llm_Client to guide SQL generation.
- **Database**: The PostgreSQL database containing the sample e-commerce data and the Interactions_Table.
- **Read_Only_Role**: A PostgreSQL role granted `SELECT` only on the Allowlisted_Tables and no access to the Interactions_Table.
- **Read_Write_Datasource**: The Backend database connection, with read and write privileges, used exclusively for reading and writing the Interactions_Table.
- **Read_Only_Datasource**: The Backend database connection that authenticates as the Read_Only_Role and is used exclusively for executing generated SQL.
- **Allowlisted_Tables**: The set of tables permitted in generated SQL: `customers`, `products`, `orders`, and `order_items`.
- **Allowlisted_Functions**: The configured set of SQL functions permitted in generated SQL. The Sql_Validator accepts a function only when the function is a member of the Allowlisted_Functions set and rejects any function that is not a member.
- **Enforced_Limit**: The maximum number of result rows returned from executing generated SQL. Default 100 rows, configurable.
- **Statement_Timeout**: The maximum wall-clock duration permitted for executing generated SQL. Default 5 seconds, configurable.
- **Llm_Timeout**: The maximum wall-clock duration permitted for a single Llm_Client call to the AI provider. Configurable.
- **Interaction**: A single recorded question-to-answer exchange, including the Question, the generated SQL (nullable), the result summary, the explanation, a status field, a latency field, and a timestamp.
- **Interactions_Table**: The Database table that stores Interaction records.
- **Status_Field**: The field of an Interaction record that records the outcome of a question-to-answer flow, distinguishing a successful outcome from a failed outcome.
- **Latency_Field**: The field of an Interaction record that records the elapsed wall-clock duration of a question-to-answer flow in milliseconds.
- **Question**: The plain-English text submitted by the user.
- **Max_Question_Length**: The maximum permitted length of a Question in characters. Default 300 characters, configurable.
- **Example_Question**: A predefined sample Question shown in the Frontend to help a user get started.
- **Llm_Response**: The raw response returned by the AI provider, from which the generated SQL and explanation are derived.
- **Error_Advice**: A user-facing message that describes a failure and, where useful, suggests a corrective action, without leaking internal details.
- **Frontend_Origin**: The web origin of the Frontend, permitted by the Backend CORS configuration.
- **Init_Script**: The database initialization script that creates the e-commerce schema, creates the Interactions_Table, seeds sample data, and provisions the Read_Only_Role.
- **Dangerous_Function**: A SQL function that is not a member of the Allowlisted_Functions set and that the Sql_Validator rejects (for example `pg_sleep`, `pg_read_file`).
- **Rate_Limit**: The configured maximum request rate permitted per client IP address on `POST /api/query`.
- **Required_Environment_Variable**: An environment variable that the Backend requires to be present at startup, including database credentials and the AI provider API key.
- **Compose_Configuration**: The docker-compose configuration that starts the Frontend, the Backend, and PostgreSQL together with a single command.

## Requirements

### Requirement 1: Ask a Question and Receive a Verified Answer

**User Story:** As a business user, I want to ask a plain-English question and receive a verified answer, so that I can explore the database without writing SQL.

#### Acceptance Criteria

1. WHEN a user submits a Question of length up to Max_Question_Length characters through `POST /api/query`, THE Query_Service SHALL translate the Question into a single SELECT statement using the Llm_Client, validate the statement with the Sql_Validator, execute the statement with the Sql_Executor, and return a results table, the generated SQL, and a short explanation.
2. IF a submitted Question exceeds Max_Question_Length characters, THEN THE Query_Controller SHALL reject the request with HTTP 400 and an Error_Advice message that states the maximum allowed length.
3. IF a submitted Question is empty or contains only whitespace, THEN THE Query_Controller SHALL reject the request with HTTP 400 and an Error_Advice message.
4. THE Llm_Client SHALL include the Schema_Context describing the Allowlisted_Tables and their columns when requesting SQL generation.

### Requirement 2: Database Initialization and Sample Data

**User Story:** As an operator, I want a consistent, safely provisioned database with sample data, so that the application starts from a known, secure state.

#### Acceptance Criteria

1. THE Init_Script SHALL create the e-commerce schema comprising the tables `customers`, `products`, `orders`, and `order_items`.
2. THE Init_Script SHALL create the Interactions_Table.
3. THE Init_Script SHALL seed at least two thousand rows of sample data across the Allowlisted_Tables.
4. THE Init_Script SHALL create the Read_Only_Role and grant the Read_Only_Role `SELECT` privileges only on the Allowlisted_Tables.
5. THE Init_Script SHALL withhold from the Read_Only_Role every privilege on the Interactions_Table.

### Requirement 3: Separate Read-Write and Read-Only Datasources

**User Story:** As a security-conscious engineer, I want generated SQL to run through a read-only datasource that cannot touch the Interactions_Table, so that untrusted SQL cannot read or modify sensitive data.

#### Acceptance Criteria

1. THE Backend SHALL use the Read_Write_Datasource exclusively for reading and writing the Interactions_Table.
2. THE Backend SHALL use the Read_Only_Datasource, authenticated as the Read_Only_Role, exclusively for executing generated SQL through the Sql_Executor.
3. WHEN generated SQL executed through the Read_Only_Datasource references the Interactions_Table, THE Database SHALL deny access to the Interactions_Table.
4. THE Read_Only_Datasource SHALL be unable to modify any table in the Database.

### Requirement 4: SQL Safety Validation

**User Story:** As a security-conscious engineer, I want generated SQL validated against strict safety rules before execution, so that only safe single read-only SELECT statements run against the database.

#### Acceptance Criteria

1. IF the generated SQL contains more than one statement, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
2. IF the generated SQL is not a SELECT statement, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
3. IF the generated SQL references a table that is not in the Allowlisted_Tables, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
4. IF the generated SQL contains a `SELECT INTO` clause, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
5. IF the generated SQL contains a locking clause such as `FOR UPDATE`, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
6. THE Sql_Validator SHALL accept a function in the generated SQL only when the function is a member of the Allowlisted_Functions set.
7. IF the generated SQL contains a function that is not a member of the Allowlisted_Functions set, including a Dangerous_Function such as `pg_sleep` or `pg_read_file`, THEN THE Sql_Validator SHALL reject the SQL with an Error_Advice message.
8. WHEN the Sql_Validator rejects generated SQL, THE Backend SHALL return HTTP 422 with the Sql_Validator Error_Advice message.
9. WHEN the Sql_Validator accepts generated SQL, THE Sql_Executor SHALL execute the SQL inside a read-only transaction bounded by the Enforced_Limit and the Statement_Timeout.

### Requirement 5: Automatic Retry on Correctable Database Errors

**User Story:** As a business user, I want the system to automatically retry when the generated SQL has a correctable error, so that I get a useful answer without resubmitting my question.

#### Acceptance Criteria

1. IF executing the generated SQL fails with a SQL syntax error or an unknown or invalid column error, THEN THE Query_Service SHALL request one corrected SELECT statement from the Llm_Client and re-validate and re-execute the corrected SQL.
2. IF executing the generated SQL fails because of a Statement_Timeout, THEN THE Query_Service SHALL NOT retry and THE Backend SHALL return HTTP 504 with an Error_Advice message.
3. IF the Sql_Validator rejects the generated SQL, THEN THE Query_Service SHALL NOT retry and THE Backend SHALL return HTTP 422 with the Sql_Validator Error_Advice message.
4. WHEN a retry attempt also fails, THE Backend SHALL return HTTP 422 with an Error_Advice message describing the failure.

### Requirement 6: Interaction Persistence

**User Story:** As a business user, I want each question-to-answer exchange recorded, so that interactions can be reviewed later.

#### Acceptance Criteria

1. WHEN a question-to-answer flow completes with a successful outcome, THE Query_Service SHALL persist an Interaction record to the Interactions_Table through the Read_Write_Datasource, including the Question, the generated SQL, a result summary, an explanation, a Status_Field that records success, a Latency_Field, and a timestamp.
2. WHEN a question-to-answer flow completes with a failed outcome, THE Query_Service SHALL persist an Interaction record to the Interactions_Table through the Read_Write_Datasource, including the Question, the generated SQL where available, a result summary, an explanation where available, a Status_Field that records the failure, a Latency_Field, and a timestamp.
3. THE generated SQL field of an Interaction record SHALL be nullable and SHALL be null when no SQL was generated.
4. WHEN a flow fails before SQL generation, THE Query_Service SHALL persist an Interaction record with a null generated SQL field, a Status_Field that records the failure, and an Error_Advice-based result summary.

### Requirement 7: Interaction History Retrieval

**User Story:** As a business user, I want to retrieve my recent questions and answers, so that I can revisit past interactions.

#### Acceptance Criteria

1. WHEN a client calls `GET /api/history`, THE Query_Controller SHALL return the most recent 50 Interaction records ordered from newest to oldest.
2. THE Query_Controller SHALL read Interaction records for `GET /api/history` through the Read_Write_Datasource.

### Requirement 8: LLM Provider Failure Handling

**User Story:** As a business user, I want clear, safe error messages when the AI provider fails, so that I understand what went wrong without exposing internal details.

#### Acceptance Criteria

1. THE Llm_Client SHALL apply the configurable Llm_Timeout to each call to the AI provider.
2. IF the Llm_Client call fails because of an Llm_Timeout, THEN THE Backend SHALL return HTTP 503 with an Error_Advice message that omits internal details.
3. IF the AI provider returns a rate-limit error, THEN THE Backend SHALL return HTTP 503 with an Error_Advice message that omits internal details.
4. IF the AI provider returns an authentication error, THEN THE Backend SHALL return HTTP 502 with an Error_Advice message that omits internal details.
5. IF the Llm_Response is not valid JSON, THEN THE Backend SHALL return HTTP 502 with an Error_Advice message that omits internal details.
6. IF the Llm_Response is missing the sql field, THEN THE Backend SHALL return HTTP 502 with an Error_Advice message that omits internal details.
7. IF the Llm_Response is missing the explanation field, THEN THE Backend SHALL return HTTP 502 with an Error_Advice message that omits internal details.

### Requirement 9: Per-IP Rate Limiting

**User Story:** As an operator, I want per-IP rate limiting on the query endpoint, so that the service is protected from abuse and excessive load.

#### Acceptance Criteria

1. IF a client IP address exceeds the configured Rate_Limit on `POST /api/query`, THEN THE Backend SHALL return HTTP 429 with a descriptive Error_Advice message.
2. THE Rate_Limit SHALL be configurable.

### Requirement 10: Configuration

**User Story:** As an operator, I want key behaviors and secrets configured through environment variables and settings, so that I can deploy and tune the system without code changes.

#### Acceptance Criteria

1. THE Backend SHALL read database credentials and the AI provider API key from environment variables.
2. THE Backend SHALL default Max_Question_Length to 300 characters and SHALL allow Max_Question_Length to be configured.
3. THE Backend SHALL default the Enforced_Limit to 100 rows and SHALL allow the Enforced_Limit to be configured.
4. THE Backend SHALL default the Statement_Timeout to 5 seconds and SHALL allow the Statement_Timeout to be configured.
5. THE Backend SHALL allow the Llm_Timeout to be configured.
6. THE Backend SHALL permit requests from the configured Frontend_Origin through its CORS configuration.

### Requirement 11: Startup Validation and Response Safety

**User Story:** As an operator, I want the backend to validate required configuration at startup and keep secrets out of responses, so that misconfiguration fails fast and sensitive details are never leaked.

#### Acceptance Criteria

1. IF a Required_Environment_Variable is absent at startup, THEN THE Backend SHALL fail startup and SHALL log a message that names the missing Required_Environment_Variable.
2. THE Backend SHALL exclude secrets from every response.
3. THE Backend SHALL exclude stack traces from every response.

### Requirement 12: Backend Structure

**User Story:** As a developer, I want the backend organized into clear layers and components, so that the codebase is maintainable and easy to extend.

#### Acceptance Criteria

1. THE Backend SHALL separate a controller layer and a service layer.
2. THE Backend SHALL implement the Sql_Validator, the Sql_Executor, and the Llm_Client as separate components.
3. THE Backend SHALL define a Request_DTO and a Response_DTO for the REST API.
4. THE Backend SHALL implement a global Controller_Advice component for centralized error handling.

### Requirement 13: Frontend Experience

**User Story:** As a business user, I want an intuitive, responsive interface to ask questions and view results, so that I can get answers easily on any device.

#### Acceptance Criteria

1. THE Frontend SHALL present an input for a Question, a submit control, and between four and five clickable Example_Question entries.
2. WHEN a user clicks an Example_Question entry, THE Frontend SHALL populate the Question input with the Example_Question text and SHALL submit the Question.
3. THE Frontend SHALL block submission of a Question that is empty or contains only whitespace through client-side validation.
4. THE Frontend SHALL block submission of a Question that exceeds Max_Question_Length characters through client-side validation.
5. WHILE a request is in progress, THE Frontend SHALL display a loading indicator and SHALL prevent an additional submission until the in-progress request completes.
6. WHEN a user submits a Question, THE Frontend SHALL display the returned results table, the generated SQL, and the explanation.
7. THE Frontend SHALL display the generated SQL in a collapsible section that is collapsed by default.
8. IF the Backend returns an error response, THEN THE Frontend SHALL display the Error_Advice message.
9. THE Frontend SHALL display a "Recent questions" list populated from `GET /api/history`.
10. THE Frontend SHALL present a layout that is responsive across mobile and desktop viewports.

### Requirement 14: Automated Testing

**User Story:** As an engineer, I want automated tests for the critical safety and orchestration components, so that regressions are caught before release.

#### Acceptance Criteria

1. THE System SHALL include unit tests for the Sql_Validator covering a valid single SELECT, a multi-statement input, a non-SELECT statement, a non-allowlisted table, and a function that is not a member of the Allowlisted_Functions set.
2. THE System SHALL include unit tests for the Query_Service that use a mocked Llm_Client.
3. WHEN the GitHub Actions CI workflow runs, THE System SHALL execute the Sql_Validator and Query_Service unit tests.

### Requirement 15: Operational Tooling and Documentation

**User Story:** As an operator, I want containerization, CI, and clear documentation, so that I can build, deploy, and run the system reliably.

#### Acceptance Criteria

1. THE System SHALL provide a multi-stage Dockerfile for the Frontend that runs the Frontend as a non-root user.
2. THE System SHALL provide a multi-stage Dockerfile for the Backend that runs the Backend as a non-root user.
3. THE System SHALL provide a Compose_Configuration that starts the Frontend, the Backend, and PostgreSQL with a single command.
4. THE System SHALL provide a GitHub Actions CI workflow that builds the System and runs the automated tests.
5. THE README SHALL include setup steps, the required environment variables, local run instructions, cloud deployment steps, an architecture overview, and a description of design choices and challenges.
6. THE System SHALL include a committed `.env.example` file that lists the required environment variables without real secret values.
