package com.aisqlanalyst.service;

/**
 * Thrown by the Query_Service (task 10) when execution hits the Statement_Timeout &mdash; a
 * {@code SQLException} whose root {@code SQLState} is {@code 57014} (query_canceled /
 * statement_timeout). This failure is never retried (design Error Handling table; Requirement 5.2).
 *
 * <p>This is an unchecked exception so the orchestration path need not declare it; the global
 * Controller_Advice (task 12.2) maps it to <strong>HTTP 504</strong> using {@link #getMessage()} as
 * the Error_Advice body.
 *
 * <p><strong>Safety:</strong> the message is a short, generic category string. It MUST NOT carry
 * secrets, stack traces, the offending SQL, or raw driver detail. Any original
 * {@code SQLException}/{@code DataAccessException} may be attached as the cause for server logs only
 * and is never surfaced to clients.
 *
 * <p>Lives in {@code com.aisqlanalyst.service} alongside {@link QueryFailedException} because the
 * Query_Service orchestrates execution and is the component that throws it; it is defined here now
 * so tasks 7.3, 10, and 12 can reference it. The timeout is detected by
 * {@link com.aisqlanalyst.sql.SqlErrorClassifier#isStatementTimeout(Throwable)}.
 */
public class StatementTimeoutException extends RuntimeException {

    public StatementTimeoutException(String message) {
        super(message);
    }

    public StatementTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
