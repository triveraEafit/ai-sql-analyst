package com.aisqlanalyst.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link AppProperties} binds the {@code app.*} tunables with the expected defaults
 * from {@code application.yml} under a loaded application context.
 *
 * <p>Required env-var placeholders are supplied as test properties so the YAML {@code ${...}}
 * references resolve (matching the smoke test) and the fail-fast startup validation (task 1.3)
 * passes. {@code LLM_PROVIDER=mock} is set so the provider secrets are not required for this
 * context to load; the bound {@code app.llm.*} values still default to empty, which this test
 * asserts.
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
class AppPropertiesTest {

    @Autowired
    private AppProperties appProperties;

    @Test
    void bindsScalarTunableDefaults() {
        assertThat(appProperties.maxQuestionLength()).isEqualTo(300);
        assertThat(appProperties.enforcedLimit()).isEqualTo(100);
        assertThat(appProperties.statementTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void bindsLlmDefaults() {
        AppProperties.Llm llm = appProperties.llm();
        // provider resolves from LLM_PROVIDER (set to mock here so provider secrets are optional).
        assertThat(llm.provider()).isEqualTo("mock");
        assertThat(llm.timeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(llm.apiKey()).isEmpty();
        assertThat(llm.baseUrl()).isEmpty();
        assertThat(llm.model()).isEmpty();
    }

    @Test
    void bindsRateLimitDefaults() {
        AppProperties.RateLimit rateLimit = appProperties.rateLimit();
        assertThat(rateLimit.requestsPerMinute()).isEqualTo(30);
        assertThat(rateLimit.trustProxy()).isFalse();
    }

    @Test
    void bindsCorsFrontendOriginFromEnvPlaceholder() {
        assertThat(appProperties.cors().frontendOrigin()).isEqualTo("http://localhost:3000");
    }
}
