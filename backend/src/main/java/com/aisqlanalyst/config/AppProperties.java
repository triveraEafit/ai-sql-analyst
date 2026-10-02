package com.aisqlanalyst.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Immutable binding for the {@code app.*} application tunables (Requirement 10, 9.2).
 *
 * <p>This is a constructor-bound {@code @ConfigurationProperties} record. On Spring Boot 3 a
 * record with a single canonical constructor is bound by that constructor automatically, so no
 * explicit {@code @ConstructorBinding} is required. Defaults declared in {@code application.yml}
 * supply values when a property is absent; the {@link DefaultValue} annotations here provide the
 * same defaults so the type binds correctly even if the YAML entry is removed.
 *
 * <p>{@code @Validated} enables Jakarta Bean Validation on the bound values. Validation runs at
 * startup when the properties are bound, so a misconfigured deployment fails fast rather than
 * surfacing a confusing error later in the request path.
 *
 * <p>Scope note: this class only <em>defines and registers</em> the binding. Fail-fast env-var
 * validation (task 1.3), datasource beans (task 3), and consumption of these values in business
 * logic are implemented in later tasks.
 *
 * @param maxQuestionLength Max_Question_Length in characters; default 300, must be positive (10.2).
 * @param enforcedLimit     Enforced_Limit result rows; default 100, must be positive (10.3).
 * @param statementTimeout  Statement_Timeout for generated SQL; default 5s (10.4).
 * @param llm               Nested LLM provider settings (10.5, provider selection).
 * @param rateLimit         Nested per-IP rate-limit settings (9.2).
 * @param cors              Nested CORS settings (10.6).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(

        @Positive
        @DefaultValue("300")
        int maxQuestionLength,

        @Positive
        @DefaultValue("100")
        int enforcedLimit,

        @NotNull
        @DefaultValue("5s")
        Duration statementTimeout,

        @Valid
        @NotNull
        @DefaultValue
        Llm llm,

        @Valid
        @NotNull
        @DefaultValue
        RateLimit rateLimit,

        @Valid
        @NotNull
        @DefaultValue
        Cors cors
) {

    /**
     * LLM provider settings, bound from {@code app.llm.*}.
     *
     * <p>{@code apiKey}, {@code baseUrl}, and {@code model} are intentionally not constrained here:
     * they are required only when {@code provider != mock}, which is enforced by the conditional
     * fail-fast startup validation in task 1.3. In {@code mock} mode they may legitimately be empty.
     *
     * @param provider provider selector; {@code http} (default) selects the real HttpLlmClient,
     *                 {@code mock} selects the MockLlmClient. Maps to {@code app.llm.provider}.
     * @param timeout  Llm_Timeout for a single provider call; configurable, default 20s (10.5).
     * @param apiKey   provider API key; required only when {@code provider != mock}.
     * @param baseUrl  provider base URL; required only when {@code provider != mock}.
     * @param model    provider model id; required only when {@code provider != mock}.
     */
    public record Llm(

            @NotBlank
            @DefaultValue("http")
            String provider,

            @NotNull
            @DefaultValue("20s")
            Duration timeout,

            @DefaultValue("")
            String apiKey,

            @DefaultValue("")
            String baseUrl,

            @DefaultValue("")
            String model
    ) {
    }

    /**
     * Per-IP rate-limiting settings, bound from {@code app.rate-limit.*} (Requirement 9).
     *
     * @param requestsPerMinute Rate_Limit per client IP per minute; default 30, must be positive (9.2).
     * @param trustProxy        whether to trust a forwarded header for the real client IP;
     *                          default {@code false} / OFF (Change 12).
     */
    public record RateLimit(

            @Positive
            @DefaultValue("30")
            int requestsPerMinute,

            @DefaultValue("false")
            boolean trustProxy
    ) {
    }

    /**
     * CORS settings, bound from {@code app.cors.*} (Requirement 10.6).
     *
     * @param frontendOrigin the single Frontend_Origin permitted by CORS; required, must be present
     *                       and non-blank (resolved from {@code FRONTEND_ORIGIN}).
     */
    public record Cors(

            @NotBlank
            String frontendOrigin
    ) {
    }
}
