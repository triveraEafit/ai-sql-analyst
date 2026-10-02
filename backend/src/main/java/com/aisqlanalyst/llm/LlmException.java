package com.aisqlanalyst.llm;

/**
 * Base type for all failures raised by the {@code HttpLlmClient} while talking to the external LLM
 * provider or parsing its response (design "Llm_Client" + Error Handling table, Requirements
 * 8.1&ndash;8.7).
 *
 * <p>This is an <strong>unchecked</strong> exception so the orchestration path need not declare it;
 * the global Controller_Advice (task 12.2) maps each concrete subtype to a safe HTTP status.
 *
 * <h2>Safety contract (Requirements 11.2, 11.3)</h2>
 * <p>The {@link #getMessage() message} of this exception and every subclass MUST be a short,
 * generic, client-safe string. It MUST NOT contain the provider API key, the provider base URL, the
 * raw provider response body, or a stack trace. Any underlying cause (an HTTP error, a parse error,
 * a {@code SocketTimeoutException}, etc.) may be attached as the {@link #getCause() cause} for
 * server-side logs only and is never surfaced to clients.
 *
 * <h2>Intended HTTP mapping (performed later in task 12.2)</h2>
 * <p>A bare {@code LlmException} that is not one of the specific subtypes below represents an
 * unexpected non-2xx provider status (not a timeout, rate-limit, auth, or parse error). Task 12.2
 * should treat it as a generic upstream failure &rarr; <strong>HTTP 502 Bad Gateway</strong>.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
