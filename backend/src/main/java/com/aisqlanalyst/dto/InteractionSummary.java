package com.aisqlanalyst.dto;

import java.time.Instant;

/**
 * One row of the interaction history returned by {@code GET /api/history} (Requirement 7.1).
 *
 * <p>Mirrors the columns selected by the history query (Change 10): newest-first, capped at 50.
 * {@code generatedSql} (renamed from {@code sql}) is nullable, matching the nullable
 * {@code generated_sql} column — it is {@code null} when no SQL was generated for the interaction.
 *
 * @param generatedSql the SQL generated for this interaction, or {@code null} if none was produced.
 * @param id           the interaction's primary key.
 * @param question     the original natural-language question.
 * @param resultSummary a short summary of the result, or {@code null}.
 * @param explanation  the natural-language explanation, or {@code null}.
 * @param status       the outcome Status_Field: {@code SUCCESS} or {@code FAILED}.
 * @param latencyMs    end-to-end latency in milliseconds.
 * @param createdAt    when the interaction was recorded.
 */
public record InteractionSummary(

        long id,

        String question,

        String generatedSql,

        String resultSummary,

        String explanation,

        String status,

        long latencyMs,

        Instant createdAt
) {
}
