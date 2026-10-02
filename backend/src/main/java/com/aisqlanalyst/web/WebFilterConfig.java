package com.aisqlanalyst.web;

import com.aisqlanalyst.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the {@link RateLimitFilter} below the {@link CorsFilter} precedence (Requirements 9.1,
 * 9.2).
 *
 * <p>The order is {@link Ordered#HIGHEST_PRECEDENCE} {@code + 10}, i.e. just <em>after</em> the
 * CORS filter (which sits at {@link Ordered#HIGHEST_PRECEDENCE}; see {@link CorsConfig}). That gap
 * guarantees CORS runs first, so a {@code 429} emitted by the rate limiter still carries CORS
 * headers (Change 5).
 *
 * <p>The registration is scoped to the {@code /api/query} URL pattern; the filter additionally
 * enforces {@code POST}-only inside {@link RateLimitFilter#doFilter}, so {@code GET /api/history}
 * and other endpoints are never rate-limited.
 */
@Configuration
public class WebFilterConfig {

    /** Keep the rate limiter just behind CORS so CORS headers are always applied first. */
    private static final int RATE_LIMIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    /**
     * Build the rate-limit filter registration.
     *
     * @param appProperties supplies the per-minute limit and trust-proxy flag.
     * @param objectMapper  the shared Jackson mapper (auto-configured by Spring Boot) used to
     *                      serialize the 429 body.
     * @return a registration that installs {@link RateLimitFilter} scoped to {@code /api/query}.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            AppProperties appProperties, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>(new RateLimitFilter(appProperties, objectMapper));
        registration.setOrder(RATE_LIMIT_FILTER_ORDER);
        registration.addUrlPatterns("/api/query");
        registration.setName("rateLimitFilter");
        return registration;
    }
}
