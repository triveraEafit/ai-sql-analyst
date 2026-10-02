package com.aisqlanalyst.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plain JUnit 5 example tests for the {@link SqlValidator} (Requirement 14.1 &mdash; "JUnit example
 * tests for Sql_Validator, 5 cases"). This is a pure unit test: it instantiates
 * {@code new SqlValidator()} directly with no Spring context, no database and no Docker, so it runs
 * fast. Assertions use AssertJ, matching the existing test style.
 *
 * <p>The design's "Testing Strategy / Sql_Validator &mdash; 5 cases" prescribes exactly these five
 * logical cases, each mapped to the acceptance criteria it covers:
 * <ol>
 *   <li><b>Valid single SELECT over allowlisted tables &rarr; ACCEPTED</b> (happy path): a realistic
 *       {@code customers}&times;{@code orders} join with the allowlisted {@code count(*)} aggregate
 *       and a WHERE returns a non-null {@link ValidatedSql} with non-empty normalized SQL and no
 *       exception.</li>
 *   <li><b>Multi-statement input &rarr; REJECTED</b> &mdash; Requirement 4.1.</li>
 *   <li><b>Non-SELECT statement &rarr; REJECTED</b> &mdash; Requirement 4.2.</li>
 *   <li><b>Non-allowlisted table (incl. {@code pg_catalog} / {@code information_schema}) &rarr;
 *       REJECTED</b> &mdash; Requirement 4.3.</li>
 *   <li><b>Non-allowlisted / Dangerous_Function &rarr; REJECTED</b> &mdash; Requirements 4.6, 4.7.</li>
 * </ol>
 */
class SqlValidatorTest {

    private final SqlValidator validator = new SqlValidator();

    // --- Case 1: valid single SELECT over allowlisted tables -> ACCEPTED (happy path) -----------

    @Test
    void case1_validSelectOverAllowlistedTables_isAccepted() {
        String sql = "SELECT c.id, count(*) AS order_count "
                + "FROM customers c "
                + "JOIN orders o ON o.customer_id = c.id "
                + "WHERE c.id > 0 "
                + "GROUP BY c.id";

        ValidatedSql[] result = new ValidatedSql[1];
        assertThatCode(() -> result[0] = validator.validate(sql)).doesNotThrowAnyException();

        assertThat(result[0]).isNotNull();
        assertThat(result[0].statement()).isNotNull();
        assertThat(result[0].normalizedSql()).isNotBlank();
    }

    // --- Case 2: multi-statement input -> REJECTED (Requirement 4.1) ----------------------------

    @Test
    void case2_multiStatementInput_isRejected() {
        String sql = "SELECT * FROM customers; DROP TABLE customers";

        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(ValidationException.class);
    }

    // --- Case 3: non-SELECT statement -> REJECTED (Requirement 4.2) -----------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM customers",
            "DROP TABLE customers",
            "INSERT INTO customers (id) VALUES (1)",
            "UPDATE customers SET id = 2 WHERE id = 1"
    })
    void case3_nonSelectStatement_isRejected(String sql) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(ValidationException.class);
    }

    // --- Case 4: non-allowlisted table -> REJECTED (Requirement 4.3) ----------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT * FROM interactions",
            "SELECT * FROM pg_catalog.pg_tables",
            "SELECT * FROM information_schema.tables"
    })
    void case4_nonAllowlistedTable_isRejected(String sql) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(ValidationException.class);
    }

    // --- Case 5: non-allowlisted / dangerous function -> REJECTED (Requirements 4.6, 4.7) -------

    @ParameterizedTest
    @ValueSource(strings = {
            "SELECT pg_sleep(1)",
            "SELECT * FROM customers WHERE id = pg_sleep(1)",
            "SELECT pg_read_file('/etc/passwd')"
    })
    void case5_nonAllowlistedFunction_isRejected(String sql) {
        assertThatThrownBy(() -> validator.validate(sql))
                .isInstanceOf(ValidationException.class);
    }
}
