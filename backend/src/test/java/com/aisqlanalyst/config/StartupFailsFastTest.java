package com.aisqlanalyst.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aisqlanalyst.AiSqlAnalystApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * End-to-end confirmation that a missing Required_Environment_Variable aborts startup
 * (Requirement 11.1). Boots the real {@link AiSqlAnalystApplication} with properties that omit a
 * required variable and asserts the context refresh fails, with the thrown chain naming the
 * missing variable.
 *
 * <p>Runs as a non-web application and never touches a datasource (DataSourceAutoConfiguration is
 * excluded on the main class), so the only reason startup fails here is the env-var validation.
 */
class StartupFailsFastTest {

    private static Map<String, Object> baseProps() {
        return Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/aisql",
                "DB_RW_USER", "app_rw",
                "DB_RW_PASSWORD", "test",
                "DB_RO_USER", "app_ro",
                "DB_RO_PASSWORD", "test",
                "FRONTEND_ORIGIN", "http://localhost:3000",
                "LLM_PROVIDER", "mock");
    }

    @Test
    void startsWhenAllRequiredPresent() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(AiSqlAnalystApplication.class)
                .web(WebApplicationType.NONE)
                .properties(baseProps());

        try (ConfigurableApplicationContext ctx = builder.run()) {
            assertThat(ctx.isRunning()).isTrue();
        }
    }

    @Test
    void startupFailsAndNamesMissingVariable() {
        Map<String, Object> props = new java.util.HashMap<>(baseProps());
        props.remove("DB_RO_PASSWORD");

        SpringApplicationBuilder builder = new SpringApplicationBuilder(AiSqlAnalystApplication.class)
                .web(WebApplicationType.NONE)
                .properties(props);

        assertThatThrownBy(builder::run)
                .getRootCause()
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("DB_RO_PASSWORD");
    }

    @Test
    void httpProviderMissingLlmSecrets_failsNamingThem() {
        Map<String, Object> props = new java.util.HashMap<>(baseProps());
        props.put("LLM_PROVIDER", "http"); // no LLM_* secrets provided

        SpringApplicationBuilder builder = new SpringApplicationBuilder(AiSqlAnalystApplication.class)
                .web(WebApplicationType.NONE)
                .properties(props);

        assertThatThrownBy(builder::run)
                .getRootCause()
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("LLM_API_KEY");
    }
}
