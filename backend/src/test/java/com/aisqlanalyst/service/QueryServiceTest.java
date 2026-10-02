package com.aisqlanalyst.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;

import com.aisqlanalyst.dto.QueryResponse;
import com.aisqlanalyst.llm.LlmClient;
import com.aisqlanalyst.llm.LlmParseException;
import com.aisqlanalyst.llm.LlmResult;
import com.aisqlanalyst.llm.SchemaContext;
import com.aisqlanalyst.persistence.Interaction;
import com.aisqlanalyst.persistence.InteractionRepository;
import com.aisqlanalyst.sql.SqlErrorClassifier;
import com.aisqlanalyst.sql.SqlExecutor;
import com.aisqlanalyst.sql.SqlValidator;
import com.aisqlanalyst.sql.ValidationException;

/**
 * Example-based unit tests for {@link QueryService} (task 10.2; Requirement 14.2, with the
 * individual branches covering Requirements 1.1, 4.8, 5.1&ndash;5.4, 6.1, 8.1).
 *
 * <p>These are plain JUnit 5 tests with a <strong>mocked {@link LlmClient}</strong> and a mocked
 * {@link SqlExecutor} / {@link InteractionRepository}. There is <em>no</em> Spring context and no
 * database/Docker: the service is constructed directly in {@link #setUp()} so each case is fast and
 * deterministic. The {@link QuestionLengthValidator}, {@link SchemaContext}, {@link SqlValidator}
 * and {@link SqlErrorClassifier} are the real (pure, dependency-free) implementations so validation
 * and SQLState classification behave authentically; only the collaborators that would otherwise
 * reach out (LLM provider, database, audit store) are mocked.
 *
 * <h2>How the mocked {@link LlmClient} is stubbed</h2>
 * <p>{@code QueryService} calls the <em>2-arg</em> {@code generateSql(question, schemaContext)} for
 * the initial generation and the <em>3-arg</em> {@code generateSql(question, schemaContext, hint)}
 * for the single retry. The 2-arg form is a Java {@code default} method that normally delegates to
 * the 3-arg form, but a Mockito mock <strong>replaces</strong> that default (an unstubbed 2-arg call
 * returns {@code null}). Therefore the initial attempt is stubbed on the <strong>2-arg</strong>
 * overload and the retry on the <strong>3-arg</strong> overload, independently, per test. "No retry
 * happened" is asserted with {@code verify(llmClient, never()).generateSql(.., .., anyString())}
 * against the 3-arg overload; "retried once" with {@code times(1)} on the same overload.
 *
 * <h2>Synthesizing execution failures</h2>
 * <p>{@link SqlExecutor} is mocked; its {@code execute(..)} is stubbed to return rows or to throw a
 * Spring {@code DataAccessException} wrapping a {@code java.sql.SQLException} carrying a specific
 * {@code SQLState}, which drives the real {@link SqlErrorClassifier}: {@code 42703} =&gt;
 * correctable (retry), {@code 57014} =&gt; statement timeout (504, no retry).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QueryServiceTest {

    /** Large max so the length guard never trips here (over-length is covered by its own test). */
    private static final int MAX_QUESTION_LENGTH = 300;

    private static final String QUESTION = "How many customers are there?";

    /** A real, allowlisted SELECT the real SqlValidator accepts. */
    private static final String VALID_SQL = "SELECT count(*) AS n FROM customers";
    private static final String VALID_SQL_CORRECTED = "SELECT count(*) AS total FROM customers";
    private static final String EXPLANATION = "Counts all customer rows.";
    private static final String EXPLANATION_CORRECTED = "Counts all customer rows (corrected).";

    @Mock
    private LlmClient llmClient;

    @Mock
    private SqlExecutor sqlExecutor;

    @Mock
    private InteractionRepository interactionRepository;

    // Real, pure collaborators so validation + classification behave authentically.
    private final QuestionLengthValidator questionLengthValidator =
            new QuestionLengthValidator(MAX_QUESTION_LENGTH);
    private final SchemaContext schemaContext = new SchemaContext();
    private final SqlValidator sqlValidator = new SqlValidator();
    private final SqlErrorClassifier sqlErrorClassifier = new SqlErrorClassifier();

    private QueryService queryService;

    @BeforeEach
    void setUp() {
        queryService = new QueryService(
                questionLengthValidator,
                llmClient,
                schemaContext,
                sqlValidator,
                sqlExecutor,
                sqlErrorClassifier,
                interactionRepository);
    }

    /**
     * Case 1 (Req 1.1, 6.1) &mdash; SUCCESS: generate -&gt; validate -&gt; execute returns rows, the
     * service returns a {@link QueryResponse} carrying the table/sql/explanation, and a SUCCESS
     * {@link Interaction} is persisted with the generated SQL.
     */
    @Test
    void success_generatesValidatesExecutes_returnsResponse_andPersistsSuccess() {
        // Initial generation uses the 2-arg overload.
        when(llmClient.generateSql(anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL, EXPLANATION));
        List<Map<String, Object>> rows = List.of(Map.of("n", 250L));
        when(sqlExecutor.execute(any())).thenReturn(rows);

        QueryResponse response = queryService.handle(QUESTION);

        assertThat(response.table()).isEqualTo(rows);
        assertThat(response.sql()).isEqualTo(VALID_SQL);
        assertThat(response.explanation()).isEqualTo(EXPLANATION);

        // No retry on success.
        verify(llmClient, never()).generateSql(anyString(), anyString(), anyString());
        verify(sqlExecutor, times(1)).execute(any());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("SUCCESS");
        assertThat(saved.generatedSql()).isEqualTo(VALID_SQL);
        assertThat(saved.explanation()).isEqualTo(EXPLANATION);
    }

    /**
     * Case 2 (Req 4.8, 5.3) &mdash; Validator rejection: the mock LLM returns a non-SELECT
     * statement, the real validator throws {@link ValidationException}, which propagates with NO
     * retry (3-arg overload never called), the executor is never reached, and a FAILED interaction
     * is persisted.
     */
    @Test
    void validatorRejection_throwsValidationException_noRetry_persistsFailed() {
        when(llmClient.generateSql(anyString(), anyString()))
                .thenReturn(new LlmResult("DROP TABLE customers", EXPLANATION));

        assertThatThrownBy(() -> queryService.handle(QUESTION))
                .isInstanceOf(ValidationException.class);

        // Rejected before execution; no retry, no execute.
        verify(llmClient, never()).generateSql(anyString(), anyString(), anyString());
        verify(sqlExecutor, never()).execute(any());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("FAILED");
    }

    /**
     * Case 3 (Req 5.1) &mdash; Correctable error then retry SUCCESS: the first execute throws a
     * class-42 (42703, undefined_column) error, the service regenerates once (3-arg overload), the
     * second execute returns rows, and the service returns a {@link QueryResponse}. The executor is
     * called twice, the retry overload exactly once, and the SUCCESS interaction records the
     * corrected SQL.
     */
    @Test
    void correctableError_thenRetrySucceeds_returnsResponse_withCorrectedSql() {
        when(llmClient.generateSql(anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL, EXPLANATION));
        when(llmClient.generateSql(anyString(), anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL_CORRECTED, EXPLANATION_CORRECTED));

        List<Map<String, Object>> rows = List.of(Map.of("total", 250L));
        when(sqlExecutor.execute(any()))
                .thenThrow(correctable())
                .thenReturn(rows);

        QueryResponse response = queryService.handle(QUESTION);

        assertThat(response.table()).isEqualTo(rows);
        assertThat(response.sql()).isEqualTo(VALID_SQL_CORRECTED);
        assertThat(response.explanation()).isEqualTo(EXPLANATION_CORRECTED);

        verify(llmClient, times(1)).generateSql(anyString(), anyString(), anyString());
        verify(sqlExecutor, times(2)).execute(any());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("SUCCESS");
        assertThat(saved.generatedSql()).isEqualTo(VALID_SQL_CORRECTED);
    }

    /**
     * Case 4 (Req 5.4) &mdash; Retry fails: the first execute throws a correctable error, the retry
     * execute throws again, and the service maps it to {@link QueryFailedException}. The executor is
     * called twice (initial + single retry) and a FAILED interaction is persisted.
     */
    @Test
    void retryFails_throwsQueryFailedException_persistsFailed() {
        when(llmClient.generateSql(anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL, EXPLANATION));
        when(llmClient.generateSql(anyString(), anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL_CORRECTED, EXPLANATION_CORRECTED));

        when(sqlExecutor.execute(any()))
                .thenThrow(correctable())
                .thenThrow(correctable());

        assertThatThrownBy(() -> queryService.handle(QUESTION))
                .isInstanceOf(QueryFailedException.class);

        verify(llmClient, times(1)).generateSql(anyString(), anyString(), anyString());
        verify(sqlExecutor, times(2)).execute(any());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("FAILED");
    }

    /**
     * Case 5 (Req 5.2) &mdash; Statement timeout: the first execute throws a 57014 timeout; the
     * service maps it to {@link StatementTimeoutException} with NO retry (3-arg overload never
     * called, executor called once), and a FAILED interaction is persisted.
     */
    @Test
    void statementTimeout_throwsStatementTimeoutException_noRetry_persistsFailed() {
        when(llmClient.generateSql(anyString(), anyString()))
                .thenReturn(new LlmResult(VALID_SQL, EXPLANATION));
        when(sqlExecutor.execute(any())).thenThrow(timeout());

        assertThatThrownBy(() -> queryService.handle(QUESTION))
                .isInstanceOf(StatementTimeoutException.class);

        verify(llmClient, never()).generateSql(anyString(), anyString(), anyString());
        verify(sqlExecutor, times(1)).execute(any());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("FAILED");
    }

    /**
     * Case 6 (Req 8.1, 6.2) &mdash; LLM failure: the initial 2-arg generation throws an
     * {@link LlmParseException}; it propagates (as an {@code LlmException}), the executor is never
     * called, and a FAILED interaction is persisted with {@code generated_sql == null} (no SQL was
     * ever produced).
     */
    @Test
    void llmFailure_propagates_executorNeverCalled_persistsFailedWithNullSql() {
        when(llmClient.generateSql(anyString(), anyString()))
                .thenThrow(new LlmParseException("The model returned something we cannot use."));

        assertThatThrownBy(() -> queryService.handle(QUESTION))
                .isInstanceOf(LlmParseException.class);

        verify(sqlExecutor, never()).execute(any());
        verify(llmClient, never()).generateSql(anyString(), anyString(), anyString());

        Interaction saved = captureSavedInteraction();
        assertThat(saved.status()).isEqualTo("FAILED");
        assertThat(saved.generatedSql()).isNull();
    }

    // --- helpers -----------------------------------------------------------------------------

    /** Captures the single Interaction passed to repository.save(..) and returns it. */
    private Interaction captureSavedInteraction() {
        ArgumentCaptor<Interaction> captor = ArgumentCaptor.forClass(Interaction.class);
        verify(interactionRepository).save(captor.capture());
        return captor.getValue();
    }

    /** A correctable (class-42, 42703 undefined_column) execution error, as Spring would wrap it. */
    private static BadSqlGrammarException correctable() {
        return new BadSqlGrammarException(
                "executing query", "SELECT 1", new SQLException("undefined_column", "42703"));
    }

    /** A statement-timeout (57014) execution error, as Spring would wrap it. */
    private static UncategorizedSQLException timeout() {
        return new UncategorizedSQLException(
                "executing query", "SELECT 1", new SQLException("canceled", "57014"));
    }
}
