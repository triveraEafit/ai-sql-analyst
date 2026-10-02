package com.aisqlanalyst.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.aisqlanalyst.dto.InteractionSummary;

/**
 * JdbcTemplate-backed repository for the Interactions_Table, bound to the Read_Write_Datasource
 * (design "InteractionRepository", Requirements 3.1, 6.1&ndash;6.4, 7.1, 7.2).
 *
 * <p><strong>Datasource wiring.</strong> The repository writes and reads the Interactions_Table,
 * which only the app (read-write) role may touch &mdash; the Read_Only_Role has no privilege on it
 * (Requirement 3.1, 3.3). It therefore injects the {@code readWriteJdbcTemplate} bean by explicit
 * {@link Qualifier}. Neither {@code JdbcTemplate} bean is {@code @Primary}, so the qualifier is
 * mandatory to disambiguate between {@code readWriteJdbcTemplate} and {@code readOnlyJdbcTemplate}.
 *
 * <p><strong>Persistence (Requirements 6.1&ndash;6.4).</strong> {@link #save(Interaction)} inserts
 * every outcome &mdash; both {@code SUCCESS} and {@code FAILED}. {@code created_at} is omitted from
 * the insert so PostgreSQL applies its {@code DEFAULT now()}. The nullable {@code generated_sql} and
 * {@code latency_ms} columns accept {@code null} directly.
 *
 * <p><strong>History (Requirements 7.1, 7.2).</strong> {@link #findRecent(int)} returns the most
 * recent interactions newest-first, capped at the caller-supplied {@code n} (the caller passes 50).
 */
@Repository
public class InteractionRepository {

    /**
     * INSERT for {@link #save(Interaction)}. {@code id} and {@code created_at} are DB-generated
     * (BIGSERIAL and {@code DEFAULT now()}), so they are not listed; the six writable columns are
     * bound positionally. {@code generated_sql}, {@code result_summary}, {@code explanation} and
     * {@code latency_ms} may be bound to {@code null}.
     */
    private static final String INSERT_SQL =
            "INSERT INTO interactions "
                    + "(question, generated_sql, result_summary, explanation, status, latency_ms) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    /**
     * History query (design Change 10). Newest-first via {@code created_at DESC, id DESC} (the
     * {@code id} tiebreaker gives a stable order when two rows share the same timestamp), capped at
     * the bound {@code LIMIT ?} parameter.
     */
    private static final String FIND_RECENT_SQL =
            "SELECT id, question, generated_sql, result_summary, explanation, "
                    + "status, latency_ms, created_at "
                    + "FROM interactions "
                    + "ORDER BY created_at DESC, id DESC "
                    + "LIMIT ?";

    private static final RowMapper<InteractionSummary> ROW_MAPPER = InteractionRepository::mapRow;

    private final JdbcTemplate jdbcTemplate;

    /**
     * @param readWriteJdbcTemplate the {@code JdbcTemplate} bound to the read-write datasource.
     *                              The {@link Qualifier} is mandatory: neither JdbcTemplate bean is
     *                              {@code @Primary}.
     */
    public InteractionRepository(
            @Qualifier("readWriteJdbcTemplate") JdbcTemplate readWriteJdbcTemplate) {
        this.jdbcTemplate = readWriteJdbcTemplate;
    }

    /**
     * Persists one interaction (Requirements 6.1&ndash;6.4). Both {@code SUCCESS} and {@code FAILED}
     * outcomes are saved. {@code created_at} defaults to {@code now()} in the database.
     *
     * <p>Nullable fields are bound with their SQL types so the JDBC driver sets a typed
     * {@code NULL} rather than attempting to infer the type of a Java {@code null} &mdash;
     * {@code generated_sql}/{@code result_summary}/{@code explanation} as {@link Types#VARCHAR} and
     * {@code latency_ms} as {@link Types#BIGINT}.
     *
     * @param interaction the interaction to insert; its {@code question} and {@code status} are
     *                    expected to be non-null (matching the {@code NOT NULL} columns).
     */
    public void save(Interaction interaction) {
        jdbcTemplate.update(
                INSERT_SQL,
                new Object[] {
                        interaction.question(),
                        interaction.generatedSql(),
                        interaction.resultSummary(),
                        interaction.explanation(),
                        interaction.status(),
                        interaction.latencyMs()
                },
                new int[] {
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.VARCHAR,
                        Types.BIGINT
                });
    }

    /**
     * Returns the most recent interactions, newest-first, capped at {@code n}
     * (Requirements 7.1, 7.2). The caller passes {@code 50}.
     *
     * @param n the maximum number of rows to return (bound to {@code LIMIT ?}).
     * @return the recent interactions ordered newest-first; empty when there are none.
     */
    public List<InteractionSummary> findRecent(int n) {
        return jdbcTemplate.query(FIND_RECENT_SQL, ROW_MAPPER, n);
    }

    /**
     * Maps one {@code interactions} row to an {@link InteractionSummary}.
     *
     * <ul>
     *   <li>{@code generated_sql} may be {@code null} &mdash; {@link ResultSet#getString} returns
     *       {@code null}, which is the desired value for the nullable field.</li>
     *   <li>{@code latency_ms} is nullable in the database but {@link InteractionSummary#latencyMs()}
     *       is a primitive {@code long}, which cannot hold {@code null}. A SQL {@code NULL} latency
     *       is therefore mapped to {@code 0L} (and {@link ResultSet#getLong} already returns
     *       {@code 0} for SQL {@code NULL}).</li>
     *   <li>{@code created_at} is {@code TIMESTAMPTZ}; it is read as an {@link OffsetDateTime} and
     *       converted to an {@link java.time.Instant}, which preserves the exact instant regardless
     *       of the session time zone.</li>
     * </ul>
     */
    private static InteractionSummary mapRow(ResultSet rs, int rowNum) throws SQLException {
        long id = rs.getLong("id");
        String question = rs.getString("question");
        String generatedSql = rs.getString("generated_sql");
        String resultSummary = rs.getString("result_summary");
        String explanation = rs.getString("explanation");
        String status = rs.getString("status");
        long latencyMs = rs.getLong("latency_ms"); // SQL NULL -> 0L
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new InteractionSummary(
                id,
                question,
                generatedSql,
                resultSummary,
                explanation,
                status,
                latencyMs,
                createdAt == null ? null : createdAt.toInstant());
    }
}
