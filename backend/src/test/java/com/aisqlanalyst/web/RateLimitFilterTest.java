package com.aisqlanalyst.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.aisqlanalyst.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Focused unit tests for {@link RateLimitFilter} using Spring's servlet mocks (no container).
 *
 * <p>The filter is built directly with a small limit (3) via a hand-built {@link AppProperties},
 * so the tests are fast and deterministic: three allowed requests then a blocked one.
 * (Requirements 9.1, 9.2)
 */
class RateLimitFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static AppProperties propsWithLimit(int limit, boolean trustProxy) {
        return new AppProperties(
                300,
                100,
                Duration.ofSeconds(5),
                new AppProperties.Llm("mock", Duration.ofSeconds(20), "", "", ""),
                new AppProperties.RateLimit(limit, trustProxy),
                new AppProperties.Cors("http://localhost:3000"));
    }

    private static MockHttpServletRequest postQuery(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/query");
        request.setServletPath("/api/query");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    private int status(RateLimitFilter filter, MockHttpServletRequest request)
            throws IOException, ServletException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void allowsUpToLimitThenRejectsWith429() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(3, false), MAPPER);

        assertThat(status(filter, postQuery("10.0.0.1"))).isEqualTo(200);
        assertThat(status(filter, postQuery("10.0.0.1"))).isEqualTo(200);
        assertThat(status(filter, postQuery("10.0.0.1"))).isEqualTo(200);

        // The (limit+1)th request within the window is blocked.
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(postQuery("10.0.0.1"), blocked, new MockFilterChain());

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentType()).contains("application/json");
        assertThat(blocked.getContentAsString()).contains("rate_limited");
    }

    @Test
    void countsPerIpIndependently() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, false), MAPPER);

        assertThat(status(filter, postQuery("1.1.1.1"))).isEqualTo(200);
        // Different IP has its own window; still allowed.
        assertThat(status(filter, postQuery("2.2.2.2"))).isEqualTo(200);
        // First IP has now exceeded its limit of 1.
        assertThat(status(filter, postQuery("1.1.1.1"))).isEqualTo(429);
    }

    @Test
    void doesNotLimitGetHistory() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, false), MAPPER);

        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/history");
            request.setServletPath("/api/history");
            request.setRemoteAddr("9.9.9.9");
            assertThat(status(filter, request)).isEqualTo(200);
        }
    }

    @Test
    void doesNotLimitGetToQueryPath() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, false), MAPPER);

        // Same path but GET method is never limited.
        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/query");
        first.setServletPath("/api/query");
        first.setRemoteAddr("8.8.8.8");
        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/query");
        second.setServletPath("/api/query");
        second.setRemoteAddr("8.8.8.8");

        assertThat(status(filter, first)).isEqualTo(200);
        assertThat(status(filter, second)).isEqualTo(200);
    }

    @Test
    void trustProxyOffIgnoresForwardedHeader() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, false), MAPPER);

        MockHttpServletRequest first = postQuery("172.16.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.5");
        MockHttpServletRequest second = postQuery("172.16.0.1");
        second.addHeader("X-Forwarded-For", "203.0.113.9");

        // Both resolve to the same remote address because the header is ignored.
        assertThat(status(filter, first)).isEqualTo(200);
        assertThat(status(filter, second)).isEqualTo(429);
    }

    @Test
    void trustProxyOnUsesFirstForwardedIp() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, true), MAPPER);

        // Same remote socket, but two distinct forwarded client IPs -> two independent buckets.
        MockHttpServletRequest clientA = postQuery("172.16.0.1");
        clientA.addHeader("X-Forwarded-For", "203.0.113.5, 172.16.0.1");
        MockHttpServletRequest clientB = postQuery("172.16.0.1");
        clientB.addHeader("X-Forwarded-For", "203.0.113.9, 172.16.0.1");
        // Second request from client A exceeds its own limit of 1.
        MockHttpServletRequest clientAAgain = postQuery("172.16.0.1");
        clientAAgain.addHeader("X-Forwarded-For", "203.0.113.5");

        assertThat(status(filter, clientA)).isEqualTo(200);
        assertThat(status(filter, clientB)).isEqualTo(200);
        assertThat(status(filter, clientAAgain)).isEqualTo(429);
    }

    @Test
    void trustProxyOnFallsBackToRemoteAddrWhenHeaderBlank() throws IOException, ServletException {
        RateLimitFilter filter = new RateLimitFilter(propsWithLimit(1, true), MAPPER);

        MockHttpServletRequest first = postQuery("172.16.0.9");
        first.addHeader("X-Forwarded-For", "   ");
        MockHttpServletRequest second = postQuery("172.16.0.9");

        // Blank header -> both fall back to the same remote address.
        assertThat(status(filter, first)).isEqualTo(200);
        assertThat(status(filter, second)).isEqualTo(429);
    }
}
