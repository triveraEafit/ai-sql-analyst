package com.aisqlanalyst;

import com.aisqlanalyst.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the AI SQL Analyst backend.
 *
 * <p>Spring Boot's default {@link DataSourceAutoConfiguration} is excluded here because the
 * application defines two explicit datasources (a read-write datasource for the Interactions_Table
 * and a read-only datasource that runs generated SQL) in a later task. Without this exclusion,
 * auto-configuration would attempt to build a single default {@code DataSource} and fail startup
 * before the dual datasources are wired. See design "Datasource URLs (Change 7)".
 *
 * <p>{@link EnableConfigurationProperties} registers {@link AppProperties}, the constructor-bound
 * {@code @Validated} binding for the {@code app.*} tunables (task 1.2), as a managed bean so the
 * values are validated at startup and injectable where consumed in later tasks.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableConfigurationProperties(AppProperties.class)
public class AiSqlAnalystApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiSqlAnalystApplication.class, args);
    }
}
