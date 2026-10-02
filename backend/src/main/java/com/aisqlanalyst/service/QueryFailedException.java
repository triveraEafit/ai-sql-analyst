package com.aisqlanalyst.service;

/**
 * Thrown by the Query_Service (task 10) when a generated query cannot be answered after the single
 * allowed retry, or when it fails with a non-correctable, non-timeout database error (design Error
 * Handling table; Requirement 5.4).
 *
 * <p>This is an unchecked exception so the orchestration path need not declare it; the global
 * Controller_Advice (task 12.2) maps it to <strong>HTTP 422</strong> using {@link #getMessage()} as
 * the Error_Advice body.
 *
 * <p><strong>Safety:</strong> the message is a short, generic category string. It MUST NOT carry
 * secrets, stack traces, the offending SQL, or raw driver detail (which can echo literal values a
 * caller supplied). Any original {@code SQLException}/{@code DataAccessException} may be attached as
 * the cause for server logs only and is never surfaced to clients.
 *
 * <p>Lives in {@code com.aisqlanalyst.service} because the Query_Service orchestrates execution and
 * is the component that throws it (task 10.1); it is defined here now so tasks 7.3, 10, and 12 can
 * reference it. Classification of which failures lead here is done by
 * {@link com.aisqlanalyst.sql.SqlErrorClassifier}.
 */
public class QueryFailedException extends RuntimeException {

    public QueryFailedException(String message) {
        super(message);
    }

    public QueryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
