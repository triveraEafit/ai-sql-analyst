package com.aisqlanalyst.sql;

/**
 * Thrown by the {@link SqlValidator} when generated SQL fails any safety rule
 * (Requirement 4.1&ndash;4.8).
 *
 * <p>This is an unchecked exception so the orchestration path need not declare it; the global
 * Controller_Advice (task 12.2) maps it to <strong>HTTP 422</strong> using {@link #getMessage()} as
 * the Error_Advice body (Requirement 4.8).
 *
 * <p><strong>Safety:</strong> the message is a short, generic category string (for example "Query
 * references a table that is not permitted."). It MUST NOT carry secrets, stack traces, or raw
 * internal details. The validator may include a rejected table or function <em>name</em> &mdash;
 * those are not secrets &mdash; but never the full offending SQL, which could echo literal values a
 * caller supplied. The original parser exception, when present, is attached as the cause for server
 * logs only and is never surfaced to clients.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
