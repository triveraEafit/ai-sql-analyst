package com.aisqlanalyst.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Unit tests for {@link RequiredEnvironmentValidator} (Requirements 11.1, 10.1).
 *
 * <p>These drive the validator directly against a {@link MockEnvironment} so each scenario is fast
 * and deterministic without starting a full application context. End-to-end fail-fast behavior
 * (throwing aborts context refresh) is covered by {@link StartupFailsFastTest}; the happy path is
 * exercised by the smoke test {@code AiSqlAnalystApplicationTests}.
 */
class RequiredEnvironmentValidatorTest {

    private final RequiredEnvironmentValidator validator = validatorStub();

    /**
     * The real bean validates in its constructor; a provided environment would run validation
     * before we can configure it. For method-level tests we build a stub whose constructor is given
     * a fully-populated environment, then call {@link RequiredEnvironmentValidator#validate} with
     * the scenario-specific environment.
     */
    private static RequiredEnvironmentValidator validatorStub() {
        return new RequiredEnvironmentValidator(fullyPopulated());
    }

    private static MockEnvironment fullyPopulated() {
        return new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/aisql")
                .withProperty("DB_RW_USER", "app_rw")
                .withProperty("DB_RW_PASSWORD", "secret")
                .withProperty("DB_RO_USER", "app_ro")
                .withProperty("DB_RO_PASSWORD", "secret")
                .withProperty("FRONTEND_ORIGIN", "http://localhost:3000")
                .withProperty("LLM_PROVIDER", "mock");
    }

    @Test
    void allRequiredPresentWithMockProvider_passes() {
        assertThatCode(() -> validator.validate(fullyPopulated())).doesNotThrowAnyException();
    }

    @Test
    void allRequiredPresentWithHttpProviderAndLlmSecrets_passes() {
        MockEnvironment env = fullyPopulated()
                .withProperty("LLM_PROVIDER", "http")
                .withProperty("LLM_API_KEY", "key")
                .withProperty("LLM_BASE_URL", "https://api.example.com")
                .withProperty("LLM_MODEL", "gpt-x");
        assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void missingAlwaysRequiredVar_failsAndNamesIt() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/aisql")
                .withProperty("DB_RW_USER", "app_rw")
                .withProperty("DB_RW_PASSWORD", "secret")
                .withProperty("DB_RO_USER", "app_ro")
                // DB_RO_PASSWORD intentionally absent
                .withProperty("FRONTEND_ORIGIN", "http://localhost:3000")
                .withProperty("LLM_PROVIDER", "mock");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("DB_RO_PASSWORD")
                .satisfies(ex -> {
                    MissingEnvironmentVariableException m = (MissingEnvironmentVariableException) ex;
                    org.assertj.core.api.Assertions.assertThat(m.getMissingVariables())
                            .containsExactly("DB_RO_PASSWORD");
                });
    }

    @Test
    void blankAlwaysRequiredVar_isTreatedAsMissing() {
        MockEnvironment env = fullyPopulated().withProperty("FRONTEND_ORIGIN", "   ");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("FRONTEND_ORIGIN");
    }

    @Test
    void mockProviderWithLlmSecretsAbsent_passes() {
        // fullyPopulated() already uses LLM_PROVIDER=mock and supplies no LLM_* secrets.
        assertThatCode(() -> validator.validate(fullyPopulated())).doesNotThrowAnyException();
    }

    @Test
    void mockProviderIsCaseInsensitiveAndTrimmed() {
        MockEnvironment env = fullyPopulated().withProperty("LLM_PROVIDER", "  MoCk  ");
        assertThatCode(() -> validator.validate(env)).doesNotThrowAnyException();
    }

    @Test
    void httpProviderWithLlmApiKeyAbsent_failsNamingLlmApiKey() {
        MockEnvironment env = fullyPopulated()
                .withProperty("LLM_PROVIDER", "http")
                // LLM_API_KEY absent
                .withProperty("LLM_BASE_URL", "https://api.example.com")
                .withProperty("LLM_MODEL", "gpt-x");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("LLM_API_KEY");
    }

    @Test
    void unsetProviderDefaultsToHttp_requiresLlmSecrets() {
        MockEnvironment noProvider = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/aisql")
                .withProperty("DB_RW_USER", "app_rw")
                .withProperty("DB_RW_PASSWORD", "secret")
                .withProperty("DB_RO_USER", "app_ro")
                .withProperty("DB_RO_PASSWORD", "secret")
                .withProperty("FRONTEND_ORIGIN", "http://localhost:3000");
        // No LLM_PROVIDER -> defaults to http -> LLM_* required.

        assertThatThrownBy(() -> validator.validate(noProvider))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .hasMessageContaining("LLM_API_KEY")
                .hasMessageContaining("LLM_BASE_URL")
                .hasMessageContaining("LLM_MODEL");
    }

    @Test
    void multipleMissingVars_areAllNamed() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("DB_URL", "jdbc:postgresql://localhost:5432/aisql")
                // DB_RW_USER, DB_RW_PASSWORD absent
                .withProperty("DB_RO_USER", "app_ro")
                .withProperty("DB_RO_PASSWORD", "secret")
                // FRONTEND_ORIGIN absent
                .withProperty("LLM_PROVIDER", "mock");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                .satisfies(ex -> {
                    MissingEnvironmentVariableException m = (MissingEnvironmentVariableException) ex;
                    org.assertj.core.api.Assertions.assertThat(m.getMissingVariables())
                            .containsExactlyInAnyOrder("DB_RW_USER", "DB_RW_PASSWORD", "FRONTEND_ORIGIN");
                });
    }

    @Test
    void failureMessageDoesNotEchoSecretValues() {
        MockEnvironment env = fullyPopulated()
                .withProperty("LLM_PROVIDER", "http")
                .withProperty("LLM_API_KEY", "") // present-but-blank -> missing
                .withProperty("LLM_BASE_URL", "https://api.example.com")
                .withProperty("LLM_MODEL", "gpt-x");

        assertThatThrownBy(() -> validator.validate(env))
                .isInstanceOf(MissingEnvironmentVariableException.class)
                // DB_RW_PASSWORD/DB_RO_PASSWORD values must never appear in the message.
                .hasMessageNotContaining("secret");
    }
}
