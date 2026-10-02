package com.aisqlanalyst.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Dual-datasource wiring (design Change 7, Requirements 3.1, 3.2).
 *
 * <p>Spring Boot''s {@code DataSourceAutoConfiguration} is excluded on the main class, so the two
 * datasources are defined here explicitly. Both HikariCP pools point at the <em>same</em>
 * PostgreSQL instance but authenticate as different roles:
 *
 * <ul>
 *   <li><strong>Read-write</strong> ({@code datasource.read-write.*}, app role) &mdash; used only
 *       for the Interactions_Table (persistence and history).</li>
 *   <li><strong>Read-only</strong> ({@code datasource.read-only.*}, Read_Only_Role) &mdash; used
 *       only to run generated SQL. The Sql_Executor (task 7.1) runs under its transaction manager
 *       via {@code @Transactional(transactionManager = "readOnlyTxManager")}.</li>
 * </ul>
 *
 * <p><strong>No {@code @Primary}; explicit {@link Qualifier} everywhere.</strong> Neither pool is
 * marked primary. {@code DataSourceAutoConfiguration} is already excluded, so there is no competing
 * auto-configured {@code DataSource} that would require a primary to disambiguate. Marking the
 * read-write beans {@code @Primary} is actively harmful here: {@code @Primary} takes precedence over
 * parameter-name matching, so an <em>unqualified</em> {@code DataSource} parameter &mdash; even one
 * named {@code readOnlyDataSource} &mdash; would resolve to the read-write pool, silently routing the
 * "read-only" transaction manager and {@code JdbcTemplate} through the privileged role and defeating
 * the datasource separation (Requirements 3.2-3.4). Every injection point therefore carries an
 * explicit {@link Qualifier} naming the exact datasource bean. The datasource-separation integration
 * test asserts the read-only template authenticates as the restricted role.
 *
 * <p><strong>jdbc-url (not url).</strong> Each datasource is built with {@link DataSourceBuilder},
 * which binds the connection string from the Spring {@code jdbc-url} property;
 * {@code @ConfigurationProperties} binds the remaining Hikari settings from the matching prefix.
 *
 * <p><strong>Lazy initialization.</strong> {@code application.yml} sets
 * {@code initialization-fail-timeout: -1} on both prefixes, so building a pool does not open a
 * connection and the context starts without contacting PostgreSQL.
 *
 * <p><strong>Bean names</strong> for later tasks to qualify: {@code readWriteDataSource},
 * {@code readOnlyDataSource}, {@code transactionManager} (read-write), {@code readOnlyTxManager},
 * {@code readWriteJdbcTemplate}, and {@code readOnlyJdbcTemplate}.
 */
@Configuration
public class DataSourceConfig {

    // ---------------------------------------------------------------------
    // DataSources
    // ---------------------------------------------------------------------

    /**
     * Read-write datasource (app role) bound from {@code datasource.read-write.*}. Used only for the
     * Interactions_Table.
     *
     * @return a lazily-initialized {@link HikariDataSource} for the app role
     */
    @Bean
    @ConfigurationProperties("datasource.read-write")
    public HikariDataSource readWriteDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /**
     * Read-only datasource (Read_Only_Role) bound from {@code datasource.read-only.*}. Used only to
     * run validated, generated SQL; the role cannot see the Interactions_Table or perform writes.
     *
     * @return a lazily-initialized {@link HikariDataSource} for the read-only role
     */
    @Bean
    @ConfigurationProperties("datasource.read-only")
    public HikariDataSource readOnlyDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    // ---------------------------------------------------------------------
    // Transaction managers
    // ---------------------------------------------------------------------

    /**
     * Transaction manager over the read-write datasource, named {@code transactionManager} (the
     * Spring default bean name) for unqualified {@code @Transactional} usage on the read-write path.
     *
     * @param readWriteDataSource the read-write datasource bean
     * @return the read-write {@link PlatformTransactionManager}
     */
    @Bean
    public PlatformTransactionManager transactionManager(
            @Qualifier("readWriteDataSource") HikariDataSource readWriteDataSource) {
        return new DataSourceTransactionManager(readWriteDataSource);
    }

    /**
     * Transaction manager over the read-only datasource. The bean name MUST be exactly
     * {@code readOnlyTxManager}: the Sql_Executor (task 7.1) references it via
     * {@code @Transactional(transactionManager = "readOnlyTxManager")}.
     *
     * @param readOnlyDataSource the read-only datasource bean
     * @return the read-only {@link PlatformTransactionManager}
     */
    @Bean
    public PlatformTransactionManager readOnlyTxManager(
            @Qualifier("readOnlyDataSource") HikariDataSource readOnlyDataSource) {
        return new DataSourceTransactionManager(readOnlyDataSource);
    }

    // ---------------------------------------------------------------------
    // JdbcTemplates (convenience for later tasks)
    // ---------------------------------------------------------------------

    /**
     * JdbcTemplate over the read-write datasource, for the InteractionRepository (task 9).
     *
     * @param readWriteDataSource the read-write datasource bean
     * @return a {@link JdbcTemplate} bound to the read-write datasource
     */
    @Bean
    public JdbcTemplate readWriteJdbcTemplate(
            @Qualifier("readWriteDataSource") HikariDataSource readWriteDataSource) {
        return new JdbcTemplate(readWriteDataSource);
    }

    /**
     * JdbcTemplate over the read-only datasource, for the Sql_Executor (task 7.1).
     *
     * @param readOnlyDataSource the read-only datasource bean
     * @return a {@link JdbcTemplate} bound to the read-only datasource
     */
    @Bean
    public JdbcTemplate readOnlyJdbcTemplate(
            @Qualifier("readOnlyDataSource") HikariDataSource readOnlyDataSource) {
        return new JdbcTemplate(readOnlyDataSource);
    }
}
