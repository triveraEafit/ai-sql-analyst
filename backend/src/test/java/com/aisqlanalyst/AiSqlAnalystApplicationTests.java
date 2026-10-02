package com.aisqlanalyst;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Smoke test: the Spring application context loads with the skeleton in place.
 *
 * <p>Required env-var placeholders are supplied as test properties so the YAML
 * {@code ${...}} references resolve and the fail-fast startup validation
 * (task 1.3) passes. Datasource beans are not defined yet (task 3) and
 * DataSourceAutoConfiguration is excluded, so no database is contacted during
 * context load. {@code LLM_PROVIDER=mock} is set so the provider secrets
 * ({@code LLM_API_KEY}/{@code LLM_BASE_URL}/{@code LLM_MODEL}) are not required
 * here, matching the design's conditional fail-fast logic (Change 11).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "DB_URL=jdbc:postgresql://localhost:5432/aisql",
        "DB_RW_USER=app_rw",
        "DB_RW_PASSWORD=test",
        "DB_RO_USER=app_ro",
        "DB_RO_PASSWORD=test",
        "FRONTEND_ORIGIN=http://localhost:3000",
        "LLM_PROVIDER=mock"
})
class AiSqlAnalystApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: fails if the application context cannot start.
    }
}
