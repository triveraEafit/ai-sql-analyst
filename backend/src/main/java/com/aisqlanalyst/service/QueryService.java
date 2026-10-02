package com.aisqlanalyst.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aisqlanalyst.config.AppProperties;
import com.aisqlanalyst.dto.QueryResponse;
import com.aisqlanalyst.llm.LlmClient;
import com.aisqlanalyst.llm.LlmException;
import com.aisqlanalyst.llm.LlmResult;
import com.aisqlanalyst.llm.SchemaContext;
import com.aisqlanalyst.persistence.Interaction;
import com.aisqlanalyst.persistence.InteractionRepository;
import com.aisqlanalyst.sql.SqlErrorClassifier;
import com.aisqlanalyst.sql.SqlExecutor;
import com.aisqlanalyst.sql.SqlValidator;
import com.aisqlanalyst.sql.ValidatedSql;
import com.aisqlanalyst.sql.ValidationException;

/**
 * Orchestrates the full question-to-answer flow (Query_Service, task 10.1; design "Query_Service"
 * pseudocode and Error Handling table). This is the safety-critical coordinator that ties together
 * LLM generation, AST validation, bounded read-only execution, the single correctable-error retry,
 * latency bookkeeping and persistence of <em>every</em> outcome.
 *
 * <h2>Flow (matches the design pseudocode)</h2>
 * <ol>
 *   <li>Start a latency timer.</li>
 *   <li>{@code enforceMaxLength(question)} first. A {@link QuestionTooLongException} propagates to
 *       HTTP 400 and is <strong>not</strong> persisted &mdash; it is a client input error caught
 *       before any work begins (empty/whitespace {@code @NotBlank} is handled at the controller, and
 *       the 429 rate-limit is handled in the filter; neither reaches here). (Req 1.2, 10.2)</li>
 *   <li>Generate SQL, validate it, execute it. On a <em>correctable</em> execution error (SQLState
 *       class 42 except 42501) perform exactly <strong>one</strong> retry: regenerate with an error
 *       hint, re-validate, re-execute. On a statement timeout (57014) map to HTTP 504 with no retry.
 *       On any other execution error map to HTTP 422 with no retry. (Req 5.1&ndash;5.4)</li>
 *   <li>On success persist a {@code SUCCESS} interaction and return the {@link QueryResponse}.</li>
 *   <li>On any typed failure (validation reject, timeout, retry/non-correctable DB failure, LLM
 *       failure) persist a {@code FAILED} interaction, then rethrow so the Controller_Advice
 *       (task 12.2) maps the status. (Req 4.8, 6.1, 6.2, 6.4)</li>
 * </ol>
 *
 * <h2>Persistence on every outcome (Req 6.1, 6.2, 6.4)</h2>
 * <p>An Interaction is persisted for <strong>every</strong> outcome that reaches this service body
 * &mdash; SUCCESS and all FAILED paths. {@code generated_sql} is {@code null} when no SQL was
 * produced (e.g. the LLM failed before generating). The 400 (over-length) path is deliberately
 * <em>not</em> persisted, and 429 never reaches the service.
 *
 * <h2>Persistence robustness</h2>
 * <p>A failure to write the audit row must never replace or mask the real outcome. All saves go
 * through {@link #persistQuietly(Interaction)}, which logs and swallows any repository error. On the
 * SUCCESS path a lost audit row therefore does not fail the request &mdash; the user already has
 * their answer, so losing the audit row is logged but tolerated. On FAILED paths the quiet save runs
 * and then the original exception is rethrown unchanged.
 *
 * <h2>Retry-failure simplification</h2>
 * <p>If the single retry's execution itself fails &mdash; for any reason, including a timeout, a
 * second correctable error, insufficient privilege, or anything else &mdash; it is <strong>not</strong>
 * retried again and is mapped to {@link QueryFailedException} (HTTP 422), matching the design's
 * "retry fails &rarr; 422" branch. (We deliberately do not promote a retry-time timeout to 504; the
 * initial-attempt timeout is still mapped to 504 as specified.)
 *
 * <h2>Message safety</h2>
 * <p>Exception messages surfaced to clients stay generic (no secrets, no raw SQL, no driver detail).
 * The stored {@code result_summary} on a failure uses the safe exception message. Storing the
 * generated SQL in {@code generated_sql} is intentional &mdash; that column is the audit of what the
 * model produced.
 *
 * <h2>Bean wiring</h2>
 * <p>All collaborators are existing application beans and none is a {@code DataSource} /
 * {@code JdbcTemplate} / transaction manager, so no {@code @Qualifier} is needed. {@link SqlExecutor}
 * is a separate bean, so calling {@code sqlExecutor.execute(...)} goes through its {@code @Transactional}
 * proxy (no self-invocation).
 */
