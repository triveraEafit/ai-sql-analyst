package com.aisqlanalyst.llm;

/**
 * Translates a natural-language Question into candidate SQL (Llm_Client, Requirement 1.4).
 *
 * <p>A single interface with a real HTTP implementation ({@code HttpLlmClient}, the default) and an
 * optional {@code MockLlmClient} (selected via {@code LLM_PROVIDER=mock}); both are wired in later
 * tasks (8.2 / 8.3). Every implementation includes the {@link SchemaContext} description of the
 * Allowlisted_Tables in its generation request so the model only ever sees the four queryable
 * tables (Requirement 1.4).
 *
 * <h2>Generation vs. retry</h2>
 * <p>Two call shapes model the single-retry orchestration (design "POST /api/query Sequence"):
 * <ul>
 *   <li>{@link #generateSql(String, String)} is the <strong>initial</strong> generation for a
 *       Question.</li>
 *   <li>{@link #generateSql(String, String, String)} is the <strong>single retry</strong> path: it
 *       takes an {@code errorHint} describing why the previous attempt failed (e.g. a correctable
 *       SQLState class-42 error from the Sql_Executor) so the model can produce a corrected
 *       statement. The orchestration retries at most once.</li>
 * </ul>
 *
 * <p>The 2-argument form is provided as a {@code default} method that delegates to the 3-argument
 * form with a {@code null} {@code errorHint}, so implementations only need to handle a single
 * method and interpret a {@code null} hint as "initial generation, no prior failure".
 */
public interface LlmClient {

    /**
     * Generates candidate SQL for a Question (initial, no prior failure).
     *
     * @param question      the user's natural-language Question.
     * @param schemaContext the Schema_Context text describing the Allowlisted_Tables and columns
     *                      (see {@link SchemaContext#asPromptText()}).
     * @return the generated {@link LlmResult} ({@code sql} + {@code explanation}).
     */
    default LlmResult generateSql(String question, String schemaContext) {
        return generateSql(question, schemaContext, null);
    }

    /**
     * Generates candidate SQL for a Question, optionally correcting a prior failure (retry path).
     *
     * @param question      the user's natural-language Question.
     * @param schemaContext the Schema_Context text describing the Allowlisted_Tables and columns.
     * @param errorHint     a short description of why the previous attempt failed, or {@code null}
     *                      for the initial generation. Implementations should use a non-null hint
     *                      to guide a corrected statement.
     * @return the generated {@link LlmResult} ({@code sql} + {@code explanation}).
     */
    LlmResult generateSql(String question, String schemaContext, String errorHint);
}
