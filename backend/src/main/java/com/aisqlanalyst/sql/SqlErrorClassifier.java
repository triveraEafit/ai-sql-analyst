package com.aisqlanalyst.sql;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Classifies execution failures by their PostgreSQL {@code SQLState} so the Query_Service
 * (task 10) can decide whether to retry once, map the failure to HTTP 504, or fail with HTTP 422
 * (design &quot;Correctable-error classification (Change 2)&quot;; Requirements 5.1, 5.2, 5.4).
 *
 * <p>This is a pure, side-effect-free helper. It does not catch anything itself and does not change
 * {@link SqlExecutor} behavior; the service wraps {@code execute()} and passes the caught throwable
 * here. Classification rules:
 *
 * <ul>
 *   <li><strong>Correctable (retryable).</strong> {@code SQLState} is in <em>class 42</em> (starts
 *       with {@code "42"}) <em>except</em> {@code "42501"} (insufficient_privilege). The class-42
 *       family covers syntax errors and name-resolution problems the LLM can plausibly fix on a
 *       second attempt: 42601 syntax_error, 42703 undefined_column, 42803 grouping_error, 42883
 *       undefined_function, 42804 datatype_mismatch, 42702 ambiguous_column.</li>
 *   <li><strong>Insufficient privilege.</strong> {@code "42501"} is in class 42 but is
 *       <em>never</em> correctable: it signals an attempt to touch something the Read_Only_Role is
 *       denied, so retrying would be pointless and would mask a safety signal. Mapped as a failure
 *       (HTTP 422) by task 10.</li>
 *   <li><strong>Statement timeout.</strong> {@code "57014"} (query_canceled / statement_timeout)
 *       is never correctable and never retried; task 10/12 maps it to HTTP 504.</li>
 *   <li>Any other state (for example {@code "23505"} unique_violation) is neither correctable nor a
 *       timeout; task 10 maps it to HTTP 422.</li>
 * </ul>
 *
 * <h2>Why we read the SQLState from the ROOT {@link SQLException}, not the Spring wrapper</h2>
 *
 * <p>Spring's {@code JdbcTemplate} translates a driver {@link SQLException} into a
 * {@code DataAccessException} subclass. That wrapper's own {@code getSQLState()} (where present) is
 * derived through Spring's error-code translation and is <strong>unreliable</strong> for this
 * decision &mdash; the exact 5-character PostgreSQL state we must branch on lives on the original
 * driver exception (a {@code PSQLException}, a subclass of {@link SQLException}) buried in the cause
 * chain. We therefore unwrap to the <em>deepest</em> {@link SQLException} reachable through the
 * cause chain and read {@link SQLException#getSQLState()} from <em>that</em> exception. Reading the
 * state from the Spring wrapper would risk a wrong retry/timeout decision on a safety-critical path.
 *
 * <p>&quot;Root&quot; here means the last/deepest {@link SQLException} found while walking
 * {@link Throwable#getCause()} to the end. PostgreSQL's {@code PSQLException} primarily chains via
 * {@code getCause()}; we additionally follow {@link SQLException#getNextException()} from any
 * {@code SQLException} we encounter so a state carried on a {@code getNextException()}-linked driver
 * exception is still found. The walk is cycle-guarded (self-referential or circular causes cannot
 * loop forever).
 */
@Component
public class SqlErrorClassifier {

    /** SQLState for insufficient_privilege: in class 42 but never correctable. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** SQLState for query_canceled / statement_timeout: maps to HTTP 504, never retried. */
    private static final String STATEMENT_TIMEOUT = "57014";

    /** SQLState class prefix for the syntax-error / access-rule-violation family. */
    private static final String CLASS_42 = "42";

    /**
     * Unwraps {@code t} to the deepest {@link SQLException} in its exception chain and returns that
     * exception's {@code SQLState}, or {@code null} if the chain contains no {@link SQLException}
     * (or the deepest one reports no state).
     *
     * <p>We deliberately return the <em>deepest</em> {@link SQLException}'s state rather than the
     * first one found: when a Spring wrapper or an intermediate {@link SQLException} sits above the
     * real driver exception, the authoritative 5-character PostgreSQL state is on the inner-most
     * driver exception. The walk follows {@link Throwable#getCause()} and, for every
     * {@link SQLException} seen, also {@link SQLException#getNextException()}; it is guarded against
     * cycles so a self-referential cause cannot spin forever.
     *
     * @param t the caught throwable (typically a Spring {@code DataAccessException}); may be
     *          {@code null}
     * @return the deepest {@link SQLException}'s {@code SQLState}, or {@code null} if none is present
     */
    public String rootSqlState(Throwable t) {
        SQLException deepest = deepestSqlException(t);
        return deepest == null ? null : deepest.getSQLState();
    }

    /**
     * Returns {@code true} iff the root {@code SQLState} is in class 42 and is not {@code 42501}.
     * These are the errors the LLM may be able to correct on a single retry (Requirement 5.1).
     *
     * @param t the caught throwable; may be {@code null}
     * @return whether the failure is correctable (retryable)
     */
    public boolean isCorrectable(Throwable t) {
        String state = rootSqlState(t);
        return state != null && state.startsWith(CLASS_42) && !INSUFFICIENT_PRIVILEGE.equals(state);
    }

    /**
     * Returns {@code true} iff the root {@code SQLState} equals {@code 57014}
     * (query_canceled / statement_timeout). Mapped to HTTP 504 with no retry (Requirement 5.2).
     *
     * @param t the caught throwable; may be {@code null}
     * @return whether the failure is a statement timeout
     */
    public boolean isStatementTimeout(Throwable t) {
        return STATEMENT_TIMEOUT.equals(rootSqlState(t));
    }

    /**
     * Returns {@code true} iff the root {@code SQLState} equals {@code 42501}
     * (insufficient_privilege). Exposed for clarity so task 10 can distinguish &quot;denied by the
     * Read_Only_Role&quot; from other non-correctable failures; it is never retried (Requirement 5.4).
     *
     * @param t the caught throwable; may be {@code null}
     * @return whether the failure is an insufficient-privilege error
     */
    public boolean isInsufficientPrivilege(Throwable t) {
        return INSUFFICIENT_PRIVILEGE.equals(rootSqlState(t));
    }

    /**
     * Walks the exception chain and returns the deepest {@link SQLException} reachable, or
     * {@code null} if there is none. Traversal follows {@link Throwable#getCause()} and, for every
     * {@link SQLException} encountered, its {@link SQLException#getNextException()} chain. An
     * identity-based visited set guards against self-referential or circular causes.
     *
     * <p>&quot;Deepest&quot; is tracked by a monotonically increasing depth counter: a cause is one
     * step deeper than its wrapper, and each {@code getNextException()} link is one step deeper than
     * the {@link SQLException} that produced it. When two {@link SQLException}s are equally deep the
     * later-visited one wins, which keeps the driver exception (visited after any wrapper above it)
     * as the chosen root.
     */
    private static SQLException deepestSqlException(Throwable t) {
        // Identity map as a visited set: distinct exceptions that happen to be .equals() are still
        // separate nodes, and a cause pointing back at an ancestor cannot loop.
        Map<Throwable, Boolean> visited = new IdentityHashMap<>();
        SQLException deepest = null;
        int deepestDepth = -1;

        Throwable current = t;
        int depth = 0;
        while (current != null && visited.put(current, Boolean.TRUE) == null) {
            if (current instanceof SQLException sqlEx) {
                if (depth >= deepestDepth) {
                    deepest = sqlEx;
                    deepestDepth = depth;
                }
                // Follow the getNextException() chain; each link counts as one step deeper so a
                // state on a nextException-linked driver exception beats a shallower wrapper.
                SQLException next = sqlEx.getNextException();
                int nextDepth = depth + 1;
                while (next != null && visited.put(next, Boolean.TRUE) == null) {
                    if (nextDepth >= deepestDepth) {
                        deepest = next;
                        deepestDepth = nextDepth;
                    }
                    next = next.getNextException();
                    nextDepth++;
                }
            }
            current = current.getCause();
            depth++;
        }
        return deepest;
    }
}
