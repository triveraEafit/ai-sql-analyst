package com.aisqlanalyst.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Regression guard: the application context must contain an {@link HttpLlmClient} bean when
 * {@code app.llm.provider=http}. Without an injectable constructor (the {@code @Autowired} on the
 * single-arg {@code HttpLlmClient(AppProperties)}), Spring cannot choose between the two
 * constructors and the bean fails to be created &mdash; this test fails fast if that regresses.
 *
 * <p>Dummy LLM provider values plus the always-required DB / FRONTEND_ORIGIN vars are supplied so
 * the fail-fast {@code RequiredEnvironmentValidator} passes. No database is contacted at context
 * load (lazy Hikari init), so no Docker is needed.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "DB_URL=jdbc:postgresql://localhost:5432/aisql",
        "DB_RW_USER=app_rw",
        "DB_RW_PASSWORD=test",
        "DB_RO_USER=app_ro",
        "DB_RO_PASSWORD=test",
        "FRONTEND_ORIGIN=http://localhost:3000",
        "LLM_PROVIDER=http",
        "LLM_API_KEY=dummy-key",
        "LLM_BASE_URL=http://localhost:9999",
        "LLM_MODEL=dummy-model"
})
class HttpLlmClientContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmClient llmClient;

    @Test
    void httpLlmClientBeanIsPresentWhenProviderIsHttp() {
        assertThat(context.getBeansOfType(HttpLlmClient.class)).hasSize(1);
        assertThat(context.getBeansOfType(MockLlmClient.class)).isEmpty();
        assertThat(llmClient).isInstanceOf(HttpLlmClient.class);
    }
}