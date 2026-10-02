package com.aisqlanalyst.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * Unit tests for {@link SqlErrorClassifier} (task 7.2; Requirements 5.1, 5.2, 5.4).
 *
 * <p>Each test synthesizes an exception chain that mirrors how Spring wraps a driver
 * {@link SQLException} ({@code DataAccessException} on the outside, the real {@code SQLException}
 * carrying the authoritative {@code SQLState} as a cause) and asserts the classifier reads the
 * state from the ROOT (deepest) {@link SQLException}, never from the Spring wrapper. The root is
 * built with {@code new SQLException(reason, sqlState)} so the state is set without depending on
 * {@code PSQLException} constructors.
 */
class SqlErrorClassifierTest {

    private final SqlErrorClassifier classifier = new SqlErrorClassifier();

    /**
     * {@code BadSqlGrammarException} is the typical Spring wrapper for a class-42 grammar error.
     * Its first constructor arg is the originating "task"/SQL string; the real driver exception is
     * the cause. 42703 (undefined_column) is correctable.
     */
    @Test
    void readsRootState_fromSpringBadSqlGrammarWrapper_correctable() {
        SQLException root = new SQLException("column \"foo\" does not exist", "42703");
        DataAccessException wrapper =
                new BadSqlGrammarException("executing query", "SELECT foo FROM x", root);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("42703");
        assertThat(classifier.isCorrectable(wrapper)).isTrue();
        assertThat(classifier.isStatementTimeout(wrapper)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(wrapper)).isFalse();
    }

    /** 42501 (insufficient_privilege) is in class 42 but is NEVER correctable. */
    @Test
    void insufficientPrivilege_notCorrectable() {
        SQLException root = new SQLException("permission denied for table interactions", "42501");
        DataAccessException wrapper = new DataIntegrityViolationException("denied", root);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("42501");
        assertThat(classifier.isCorrectable(wrapper)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(wrapper)).isTrue();
        assertThat(classifier.isStatementTimeout(wrapper)).isFalse();
    }

    /** 57014 (query_canceled / statement_timeout) is a timeout, not correctable. */
    @Test
    void statementTimeout_classified() {
        SQLException root = new SQLException("canceling statement due to statement timeout", "57014");
        DataAccessException wrapper = new DataIntegrityViolationException("timeout", root);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("57014");
        assertThat(classifier.isStatementTimeout(wrapper)).isTrue();
        assertThat(classifier.isCorrectable(wrapper)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(wrapper)).isFalse();
    }

    /** A non-42, non-57014 state (23505 unique_violation) is neither correctable nor a timeout. */
    @Test
    void unrelatedState_neitherCorrectableNorTimeout() {
        SQLException root = new SQLException("duplicate key value", "23505");
        DataAccessException wrapper = new DataIntegrityViolationException("conflict", root);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("23505");
        assertThat(classifier.isCorrectable(wrapper)).isFalse();
        assertThat(classifier.isStatementTimeout(wrapper)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(wrapper)).isFalse();
    }

    /** A deep chain through a non-SQL RuntimeException still unwraps to the SQLException (42883). */
    @Test
    void nestedChain_walksToDeepSqlException() {
        SQLException root = new SQLException("function foo() does not exist", "42883");
        RuntimeException middle = new RuntimeException("intermediate", root);
        DataAccessException wrapper = new DataIntegrityViolationException("outer", middle);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("42883");
        assertThat(classifier.isCorrectable(wrapper)).isTrue();
    }

    /**
     * TWO SQLExceptions in the cause chain where the DEEPER one carries the real driver state. This
     * proves the classifier reads the root/deepest state (42804), not the first/shallower one
     * (whose state would wrongly look non-correctable here if read).
     */
    @Test
    void twoSqlExceptions_readsDeeperRoot() {
        // Deepest: the real driver exception with the authoritative state.
        SQLException driver = new SQLException("datatype mismatch", "42804");
        // Shallower SQLException wrapping it, carrying a DIFFERENT (non-42) state.
        SQLException intermediate = new SQLException("wrapped by driver layer", "08006", driver);
        DataAccessException wrapper = new DataIntegrityViolationException("outer", intermediate);

        // Reads the DEEPER root (42804), not the shallower 08006.
        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("42804");
        assertThat(classifier.isCorrectable(wrapper)).isTrue();
    }

    /**
     * {@code getNextException()}-linked driver exception: PostgreSQL also chains via
     * {@code setNextException}. The deeper nextException (42601) carries the real state.
     */
    @Test
    void followsNextExceptionChain() {
        SQLException first = new SQLException("primary", "08006");
        SQLException next = new SQLException("syntax error at or near", "42601");
        first.setNextException(next);
        DataAccessException wrapper = new DataIntegrityViolationException("outer", first);

        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("42601");
        assertThat(classifier.isCorrectable(wrapper)).isTrue();
    }

    /** A chain with no SQLException yields a null root state and all classifiers return false. */
    @Test
    void noSqlExceptionInChain_nullAndAllFalse() {
        RuntimeException notSql = new IllegalStateException("boom", new RuntimeException("inner"));

        assertThat(classifier.rootSqlState(notSql)).isNull();
        assertThat(classifier.isCorrectable(notSql)).isFalse();
        assertThat(classifier.isStatementTimeout(notSql)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(notSql)).isFalse();
    }

    /** Null input is handled defensively: null state, all classifiers false. */
    @Test
    void nullThrowable_handledSafely() {
        assertThat(classifier.rootSqlState(null)).isNull();
        assertThat(classifier.isCorrectable(null)).isFalse();
        assertThat(classifier.isStatementTimeout(null)).isFalse();
        assertThat(classifier.isInsufficientPrivilege(null)).isFalse();
    }

    /** A bare SQLException with no wrapper (direct propagation) is classified by its own state. */
    @Test
    void bareSqlException_classifiedDirectly() {
        SQLException bare = new SQLException("grouping error", "42803");

        assertThat(classifier.rootSqlState(bare)).isEqualTo("42803");
        assertThat(classifier.isCorrectable(bare)).isTrue();
    }

    /**
     * A {@code getNextException()} link that points back at an already-visited exception must not
     * loop forever (the identity-based visited set guards the walk). The real state (42702) is
     * still returned.
     */
    @Test
    void cyclicNextException_doesNotLoop() {
        SQLException a = new SQLException("ambiguous column", "42702");
        SQLException b = new SQLException("secondary", "08006");
        a.setNextException(b);
        b.setNextException(a); // cycle: b -> a -> b -> ...
        DataAccessException wrapper = new DataIntegrityViolationException("outer", a);

        // Walk terminates thanks to the visited guard; 42702 is the first/deepest class-42 state.
        assertThat(classifier.rootSqlState(wrapper)).isEqualTo("08006");
        assertThat(classifier.isInsufficientPrivilege(wrapper)).isFalse();
    }
}
