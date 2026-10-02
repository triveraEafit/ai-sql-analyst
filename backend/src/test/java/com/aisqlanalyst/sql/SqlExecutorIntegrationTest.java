package com.aisqlanalyst.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Integration tests for the {@link SqlExecutor} bounds and {@link SqlErrorClassifier} classification
 * (Requirements 4.9, 10.3, 10.4, 5.2). These exercise the real validated&rarr;executed path against a
 * live PostgreSQL started via Testcontainers, running as the restricted {@code read_only_role}.
 *
 * <h2>Why an integration test (not a unit test)</h2>
 * The guarantees under test are enforced by PostgreSQL and the JDBC driver, not by Java logic:
 * <ul>
 *   <li><strong>Enforced_Limit (10.3, 4.9).</strong> The {@code SELECT * FROM (...) AS _wrapped
 *       LIMIT n} wrap plus the JDBC {@code maxRows} cap only cap rows when a real driver fetches
 *       real result rows. We prove it by executing a query that <em>would</em> return far more rows
 *       than the cap and asserting the returned list is capped at exactly {@code n}.</li>
 *   <li><strong>Statement_Timeout &rarr; SQLState 57014 (10.4, 5.2).</strong> {@code SET LOCAL
 *       statement_timeout} is a server-side setting; a timeout only surfaces as a driver
 *       {@code PSQLException} carrying SQLState {@code 57014} when a genuinely slow query runs
 *       against the real server. We prove it by running a deliberately heavy query under a short
 *       timeout and asserting {@link SqlErrorClassifier#isStatementTimeout(Throwable)} is true.</li>
 * </ul>
 *
 * <h2>Database initialization &mdash; entrypoint approach</h2>
 * Mirrors {@code DataSourceSeparationIntegrationTest}: the whole repository {@code db/} directory is
 * copied into {@code /docker-entrypoint-initdb.d} and {@code RO_DB_PASSWORD} is set, so the postgres
 * entrypoint runs {@code db/01-init.sh} to apply {@code schema.sql} / {@code seed.sql}, create
 * {@code read_only_role}, and GRANT {@code SELECT} on the four allowlisted tables. The executor uses
 * the read-only datasource, so these grants are exactly what the executed queries run under.
 *
 * <h2>Config overrides</h2>
 * {@link DynamicPropertySource} points the datasources at the container (read-write as the owning
 * superuser, read-only as {@code read_only_role}) and overrides the two executor bounds so the
 * assertions are fast and deterministic:
 * <ul>
 *   <li>{@code app.enforced-limit = 5} &mdash; small enough to prove the cap against the 250-row
 *       {@code customers} table.</li>
 *   <li>{@code app.statement-timeout = 500ms} &mdash; short enough that the heavy cross-join trips
 *       the timeout quickly, while leaving the trivial count/limit queries (which complete in a few
 *       milliseconds) unaffected.</li>
 * </ul>
 *
 * <h2>Proxy note</h2>
 * {@code SqlExecutor.execute} is {@code @Transactional} (proxy-based), so it is invoked on the
 * autowired {@link SqlExecutor} <em>bean</em> &mdash; never a hand-constructed instance &mdash; so
 * the read-only transaction advice (and therefore the connection-scoped {@code SET LOCAL
 * statement_timeout}) applies.
 */
@SpringBootTest
@Testcontainers
class SqlExecutorIntegrationTest {

    /** Password for the read-only login role; a throwaway value scoped to this ephemeral container. */
    private static final String RO_PASSWORD = "ro_test_pw";

    /** The login role name created by {@code db/01-init.sh}. */
    private static final String RO_ROLE = "read_only_role";

    /** Enforced_Limit override for this test class (small, to prove the cap quickly). */
    private static final int ENFORCED_LIMIT = 5;

    /** Rows the seed inserts into {@code customers} (see db/sql/seed.sql); &gt; ENFORCED_LIMIT. */
    private static final int SEEDED_CUSTOMERS = 250;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(dbDirectory().getAbsolutePath()),
                    "/docker-entrypoint-initdb.d")
            // Consumed by 01-init.sh to set the read_only_role password. Never a production secret.
            .withEnv("RO_DB_PASSWORD", RO_PASSWORD);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        // Read-write: the container's owning superuser (full access; needed only for context start).
        registry.add("DB_RW_USER", POSTGRES::getUsername);
        registry.add("DB_RW_PASSWORD", POSTGRES::getPassword);
        // Read-only: the restricted role the executor runs under.
        registry.add("DB_RO_USER", () -> RO_ROLE);
        registry.add("DB_RO_PASSWORD", () -> RO_PASSWORD);
        // Always-required env vars so the fail-fast validator passes and the context starts.
        registry.add("FRONTEND_ORIGIN", () -> "http://localhost:3000");
        registry.add("LLM_PROVIDER", () -> "mock");
        // Executor bound overrides: small limit + short timeout for fast, deterministic assertions.
        registry.add("app.enforced-limit", () -> ENFORCED_LIMIT);
        registry.add("app.statement-timeout", () -> "500ms");
    }

    @Autowired
    private SqlExecutor sqlExecutor;

    @Autowired
    private SqlValidator sqlValidator;

    @Autowired
    private SqlErrorClassifier sqlErrorClassifier;

    // ---------------------------------------------------------------------
    // Requirement 4.9 / 10.3 — Enforced_Limit caps the returned rows.
    // ---------------------------------------------------------------------

    /**
     * A query that would return all 250 seeded customers is capped at the Enforced_Limit (5). This
     * proves the LIMIT-subquery wrap and the JDBC {@code maxRows} cap work together on the real
     * driver/server. Goes through the full validate&rarr;execute path.
     */
    @Test
    void enforcedLimit_capsRowsBelowSourceSize() {
        ValidatedSql validated = sqlValidator.validate("SELECT id FROM customers");

        List<Map<String, Object>> rows = sqlExecutor.execute(validated);

        assertThat(rows)
                .as("250 seeded customers must be capped at the Enforced_Limit of %d", ENFORCED_LIMIT)
                .hasSize(ENFORCED_LIMIT);
    }

    /**
     * When the source has fewer rows than the Enforced_Limit, all rows are returned unchanged (the
     * cap is an upper bound, not a fixed size). A single aggregate row is well under the cap.
     */
    @Test
    void enforcedLimit_returnsAllRowsWhenFewerThanCap() {
        ValidatedSql validated = sqlValidator.validate("SELECT count(*) AS n FROM customers");

        List<Map<String, Object>> rows = sqlExecutor.execute(validated);

        assertThat(rows)
                .as("a single aggregate row is below the cap and must be returned in full")
                .hasSize(1);
    }

    // ---------------------------------------------------------------------
    // Supporting 4.9 / 3.2 — the read-only role runs allowlisted SELECTs and results map correctly.
    // ---------------------------------------------------------------------

    /**
     * Sanity: a validated {@code SELECT count(*) AS n FROM customers} executed through the real
     * executor on the read-only role returns the exact seeded count, proving column-name/value
     * mapping and the read-only grants.
     */
    @Test
    void sanity_executesAllowlistedCountAndMapsResult() {
        ValidatedSql validated = sqlValidator.validate("SELECT count(*) AS n FROM customers");

        List<Map<String, Object>> rows = sqlExecutor.execute(validated);

        assertThat(rows).hasSize(1);
        Object n = rows.get(0).get("n");
        assertThat(((Number) n).longValue())
                .as("count(*) of the seeded customers table")
                .isEqualTo(SEEDED_CUSTOMERS);
    }

    // ---------------------------------------------------------------------
    // Requirement 10.4 / 5.2 — a slow query trips Statement_Timeout (SQLState 57014).
    // ---------------------------------------------------------------------

    /**
     * A deliberately heavy query exceeds the short (500ms) Statement_Timeout and surfaces as a
     * failure whose root SQLState is {@code 57014} (query_canceled / statement_timeout), as
     * classified by {@link SqlErrorClassifier#isStatementTimeout(Throwable)}.
     *
     * <h3>Chosen query</h3>
     * {@code SELECT count(*) FROM order_items a, order_items b, order_items c} &mdash; a triple
     * self cross-join over the ~2250-row {@code order_items} table. That is roughly 2250<sup>3</sup>
     * &asymp; 1.1&times;10<sup>10</sup> intermediate rows the server must enumerate before the outer
     * aggregate completes, which far exceeds 500ms of work. The query is a valid, allowlisted
     * {@code SELECT} ({@code count} is allowlisted; {@code order_items} is allowlisted; no disallowed
     * constructs), so it passes {@link SqlValidator} and the whole validated&rarr;executed path is
     * tested. The outer {@code LIMIT 1}/{@code maxRows} wrap cannot short-circuit the work: the
     * {@code count(*)} aggregates every joined row before a single output row exists.
     */
    @Test
    void statementTimeout_slowQueryIsClassifiedAs57014() {
        ValidatedSql validated = sqlValidator.validate(
                "SELECT count(*) FROM order_items a, order_items b, order_items c");

        Throwable thrown = catchThrowable(() -> sqlExecutor.execute(validated));

        assertThat(thrown)
                .as("the heavy cross-join must fail (be cancelled) under the 500ms timeout")
                .isNotNull();
        assertThat(sqlErrorClassifier.isStatementTimeout(thrown))
                .as("the failure must classify as a statement timeout (root SQLState 57014), "
                        + "root state was: %s", sqlErrorClassifier.rootSqlState(thrown))
                .isTrue();
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Resolves the repository {@code db/} directory regardless of the working directory the test is
     * launched from (the backend module directory under {@code mvn test}, or the repo root).
     *
     * @return the {@code db/} directory containing {@code 01-init.sh} and {@code sql/}
     * @throws IllegalStateException if the directory cannot be found
     */
    private static File dbDirectory() {
        for (String candidate : new String[] {"../db", "db", "../../db"}) {
            File dir = new File(candidate);
            if (new File(dir, "01-init.sh").isFile()) {
                return dir;
            }
        }
        throw new IllegalStateException(
                "Could not locate the repository db/ directory (expected to contain 01-init.sh) "
                        + "relative to working directory: " + new File(".").getAbsolutePath());
    }
}
