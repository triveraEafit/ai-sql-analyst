package com.aisqlanalyst.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

import com.aisqlanalyst.dto.InteractionSummary;

/**
 * Integration tests for {@link InteractionRepository} against a real PostgreSQL started via
 * Testcontainers (Requirements 6.1, 6.2, 7.1, 7.2).
 *
 * <h2>Why an integration test (not a unit test)</h2>
 * The behaviours under test are database-enforced, not Java logic:
 * <ul>
 *   <li><strong>6.1 / 6.2 round-trip.</strong> That a {@code SUCCESS} row persists all its fields,
 *       that a {@code FAILED} row persists {@code generated_sql = NULL}, and that the DB defaults
 *       ({@code id BIGSERIAL}, {@code created_at TIMESTAMPTZ DEFAULT now()}) are applied, can only be
 *       proven by inserting and reading back through a real driver/server. The nullable-column
 *       round-trip (null in &rarr; null out, and the repository's documented {@code latency_ms}
 *       {@code NULL -> 0L} mapping) depends on real JDBC {@code getString}/{@code getLong}
 *       semantics.</li>
 *   <li><strong>7.1 / 7.2 ordering and cap.</strong> The newest-first order
 *       ({@code ORDER BY created_at DESC, id DESC}) and the {@code LIMIT 50} cap are expressed in SQL
 *       and resolved by PostgreSQL; proving them requires the real server ordering real rows.</li>
 * </ul>
 *
 * <h2>Database initialization &mdash; entrypoint approach</h2>
 * Mirrors {@code DataSourceSeparationIntegrationTest} and {@code SqlExecutorIntegrationTest}: the
 * repository {@code db/} directory is copied into {@code /docker-entrypoint-initdb.d} and
 * {@code RO_DB_PASSWORD} is set, so the postgres entrypoint runs {@code db/01-init.sh} to apply
 * {@code schema.sql} / {@code seed.sql} and provision {@code read_only_role}. The seed does not
 * populate {@code interactions}, so the table starts empty.
 *
 * <h2>Datasource wiring</h2>
 * {@link DynamicPropertySource} points the datasources at the container exactly as the sibling
 * integration tests do. The repository uses the <em>read-write</em> datasource (the container's
 * owning superuser), which owns the {@code interactions} table, so its INSERTs and SELECTs succeed.
 *
 * <h2>Deterministic cleanup</h2>
 * A single container is shared across the test methods (static {@code @Container}), so rows would
 * otherwise accumulate between methods. To keep every assertion simple and deterministic, each test
 * starts from an empty, reset table: {@link #truncateInteractions()} runs
 * {@code TRUNCATE interactions RESTART IDENTITY} via an {@link Qualifier}-qualified read-write
 * {@link JdbcTemplate} before each test. {@code RESTART IDENTITY} also resets the {@code BIGSERIAL}
 * so id-based assertions are independent of prior methods. TRUNCATE runs as the owning superuser.
 */
@SpringBootTest
@Testcontainers
class InteractionRepositoryIntegrationTest {

    /** Password for the read-only login role; a throwaway value scoped to this ephemeral container. */
    private static final String RO_PASSWORD = "ro_test_pw";

    /** The login role name created by {@code db/01-init.sh}. */
    private static final String RO_ROLE = "read_only_role";

