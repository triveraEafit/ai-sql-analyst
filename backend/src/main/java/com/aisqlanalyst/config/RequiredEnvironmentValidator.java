package com.aisqlanalyst.config;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fail-fast startup validation of the Required_Environment_Variables (Requirements 11.1, 10.1).
 *
 * <p>This component reads the Spring {@link Environment} as the application context is initialized
 * and refuses to start when a required variable is absent or blank. Reading from the
 * {@code Environment} (rather than raw {@code System.getenv}) means values supplied via
 * {@code application.yml} placeholders, JVM system properties, or {@code @TestPropertySource} are
 * all visible and resolve consistently.
 *
 * <p><strong>Always required:</strong> {@code DB_URL}, {@code DB_RW_USER}, {@code DB_RW_PASSWORD},
 * {@code DB_RO_USER}, {@code DB_RO_PASSWORD}, {@code FRONTEND_ORIGIN}.
 *
 * <p><strong>Conditionally required</strong> (only when the selected provider is not {@code mock}):
 * {@code LLM_API_KEY}, {@code LLM_BASE_URL}, {@code LLM_MODEL}. The provider is read from
 * {@code LLM_PROVIDER} and defaults to {@code http} (matching {@code application.yml}). The
 * comparison against {@code mock} is case-insensitive and ignores surrounding whitespace, so
 * {@code mock}, {@code MOCK}, and {@code " mock "} all disable the provider-secret requirement
 * (Change 11). Any other value (including the {@code http} default) keeps the three provider
 * secrets required.
 *
 * <p>When one or more required variables are missing, <em>all</em> of them are collected and named
 * in a single {@link MissingEnvironmentVariableException}; validation does not stop at the first
 * gap. The message names variables by their environment-variable name only and never echoes any
 * value, so secrets cannot leak into logs (Requirement 11.2).
 *
 * <p><strong>Mechanism.</strong> Validation runs from the constructor of this {@code @Configuration}
 * bean, which Spring instantiates during context refresh. Throwing here aborts the refresh and
 * prevents the application from starting — the fail-fast guarantee of Requirement 11.1 — while the
 * injected {@link Environment} is already fully populated. Scope note: this task only validates
 * presence; datasource beans (task 3) and the Llm_Client (task 8) are implemented later.
 */
@Configuration
public class RequiredEnvironmentValidator {

    private static final Logger log = LoggerFactory.getLogger(RequiredEnvironmentValidator.class);

    /** Environment variables that must always be present and non-blank. */
    static final List<String> ALWAYS_REQUIRED = List.of(
            "DB_URL",
            "DB_RW_USER",
            "DB_RW_PASSWORD",
            "DB_RO_USER",
            "DB_RO_PASSWORD",
            "FRONTEND_ORIGIN");

    /** Provider secrets required only when {@code LLM_PROVIDER != mock}. */
    static final List<String> LLM_REQUIRED_WHEN_NOT_MOCK = List.of(
            "LLM_API_KEY",
            "LLM_BASE_URL",
            "LLM_MODEL");

    /** Name of the variable selecting the LLM provider. */
    static final String LLM_PROVIDER = "LLM_PROVIDER";

    /** Provider value that relaxes the provider-secret requirement (case-insensitive). */
    static final String MOCK_PROVIDER = "mock";

    public RequiredEnvironmentValidator(Environment environment) {
        validate(environment);
    }

    /**
     * Validates presence of every required variable, collecting all missing names.
     *
     * @param environment the fully-populated Spring environment to read from
     * @throws MissingEnvironmentVariableException if any required variable is absent or blank
     */
    void validate(Environment environment) {
        List<String> missing = new ArrayList<>();

        for (String name : ALWAYS_REQUIRED) {
            if (isBlank(environment.getProperty(name))) {
                missing.add(name);
            }
        }

        if (!isMockProvider(environment)) {
            for (String name : LLM_REQUIRED_WHEN_NOT_MOCK) {
                if (isBlank(environment.getProperty(name))) {
                    missing.add(name);
                }
            }
        }

        if (!missing.isEmpty()) {
            String message = "Missing required environment variable(s): " + String.join(", ", missing)
                    + ". Set these before starting the application.";
            // Log the names (never the values) so the failure is visible in logs (11.1, 11.2).
            log.error(message);
            throw new MissingEnvironmentVariableException(message, missing);
        }
    }

    /**
     * Returns {@code true} when the configured provider is {@code mock}, ignoring case and
     * surrounding whitespace. An absent provider resolves to the {@code http} default, which is
     * not mock, so provider secrets remain required by default.
     */
    private static boolean isMockProvider(Environment environment) {
        String provider = environment.getProperty(LLM_PROVIDER, "http");
        return MOCK_PROVIDER.equalsIgnoreCase(provider.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
