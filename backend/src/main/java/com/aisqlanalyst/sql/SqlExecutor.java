package com.aisqlanalyst.sql;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aisqlanalyst.config.AppProperties;

/**
 * Executes a validated, read-only {@code SELECT} inside a bounded read-only transaction
 * (Sql_Executor, Requirements 3.2, 4.9, 10.3, 10.4; design Change 3 &amp; 7).
 *
 * <p>Every generated query runs through {@link #execute(ValidatedSql)}, which layers three
 * independent safety bounds on top of the validation already performed by {@link SqlValidator}:
 *
 * <ol>
 *   <li><strong>Read-only transaction on the restricted role (Change 7, Req 3.2).</strong> The
 *       {@link Transactional @Transactional} annotation names
 *       {@code transactionManager = "readOnlyTxManager"} and sets {@code readOnly = true}, so the
 *       work runs under the transaction manager bound to the {@code readOnlyDataSource} &mdash; the
 *       connection authenticates as the Read_Only_Role. PostgreSQL itself denies any write or any
 *       reference to the Interactions_Table from this role (Req 3.3, 3.4).</li>
 *   <li><strong>Statement_Timeout (Req 10.4).</strong> At the start of the transaction the executor
 *       issues {@code SET LOCAL statement_timeout = <ms>} (from
 *       {@link AppProperties#statementTimeout()}, default 5s). {@code SET LOCAL} scopes the setting
 *       to the current transaction only. A timeout surfaces as a {@code SQLException} with SQLState
 *       {@code 57014}; mapping that to HTTP 504 is task 7.2/7.3 &mdash; here we only ensure the
 *       bound is applied.</li>
 *   <li><strong>Enforced_Limit, belt-and-suspenders (Req 10.3, Change 3).</strong> The validated
 *       statement text is wrapped in an outer {@code SELECT * FROM ( ... ) AS _wrapped LIMIT n}
 *       subquery, <em>and</em> the JDBC {@code maxRows} cap is set on the executing statement. Either
 *       alone would cap the result at {@link AppProperties#enforcedLimit()} (default 100); both
 *       together mean the driver stops fetching past the cap even if the wrapper were somehow
 *       bypassed.</li>
 * </ol>
 *
 * <h2>Why a fresh local {@link JdbcTemplate} that still shares the transaction</h2>
 *
 * <p>The executor injects the read-only {@link DataSource} and, inside {@link #execute}, constructs
 * a <em>new</em> {@code JdbcTemplate(dataSource)} per call. This is deliberate:
 *
 * <ul>
 *   <li>{@code JdbcTemplate.setMaxRows(int)} mutates template state. Calling it on the shared
 *       {@code readOnlyJdbcTemplate} bean would leak the cap (or a stale cap) across concurrent
 *       callers. A per-call template keeps the shared bean untouched.</li>
 *   <li>A {@code JdbcTemplate} does not own a connection &mdash; it obtains one from its
 *       {@code DataSource} via {@code DataSourceUtils.getConnection(dataSource)}. When a Spring
 *       transaction is active (as it is here, opened by {@code readOnlyTxManager} for this method),
 *       {@code DataSourceUtils} returns the single transaction-bound connection registered for that
 *       exact {@code DataSource}. Because this local template wraps the <em>same</em>
 *       {@code readOnlyDataSource} the transaction manager opened the transaction on, both the
 *       {@code SET LOCAL statement_timeout} statement and the wrapped query run on the one
 *       transaction-bound connection. If they ran on different connections, {@code SET LOCAL} (which
 *       only affects its own transaction/connection) would have no effect on the query &mdash; so
 *       sharing the connection is what makes the timeout bound real.</li>
 * </ul>
 *
 * <p><strong>Proxy note.</strong> {@code @Transactional} is proxy-based, so {@link #execute} must be
 * invoked from <em>another</em> bean (the Query_Service, task 10) for the transaction advice to
 * apply. It must not be called from within this class.
 *
 * <p><strong>Scope.</strong> This task applies the bounds only. SQLState classification
 * (correctable 42xxx vs. timeout 57014) and unwrapping Spring's {@code DataAccessException} to the
 * root {@code SQLException} are task 7.2; integration tests are task 7.3. Here, any
 * {@code SQLException}/{@code DataAccessException} is allowed to propagate.
 */
@Component
public class SqlExecutor {

    private final DataSource readOnlyDataSource;
    private final AppProperties appProperties;

    /**
     * @param readOnlyDataSource the read-only datasource (Read_Only_Role); injected by name so it is
     *                           never the privileged read-write pool (neither pool is {@code @Primary})
     * @param appProperties      application tunables supplying Enforced_Limit and Statement_Timeout
     */
    public SqlExecutor(
            @Qualifier("readOnlyDataSource") DataSource readOnlyDataSource,
            AppProperties appProperties) {
        this.readOnlyDataSource = readOnlyDataSource;
        this.appProperties = appProperties;
    }

    /**
     * Runs the validated statement in a bounded, read-only transaction and returns the result rows.
     *
     * <p>The returned {@code List<Map<String,Object>>} maps each row to its column-name/value pairs
     * (via {@link ColumnMapRowMapper}); it becomes {@code QueryResponse.table}.
     *
     * @param sql the validated, parsed SELECT to execute
     * @return the result rows, capped at {@link AppProperties#enforcedLimit()}
     */
    @Transactional(readOnly = true, transactionManager = "readOnlyTxManager")
    public List<Map<String, Object>> execute(ValidatedSql sql) {
        int enforcedLimit = appProperties.enforcedLimit();
        long timeoutMillis = appProperties.statementTimeout().toMillis();

        // Fresh per-call template so setMaxRows does not mutate the shared bean. It wraps the same
        // read-only datasource the transaction was opened on, so DataSourceUtils hands it the single
        // transaction-bound connection: the SET LOCAL below and the query share one connection.
        JdbcTemplate jdbcTemplate = new JdbcTemplate(readOnlyDataSource);
        jdbcTemplate.setMaxRows(enforcedLimit);

        // Statement_Timeout (10.4): SET LOCAL scopes to this transaction only. timeoutMillis is an
        // integer derived from trusted config (not user input), so inlining it is injection-safe.
        jdbcTemplate.execute("SET LOCAL statement_timeout = " + timeoutMillis);

        // Enforced_Limit (10.3, Change 3): wrap the normalized AST text (no trailing semicolon) in an
        // outer LIMIT subquery. normalizedSql() == statement.toString(), never a raw LLM string.
        String wrappedSql =
                "SELECT * FROM ( " + sql.normalizedSql() + " ) AS _wrapped LIMIT " + enforcedLimit;

        return jdbcTemplate.query(wrappedSql, new ColumnMapRowMapper());
    }
}
