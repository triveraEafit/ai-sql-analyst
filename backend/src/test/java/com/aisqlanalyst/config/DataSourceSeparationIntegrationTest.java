package com.aisqlanalyst.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.File;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * Integration test proving the read-write / read-only datasource separation and the database-level
 * security posture it depends on (Requirements 3.1, 3.2, 3.3, 3.4).
 *
 * <h2>Why an integration test</h2>
 * The guarantees under test ("the read-only role cannot read the Interactions_Table", "it cannot
 * write any table") are enforced by PostgreSQL GRANT/REVOKE, not by Java. They can only be verified
 * against a real database that has the production role and grants applied. This test starts a
 * disposable PostgreSQL via Testcontainers and exercises the two {@link JdbcTemplate} beans from
 * {@link DataSourceConfig} against it.
 *
 * <h2>Database initialization &mdash; entrypoint approach</h2>
 * The whole repository {@code db/} directory is copied into the image''s
 * {@code /docker-entrypoint-initdb.d}, and {@code RO_DB_PASSWORD} is set. The official postgres
 * entrypoint then runs {@code db/01-init.sh}, which applies {@code db/sql/schema.sql} and
 * {@code db/sql/seed.sql}, creates {@code read_only_role}, GRANTs {@code SELECT} on the four
 * allowlisted tables, and REVOKEs every privilege on {@code interactions}. The test therefore
 * exercises the real grants from {@code 01-init.sh} rather than a re-implementation.
 *
 * <h2>Datasource wiring</h2>
 * {@link DynamicPropertySource} points both Spring datasources at the container: read-write
 * authenticates as the container''s owning superuser (owns the schema, so full access to
 * {@code interactions}); read-only authenticates as {@code read_only_role}. The two injected
 * {@link JdbcTemplate}s are {@link Qualifier}-qualified by bean name because neither datasource is
 * {@code @Primary}.
 */
@SpringBootTest
@Testcontainers
class DataSourceSeparationIntegrationTest {

    /** Password for the read-only login role; a throwaway value scoped to this ephemeral container. */
    private static final String RO_PASSWORD = "ro_test_pw";

    /** The login role name created by {@code db/01-init.sh}. */
    private static final String RO_ROLE = "read_only_role";

    /** PostgreSQL SQLState for {@code insufficient_privilege} (permission denied). */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Rows the seed inserts into {@code customers} (see db/sql/seed.sql). */
    private static final int EXPECTED_CUSTOMERS = 250;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(dbDirectory().getAbsolutePath()),
                    "/docker-entrypoint-initdb.d")
            // Consumed by 01-init.sh to set the read_only_role password. Never a production secret.
            .withEnv("RO_DB_PASSWORD", RO_PASSWORD);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        // Read-write: the container''s owning user (owns the schema -> full access to interactions).
        registry.add("DB_RW_USER", POSTGRES::getUsername);
        registry.add("DB_RW_PASSWORD", POSTGRES::getPassword);
        // Read-only: the restricted role provisioned by 01-init.sh.
        registry.add("DB_RO_USER", () -> RO_ROLE);
        registry.add("DB_RO_PASSWORD", () -> RO_PASSWORD);
        // Remaining always-required env vars so the fail-fast validator passes and the context starts.
        registry.add("FRONTEND_ORIGIN", () -> "http://localhost:3000");
        registry.add("LLM_PROVIDER", () -> "mock");
    }

    @Autowired
    @Qualifier("readWriteJdbcTemplate")
    private JdbcTemplate readWriteJdbcTemplate;

    @Autowired
    @Qualifier("readOnlyJdbcTemplate")
    private JdbcTemplate readOnlyJdbcTemplate;

    // ---------------------------------------------------------------------
    // Requirement 3.1 / 3.3 — the read-write datasource can read AND write interactions.
    // ---------------------------------------------------------------------

    @Test
    void readWriteDatasource_canWriteAndReadInteractions() {
        readWriteJdbcTemplate.update(
                "INSERT INTO interactions (question, generated_sql, result_summary, explanation, status, latency_ms) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                "how many customers?",
                "SELECT count(*) FROM public.customers",
                "250",
                "Counts all customers.",
                "SUCCESS",
                42L);

        Integer rows = readWriteJdbcTemplate.queryForObject(
                "SELECT count(*) FROM interactions WHERE question = ?",
                Integer.class,
                "how many customers?");

        assertThat(rows).isEqualTo(1);
    }

    // ---------------------------------------------------------------------
    // Requirement 3.2 — the read-only datasource can SELECT allowlisted tables.
    // ---------------------------------------------------------------------

    @Test
    void readOnlyDatasource_canSelectAllowlistedTables() {
        Integer customerCount =
                readOnlyJdbcTemplate.queryForObject("SELECT count(*) FROM customers", Integer.class);

        assertThat(customerCount).isEqualTo(EXPECTED_CUSTOMERS);
    }

    // ---------------------------------------------------------------------
    // Requirement 3.3 — the read-only datasource CANNOT read the Interactions_Table.
    // ---------------------------------------------------------------------

    @Test
    void readOnlyDatasource_cannotReadInteractions() {
        Throwable thrown = catchThrowable(
                () -> readOnlyJdbcTemplate.queryForObject("SELECT count(*) FROM interactions", Integer.class));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown))
                .as("read_only_role must be denied access to interactions (insufficient_privilege)")
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // ---------------------------------------------------------------------
    // Requirement 3.4 — the read-only datasource CANNOT modify any table.
    // ---------------------------------------------------------------------

    @Test
    void readOnlyDatasource_cannotWriteAllowlistedTable() {
        Throwable thrown = catchThrowable(() -> readOnlyJdbcTemplate.update(
                "INSERT INTO customers (name, email, country) VALUES (?, ?, ?)",
                "Mallory",
                "mallory@example.com",
                "US"));

        assertThat(thrown).isNotNull();
        assertThat(sqlStateOf(thrown))
                .as("read_only_role must be denied writes on allowlisted tables (insufficient_privilege)")
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Unwraps a Spring {@code DataAccessException} chain to the root {@link SQLException} and returns
     * its SQLState. Mirrors the Sql_Executor classification approach (task 7.2): read the SQLState
     * from the root {@link SQLException}, never from the Spring wrapper.
     *
     * @param thrown the exception thrown by a JdbcTemplate operation
     * @return the SQLState of the root {@link SQLException}, or {@code null} if none is present
     */
    private static String sqlStateOf(Throwable thrown) {
        Throwable cursor = thrown;
        SQLException sqlException = null;
        while (cursor != null) {
            if (cursor instanceof SQLException sqle) {
                sqlException = sqle;
            }
            if (cursor.getCause() == cursor) {
                break;
            }
            cursor = cursor.getCause();
        }
        return sqlException == null ? null : sqlException.getSQLState();
    }

    /**
     * Resolves the repository {@code db/} directory robustly regardless of the working directory the
     * test is launched from (the backend module directory under {@code mvn test}, or the repo root).
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
