package com.aisqlanalyst.llm;

/**
 * Raised when a call to the LLM provider exceeds the configured Llm_Timeout (connect or read
 * timeout), typically surfacing as a {@code ResourceAccessException} wrapping a
 * {@code SocketTimeoutException} (Requirement 8.2).
 *
 * <p><strong>Intended HTTP mapping (task 12.2):</strong> HTTP <strong>503 Service Unavailable</strong>.
 *
 * <p>The message is a short, generic, client-safe string; see {@link LlmException} for the safety
 * contract (no key, URL, body, or stack trace).
 */
public class LlmTimeoutException extends LlmException {

    public LlmTimeoutException(String message) {
        super(message);
    }

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