@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";

    /** Generic, safe message for a non-correctable / retry-exhausted database failure (HTTP 422). */
    private static final String MSG_QUERY_FAILED =
            "The query could not be completed. Please try rephrasing your question.";

    /** Generic, safe message for a statement timeout (HTTP 504). */
    private static final String MSG_TIMEOUT =
            "The query took too long to run and was cancelled. Please try a narrower question.";

    private final QuestionLengthValidator questionLengthValidator;
    private final LlmClient llmClient;
    private final SchemaContext schemaContext;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final SqlErrorClassifier sqlErrorClassifier;
    private final InteractionRepository interactionRepository;

    /**
     * Constructor injection of the orchestration collaborators. None is a datasource/transaction
     * bean, so no {@code @Qualifier} is required (unlike the repository/executor, which qualify
     * their own datasource injections internally).
     */
    public QueryService(
            QuestionLengthValidator questionLengthValidator,
            LlmClient llmClient,
            SchemaContext schemaContext,
            SqlValidator sqlValidator,
            SqlExecutor sqlExecutor,
            SqlErrorClassifier sqlErrorClassifier,
            InteractionRepository interactionRepository) {
        this.questionLengthValidator = questionLengthValidator;
        this.llmClient = llmClient;
        this.schemaContext = schemaContext;
        this.sqlValidator = sqlValidator;
        this.sqlExecutor = sqlExecutor;
        this.sqlErrorClassifier = sqlErrorClassifier;
        this.interactionRepository = interactionRepository;
    }

    /**
     * Handles one natural-language question end-to-end.
     *
     * @param question the user's question (already {@code @NotBlank}-validated at the controller).
     * @return the {@link QueryResponse} with result rows, the generated SQL, and the explanation.
     * @throws QuestionTooLongException over the configured max length &rarr; HTTP 400 (not persisted).
     * @throws ValidationException      validator rejected the SQL &rarr; HTTP 422 (persisted FAILED).
     * @throws StatementTimeoutException initial execution timed out &rarr; HTTP 504 (persisted FAILED).
     * @throws QueryFailedException     retry/non-correctable DB failure &rarr; HTTP 422 (persisted FAILED).
     * @throws LlmException             LLM provider/parse failure &rarr; HTTP 502/503 (persisted FAILED).
     */
    public QueryResponse handle(String question) {
        long startNanos = System.nanoTime();

        // Step 2: over-length is a client input error -> HTTP 400 and NOT persisted. Done before the
        // try/persist block so the 400 path never writes an audit row.
        questionLengthValidator.enforceMaxLength(question);

        // Tracked across the flow so a FAILED persist can record whatever SQL (if any) was produced.
        // Stays null if the LLM fails before producing SQL.
        String generatedSql = null;
        String explanation = null;

        try {
            // --- Initial generation (8.x on failure) ---------------------------------------------
            LlmResult initial = llmClient.generateSql(question, schemaContext.asPromptText());
            generatedSql = initial.sql();
            explanation = initial.explanation();

            // --- Validation: ValidationException -> 422, NEVER retried (4.8, 5.3) ----------------
            ValidatedSql validated = sqlValidator.validate(initial.sql());

            // --- Execution, with at most one correctable-error retry (5.1, 5.2, 5.4) -------------
            List<Map<String, Object>> rows;
            try {
                rows = sqlExecutor.execute(validated);
            } catch (RuntimeException executionError) {
                // SqlExecutor lets Spring's DataAccessException (wrapping the root SQLException)
                // propagate; classify it via the root SQLState.
                if (sqlErrorClassifier.isStatementTimeout(executionError)) {
                    // 57014 -> 504, no retry. Persisted FAILED by the catch block below.
                    throw new StatementTimeoutException(MSG_TIMEOUT, executionError);
                }
                if (sqlErrorClassifier.isCorrectable(executionError)) {
                    // Class-42 (except 42501): exactly ONE retry with a safe hint. The retry carries
                    // its own corrected SQL/explanation, which replace the initial attempt's values
                    // so the SUCCESS audit row reflects what actually ran.
                    RetryOutcome retry = retryOnce(question, executionError);
                    rows = retry.rows();
                    generatedSql = retry.sql();
                    explanation = retry.explanation();
                } else {
                    // Non-correctable, non-timeout (incl. 42501 insufficient_privilege): no retry.
                    throw new QueryFailedException(MSG_QUERY_FAILED, executionError);
                }
            }

            // --- Success: persist SUCCESS and return (6.1) ---------------------------------------
            String resultSummary = summarize(rows);
            long latencyMs = elapsedMillis(startNanos);
            // A lost audit row on success must not fail the request: persist quietly, then return.
            persistQuietly(new Interaction(
                    question, generatedSql, resultSummary, explanation, STATUS_SUCCESS, latencyMs));
            return new QueryResponse(rows, generatedSql, explanation);

        } catch (ValidationException ve) {
            // Validator rejection -> 422 (persist the SQL that failed validation, if any) (4.8, 5.3).
            persistFailure(question, generatedSql, safeMessage(ve), startNanos);
            throw ve;
        } catch (StatementTimeoutException te) {
            // Statement timeout -> 504 (6.2).
            persistFailure(question, generatedSql, te.getMessage(), startNanos);
            throw te;
        } catch (QueryFailedException qfe) {
            // Retry failure / non-correctable DB error -> 422 (5.4).
            persistFailure(question, generatedSql, qfe.getMessage(), startNanos);
            throw qfe;
        } catch (LlmException le) {
            // LLM provider/parse failure -> 502/503. generated_sql is typically null here (6.2, 6.4).
            persistFailure(question, generatedSql, safeMessage(le), startNanos);
            throw le;
        }
    }

    /**
     * Performs the single allowed retry on a correctable execution error: regenerate with a safe
     * error hint, re-validate (ValidationException still maps to 422), and re-execute once.
     *
     * <p>If this retry's execution fails for any reason it is <strong>not</strong> retried again and
     * is mapped to {@link QueryFailedException} (HTTP 422), per the "retry fails -> 422" design branch.
     * A re-validation failure propagates as {@link ValidationException} (422) unchanged.
     *
     * @param question       the original question.
     * @param executionError the correctable error from the initial execution (for the hint + cause).
     * @return the retry's rows together with the corrected SQL and explanation.
     */
    private RetryOutcome retryOnce(String question, RuntimeException executionError) {
        String hint = correctionHint(executionError);
        LlmResult retry = llmClient.generateSql(question, schemaContext.asPromptText(), hint);

        // Re-validate the corrected statement; a reject here is still a 422 (no further retry).
        ValidatedSql revalidated = sqlValidator.validate(retry.sql());

        try {
            List<Map<String, Object>> rows = sqlExecutor.execute(revalidated);
            return new RetryOutcome(rows, retry.sql(), retry.explanation());
        } catch (RuntimeException retryError) {
            // SINGLE retry only: any failure of the retry execution -> 422 (no second retry, and we
            // intentionally do not promote a retry-time timeout to 504 to keep this path simple).
            throw new QueryFailedException(MSG_QUERY_FAILED, retryError);
        }
    }

    /**
     * Result of the single retry: the rows produced plus the corrected SQL/explanation that must be
     * recorded on the SUCCESS audit row. A local value holder avoids any shared mutable state on this
     * singleton service.
     */
    private record RetryOutcome(List<Map<String, Object>> rows, String sql, String explanation) {
    }

    /**
     * Builds a short, safe hint for the retry generation. The root {@code SQLState} is a 5-character
     * standard error class (not a secret and not user data), so including it helps the model correct
     * the statement without leaking anything sensitive.
     */
    private String correctionHint(Throwable executionError) {
        String state = sqlErrorClassifier.rootSqlState(executionError);
        String stateText = (state == null || state.isBlank()) ? "unknown" : state;
        return "The previous SQL failed with a database error (SQLState " + stateText
                + "). Produce a corrected read-only SELECT query.";
    }

    /**
     * Persists a FAILED interaction via the quiet helper. {@code generatedSql} may be {@code null}
     * (no SQL produced). The {@code resultSummary} carries the safe failure message; the explanation
     * is {@code null} on failures.
     */
    private void persistFailure(String question, String generatedSql, String resultSummary, long startNanos) {
        persistQuietly(new Interaction(
                question, generatedSql, resultSummary, null, STATUS_FAILED, elapsedMillis(startNanos)));
    }

    /**
     * Saves an interaction, logging and swallowing any repository failure so a persistence problem
     * never masks or replaces the real request outcome (Req 6.x robustness). The original flow
     * continues (SUCCESS path returns; FAILED paths rethrow the original exception).
     */
    private void persistQuietly(Interaction interaction) {
        try {
            interactionRepository.save(interaction);
        } catch (RuntimeException persistError) {
            // Audit write failed; keep the real outcome. Log for operators and move on.
            log.error("Failed to persist interaction (status={}); original outcome preserved.",
                    interaction.status(), persistError);
        }
    }

    /** A short, deterministic row-count summary stored on SUCCESS. */
    private static String summarize(List<Map<String, Object>> rows) {
        int size = rows == null ? 0 : rows.size();
        return size + " row(s)";
    }

    /** Milliseconds elapsed since {@code startNanos}, floored at 0. */
    private static long elapsedMillis(long startNanos) {
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
        return Math.max(0L, elapsed);
    }

    /**
     * Returns an exception's message when present, otherwise a generic fallback, so a null-message
     * exception never produces a null {@code result_summary}.
     */
    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return (message == null || message.isBlank()) ? MSG_QUERY_FAILED : message;
    }
}
