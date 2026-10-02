package com.aisqlanalyst.llm;

/**
 * The outcome of a single LLM generation call (design "Llm_Client").
 *
 * <p>Both fields are the raw text produced by the provider: {@code sql} is the candidate SQL
 * statement (to be handed to the Sql_Validator, never trusted or executed directly) and
 * {@code explanation} is a short natural-language description of what the query does, surfaced to
 * the user alongside the results (Requirement 1.1).
 *
 * @param sql         the candidate SQL statement generated for the Question; validated downstream.
 * @param explanation a short human-readable explanation of the generated query.
 */
public record LlmResult(String sql, String explanation) {
}
