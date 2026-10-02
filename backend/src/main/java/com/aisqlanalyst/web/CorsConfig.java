package com.aisqlanalyst.web;

import com.aisqlanalyst.config.AppProperties;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Registers the dedicated {@link CorsFilter} at {@link Ordered#HIGHEST_PRECEDENCE} (Change 5,
 * Requirement 10.6).
 *
 * <p><strong>Why CORS runs first.</strong> This filter is registered at the highest possible
 * precedence so it executes <em>before</em> the {@link RateLimitFilter}. Running CORS first
 * guarantees that even a {@code 429 Too Many Requests} (or any {@code 4xx}) produced downstream
 * still carries the {@code Access-Control-Allow-*} headers. Without that ordering a browser would
 * surface an opaque CORS error instead of letting the frontend read the real status and body.
 *
 * <p>The filter permits exactly the single configured {@code Frontend_Origin}
 * ({@link AppProperties.Cors#frontendOrigin()}), the methods the API actually uses
 * ({@code GET}, {@code POST}, {@code OPTIONS}), the {@code Content-Type} header, and handles the
 * CORS preflight {@code OPTIONS} request. {@code allowCredentials} is enabled; because a single
 * explicit origin (not {@code *}) is configured, credentialed CORS is valid.
 */
@Configuration
public class CorsConfig {

    /** CORS config applies to the whole API surface. */
    private static final String CORS_PATH_PATTERN = "/**";

    /**
     * Build the CORS filter registration pinned to the highest precedence.
     *
     * @param appProperties supplies the single permitted {@code Frontend_Origin}.
     * @return a registration that installs {@link CorsFilter} ahead of all other filters.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(AppProperties appProperties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(appProperties.cors().frontendOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(CORS_PATH_PATTERN, configuration);

        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        // Highest precedence: CORS must run before the RateLimitFilter so a 429/4xx still
        // carries CORS headers (Change 5).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/api/*");
        registration.setName("corsFilter");
        return registration;
    }
}
