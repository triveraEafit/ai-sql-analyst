package com.aisqlanalyst.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/query} (Requirements 12.3, 1.1).
 *
 * <p>Carries the natural-language question the user wants answered against the allowlisted
 * e-commerce schema. {@code @NotBlank} rejects {@code null}, empty, and whitespace-only values
 * so a blank question is mapped to HTTP 400 by the Controller_Advice (task 12.2).
 *
 * <p><strong>Change 6 — why no {@code @Size}:</strong> the maximum question length is the
 * runtime-configurable {@code Max_Question_Length} ({@code app.max-question-length}). Bean
 * Validation {@code @Size(max=...)} requires a compile-time constant and cannot read a value
 * bound at runtime, so the upper-bound check is done manually against the injected limit in task
 * 4.2 (throwing {@code QuestionTooLongException} -> HTTP 400) rather than with an annotation here.
 *
 * @param question the user's natural-language question; must be present and non-blank.
 */
public record QueryRequest(

        @NotBlank
        String question
) {
}