    /** The history cap the application uses (Requirement 7.2); the caller passes 50 to findRecent. */
    private static final int HISTORY_CAP = 50;

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
        // Read-write: the container's owning superuser; owns the interactions table (writes+reads).
        registry.add("DB_RW_USER", POSTGRES::getUsername);
        registry.add("DB_RW_PASSWORD", POSTGRES::getPassword);
        // Read-only: the restricted role provisioned by 01-init.sh (unused here, but required wiring).
        registry.add("DB_RO_USER", () -> RO_ROLE);
        registry.add("DB_RO_PASSWORD", () -> RO_PASSWORD);
        // Always-required env vars so the fail-fast validator passes and the context starts.
        registry.add("FRONTEND_ORIGIN", () -> "http://localhost:3000");
        registry.add("LLM_PROVIDER", () -> "mock");
    }

    @Autowired
    private InteractionRepository repository;

    /**
     * Read-write template used only for test setup/cleanup (TRUNCATE). The {@link Qualifier} is
     * mandatory: neither JdbcTemplate bean is {@code @Primary}.
     */
    @Autowired
    @Qualifier("readWriteJdbcTemplate")
    private JdbcTemplate readWriteJdbcTemplate;

    /**
     * Resets the Interactions_Table to empty with a restarted identity before each test, so every
     * method sees a clean, deterministic starting state regardless of execution order.
     */
    @BeforeEach
    void truncateInteractions() {
        readWriteJdbcTemplate.execute("TRUNCATE interactions RESTART IDENTITY");
    }

    // ---------------------------------------------------------------------
    // Requirement 6.1 — a SUCCESS interaction persists all fields and reads back.
    // ---------------------------------------------------------------------

    /**
     * Saving a {@code SUCCESS} interaction with every field populated, then reading it back via
     * {@link InteractionRepository#findRecent(int)}, returns the row with all field values intact and
     * a DB-generated {@code createdAt}.
     */
    @Test
    void save_thenFindRecent_roundTripsSuccessRow() {
        Interaction success = new Interaction(
                "how many customers?",
                "SELECT count(*) FROM public.customers",
                "250",
                "Counts all customers.",
                "SUCCESS",
                123L);

        repository.save(success);

        List<InteractionSummary> recent = repository.findRecent(HISTORY_CAP);

        assertThat(recent)
                .as("the single saved SUCCESS row must be returned by findRecent")
                .hasSize(1);
        InteractionSummary row = recent.get(0);
        assertThat(row.question()).isEqualTo("how many customers?");
        assertThat(row.generatedSql()).isEqualTo("SELECT count(*) FROM public.customers");
        assertThat(row.resultSummary()).isEqualTo("250");
        assertThat(row.explanation()).isEqualTo("Counts all customers.");
        assertThat(row.status()).isEqualTo("SUCCESS");
        assertThat(row.latencyMs()).isEqualTo(123L);
        assertThat(row.createdAt())
                .as("created_at must be DB-generated (DEFAULT now()) and therefore non-null")
                .isNotNull();
    }

    // ---------------------------------------------------------------------
    // Requirement 6.2 / 6.3 — a FAILED interaction persists with NULL generated_sql.
    // ---------------------------------------------------------------------

    /**
     * Saving a {@code FAILED} interaction whose {@code generatedSql}, {@code resultSummary} and
     * {@code latencyMs} are {@code null} round-trips: the nullable {@code generated_sql} column
     * returns {@code null}, and a {@code NULL} {@code latency_ms} maps to {@code 0L} per the
     * repository's documented mapping.
     */
    @Test
    void save_thenFindRecent_roundTripsFailedRowWithNullGeneratedSql() {
        Interaction failed = new Interaction(
                "give me everything",
                null,          // no SQL was generated for the failed interaction
                null,          // no result summary
                "The request could not be fulfilled.",
                "FAILED",
                null);         // latency not measured

        repository.save(failed);

        List<InteractionSummary> recent = repository.findRecent(HISTORY_CAP);

        assertThat(recent).hasSize(1);
        InteractionSummary row = recent.get(0);
        assertThat(row.question()).isEqualTo("give me everything");
        assertThat(row.status()).isEqualTo("FAILED");
        assertThat(row.generatedSql())
                .as("a nullable generated_sql column must round-trip as null")
                .isNull();
        assertThat(row.resultSummary()).isNull();
        assertThat(row.explanation()).isEqualTo("The request could not be fulfilled.");
        assertThat(row.latencyMs())
                .as("a SQL NULL latency_ms must map to 0L (primitive long cannot hold null)")
                .isEqualTo(0L);
        assertThat(row.createdAt()).isNotNull();
    }

    // ---------------------------------------------------------------------
    // Requirement 7.1 / 7.2 — history is newest-first and capped at 50.
    // ---------------------------------------------------------------------

    /**
     * Inserting more rows than the cap (55) and asking for the 50 most recent returns exactly 50
     * rows (Requirement 7.2), newest-first (Requirement 7.1).
     *
     * <p>Because {@code created_at} defaults to {@code now()} and many inserts can land in the same
     * timestamp tick, the ordering is asserted via the stable {@code id DESC} tiebreaker (the ids are
     * assigned in insertion order by the {@code BIGSERIAL}). After {@code RESTART IDENTITY}, the 55
     * inserts receive ids {@code 1..55}; the newest 50 are ids {@code 55..6}. The test asserts:
     * <ul>
     *   <li>the returned ids are strictly descending (newest-first),</li>
     *   <li>the returned set is exactly the highest 50 ids {@code {6..55}} (the 5 oldest,
     *       {@code {1..5}}, are excluded by the cap), and</li>
     *   <li>the first element is the last-inserted row ({@code "q55"}).</li>
     * </ul>
     */
    @Test
    void findRecent_isNewestFirstAndCappedAtFifty() {
        int totalInserted = 55;
        for (int i = 1; i <= totalInserted; i++) {
            repository.save(new Interaction(
                    "q" + i,
                    "SELECT " + i,
                    String.valueOf(i),
                    "explanation " + i,
                    "SUCCESS",
                    (long) i));
        }

        List<InteractionSummary> recent = repository.findRecent(HISTORY_CAP);

        assertThat(recent)
                .as("history must be capped at %d even though %d rows exist", HISTORY_CAP, totalInserted)
                .hasSize(HISTORY_CAP);

        List<Long> returnedIds = new ArrayList<>();
        for (InteractionSummary s : recent) {
            returnedIds.add(s.id());
        }

        assertThat(returnedIds)
                .as("ids must be strictly descending (newest-first)")
                .isSortedAccordingTo((a, b) -> Long.compare(b, a))
                .doesNotHaveDuplicates();

        // The newest 50 of ids 1..55 are 6..55. The 5 oldest (1..5) must be excluded.
        List<Long> expectedNewestFifty = new ArrayList<>();
        for (long id = totalInserted; id >= totalInserted - HISTORY_CAP + 1; id--) {
            expectedNewestFifty.add(id);
        }
        assertThat(returnedIds)
                .as("the returned ids must be exactly the highest %d, excluding the 5 oldest",
                        HISTORY_CAP)
                .containsExactlyElementsOf(expectedNewestFifty);

        assertThat(recent.get(0).question())
                .as("the first (newest) element must be the last-inserted row")
                .isEqualTo("q" + totalInserted);
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
