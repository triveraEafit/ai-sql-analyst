package com.aisqlanalyst.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.aisqlanalyst.sql.SqlValidator;

/**
 * Plain JUnit 5 unit tests for {@link MockLlmClient} (task 8.2). No Spring context, database or
 * Docker is needed: the mock has no dependencies and the real {@link SqlValidator} is a pure
 * component, so both are constructed directly. Assertions use AssertJ to match the existing style.
 *
 * <p>The key guarantee this verifies is end-to-end usability of mock mode: for every frontend
 * example question (and the fallback), the SQL the mock returns must pass the real
 * {@link SqlValidator} unchanged, so the frontend example buttons work against the seeded database
 * without a live LLM. Each answer's {@code sql} and {@code explanation} must also be non-blank.
 */
class MockLlmClientTest {

    private final MockLlmClient mock = new MockLlmClient();
    private final SqlValidator validator = new SqlValidator();
    private final String schema = new SchemaContext().asPromptText();

    /** The frontend's example questions plus a couple of unrecognised prompts for the fallback. */
    @ParameterizedTest
    @ValueSource(strings = {
            "Who are the top customers by spending?",
            "Show me revenue by month",
            "What are the best-selling products?",
            "Break down orders by status",
            "How many customers by country?",
            "something totally unrelated",
            ""
    })
    void everyMockAnswerIsValidPerSqlValidator(String question) {
        LlmResult result = mock.generateSql(question, schema);

        assertThat(result).isNotNull();
        assertThat(result.sql()).isNotBlank();
        assertThat(result.explanation()).isNotBlank();

        // The must-have: the returned SQL passes the real Sql_Validator unchanged.
        assertThatCode(() -> validator.validate(result.sql())).doesNotThrowAnyException();
    }

    @Test
    void revenueByMonthUsesDateTrunc() {
        LlmResult result = mock.generateSql("Show me revenue by month", schema);
        assertThat(result.sql().toLowerCase()).contains("date_trunc");
    }

    @Test
    void topCustomersQuerySumsSpend() {
        LlmResult result = mock.generateSql("top customers by spending", schema);
        assertThat(result.sql().toLowerCase()).contains("sum").contains("customers");
    }

    @Test
    void retryPathReturnsSameValidAnswerAsInitial() {
        LlmResult initial = mock.generateSql("revenue by month", schema);
        LlmResult retry = mock.generateSql("revenue by month", schema, "previous SQL failed");

        assertThat(retry.sql()).isEqualTo(initial.sql());
        assertThatCode(() -> validator.validate(retry.sql())).doesNotThrowAnyException();
    }
}
