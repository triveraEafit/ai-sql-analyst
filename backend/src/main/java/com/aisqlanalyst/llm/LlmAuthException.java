package com.aisqlanalyst.llm;

/**
 * Raised when the LLM provider rejects the request with an authentication/authorization error,
 * i.e. an HTTP 401 Unauthorized or 403 Forbidden response from the provider (Requirement 8.4).
 *
 * <p><strong>Intended HTTP mapping (task 12.2):</strong> HTTP <strong>502 Bad Gateway</strong>.
 *
 * <p>The message is a short, generic, client-safe string; see {@link LlmException} for the safety
 * contract. In particular it MUST NOT echo the API key or any {@code Authorization} header value.
 */
public class LlmAuthException extends LlmException {

    public LlmAuthException(String message) {
        super(message);
    }

    public LlmAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
