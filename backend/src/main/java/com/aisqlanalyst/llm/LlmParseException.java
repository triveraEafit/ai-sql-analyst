package com.aisqlanalyst.llm;

/**
 * Raised when the LLM provider's response cannot be turned into a usable {@link LlmResult}
 * (Requirements 8.5, 8.6, 8.7). Three distinct conditions all map here because they share the same
 * client-facing meaning &mdash; "the model returned something we cannot use":
 *
 * <ul>
 *   <li>the Llm_Response text is not valid JSON (8.5);</li>
 *   <li>the parsed JSON is missing the {@code sql} field (or it is blank) (8.6);</li>
 *   <li>the parsed JSON is missing the {@code explanation} field (or it is blank) (8.7).</li>
 * </ul>
 *
 * <p><strong>Intended HTTP mapping (task 12.2):</strong> HTTP <strong>502 Bad Gateway</strong>.
 *
 * <p>The message is a short, generic, client-safe string; see {@link LlmException} for the safety
 * contract. In particular it MUST NOT echo the raw provider body (which could contain injected or
 * sensitive content).
 */
public class LlmParseException extends LlmException {

    public LlmParseException(String message) {
        super(message);
    }

    public LlmParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
