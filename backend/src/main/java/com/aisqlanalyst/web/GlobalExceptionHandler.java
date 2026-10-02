package com.aisqlanalyst.web;

import com.aisqlanalyst.dto.ErrorResponse;
import com.aisqlanalyst.llm.LlmAuthException;
import com.aisqlanalyst.llm.LlmException;
import com.aisqlanalyst.llm.LlmParseException;
import com.aisqlanalyst.llm.LlmRateLimitException;
import com.aisqlanalyst.llm.LlmTimeoutException;
import com.aisqlanalyst.service.QueryFailedException;
import com.aisqlanalyst.service.QuestionTooLongException;
import com.aisqlanalyst.service.StatementTimeoutException;
import com.aisqlanalyst.sql.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global Controller_Advice that maps application exceptions to safe HTTP responses
 * (Requirements 11.2, 11.3, 12.4, 1.2, 1.3, 4.8, 5.2, 5.4, 8.2&ndash;8.7).
 *
 * <p>Every handler returns an {@link ErrorResponse} carrying only a short, stable machine code and
 * a human-safe message. Bodies NEVER include stack traces, SQL, SQLState, provider payloads, API
 * keys, or {@code exception.toString()} of internal errors (Requirements 11.2, 11.3). For the
 * typed application exceptions, whose {@code getMessage()} was deliberately designed to be
 * generic and client-safe, that message is surfaced as-is; for everything else a fixed safe string
 * is used.
 *
 * <h2>Exception &rarr; HTTP status mapping</h2>
 * <ul>
 *   <li>{@link QuestionTooLongException} &rarr; 400 (Requirement 1.2)</li>
 *   <li>{@link MethodArgumentNotValidException} (blank question) &rarr; 400 (Requirement 1.3)</li>
 *   <li>{@link ValidationException} &rarr; 422 (Requirement 4.8)</li>
 *   <li>{@link QueryFailedException} &rarr; 422 (Requirement 5.4)</li>
 *   <li>{@link StatementTimeoutException} &rarr; 504 (Requirement 5.2)</li>
 *   <li>{@link LlmTimeoutException} &rarr; 503 (Requirement 8.2)</li>
 *   <li>{@link LlmRateLimitException} &rarr; 503 (Requirement 8.3)</li>
 *   <li>{@link LlmAuthException} &rarr; 502 (Requirement 8.4)</li>
 *   <li>{@link LlmParseException} &rarr; 502 (Requirements 8.5&ndash;8.7)</li>
 *   <li>{@link LlmException} (base, any other provider failure) &rarr; 502</li>
 *   <li>any other {@link Exception} &rarr; 500 with a generic body</li>
 * </ul>
 *
 * <p>The LLM subtypes each have their own handler and the base {@link LlmException} has a separate
 * handler; Spring dispatches to the most specific match, so a subtype is handled by its own method
 * and only a bare {@code LlmException} falls through to the base handler.
 *
 * <p><strong>Note:</strong> the 429 rate-limit response is produced by the RateLimitFilter
 * (task 12.4), not by this advice.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** {@link QuestionTooLongException} &rarr; HTTP 400 (Requirement 1.2). */
    @ExceptionHandler(QuestionTooLongException.class)
    public ResponseEntity<ErrorResponse> handleQuestionTooLong(QuestionTooLongException ex) {
        return build(HttpStatus.BAD_REQUEST, "question_too_long", ex.getMessage());
    }

    /** Blank/missing question (bean validation) &rarr; HTTP 400 (Requirement 1.3). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex) {
        // Do not echo field errors verbatim; a fixed safe summary avoids leaking internals.
        return build(HttpStatus.BAD_REQUEST, "bad_request", "Question must not be blank.");
    }

    /** Missing or malformed JSON request body &rarr; HTTP 400 (safe, generic message). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        // Never echo the parser message: it can reveal internal field/structure details.
        return build(HttpStatus.BAD_REQUEST, "invalid_request", "Request body is missing or malformed.");
    }

    /** {@link ValidationException} &rarr; HTTP 422 (Requirement 4.8). */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "invalid_query", ex.getMessage());
    }

    /** {@link QueryFailedException} &rarr; HTTP 422 (Requirement 5.4). */
    @ExceptionHandler(QueryFailedException.class)
    public ResponseEntity<ErrorResponse> handleQueryFailed(QueryFailedException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "query_failed", ex.getMessage());
    }

    /** {@link StatementTimeoutException} &rarr; HTTP 504 (Requirement 5.2). */
    @ExceptionHandler(StatementTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleStatementTimeout(StatementTimeoutException ex) {
        return build(HttpStatus.GATEWAY_TIMEOUT, "statement_timeout", ex.getMessage());
    }

    /** {@link LlmTimeoutException} &rarr; HTTP 503 (Requirement 8.2). */
    @ExceptionHandler(LlmTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleLlmTimeout(LlmTimeoutException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "llm_unavailable", ex.getMessage());
    }

    /** {@link LlmRateLimitException} &rarr; HTTP 503 (Requirement 8.3). */
    @ExceptionHandler(LlmRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleLlmRateLimit(LlmRateLimitException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "llm_unavailable", ex.getMessage());
    }

    /** {@link LlmAuthException} &rarr; HTTP 502 (Requirement 8.4). */
    @ExceptionHandler(LlmAuthException.class)
    public ResponseEntity<ErrorResponse> handleLlmAuth(LlmAuthException ex) {
        return build(HttpStatus.BAD_GATEWAY, "llm_error", ex.getMessage());
    }

    /** {@link LlmParseException} &rarr; HTTP 502 (Requirements 8.5&ndash;8.7). */
    @ExceptionHandler(LlmParseException.class)
    public ResponseEntity<ErrorResponse> handleLlmParse(LlmParseException ex) {
        return build(HttpStatus.BAD_GATEWAY, "llm_error", ex.getMessage());
    }

    /** Base {@link LlmException} (any other provider failure) &rarr; HTTP 502. */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ErrorResponse> handleLlm(LlmException ex) {
        return build(HttpStatus.BAD_GATEWAY, "llm_error", ex.getMessage());
    }

    /**
     * Last-resort handler so unexpected errors never leak internals (Requirements 11.2, 11.3).
     * Returns a fixed generic body with no details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Log server-side for operators; the response body stays generic (no internals leaked).
        log.error("Unhandled exception mapped to HTTP 500", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred.");
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(code, message));
    }
}
