package com.aisqlanalyst.llm;

/**
 * Raised when the LLM provider reports a rate-limit error, i.e. an HTTP 429 Too Many Requests
 * response from the provider (Requirement 8.3).
 *
 * <p><strong>Intended HTTP mapping (task 12.2):</strong> HTTP <strong>503 Service Unavailable</strong>.
 *
 * <p>The message is a short, generic, client-safe string; see {@link LlmException} for the safety
 * contract (no key, URL, body, or stack trace).
 */
public class LlmRateLimitException extends LlmException {

    public LlmRateLimitException(String message) {
        super(message);
    }

    public LlmRateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
