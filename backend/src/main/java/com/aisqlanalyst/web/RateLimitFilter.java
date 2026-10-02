package com.aisqlanalyst.web;

import com.aisqlanalyst.config.AppProperties;
import com.aisqlanalyst.dto.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP, in-memory, fixed-window rate limiter for {@code POST /api/query} (Requirements 9.1, 9.2).
 *
 * <p><strong>Scope.</strong> Only {@code POST /api/query} is rate-limited (Requirement 9.1). Every
 * other method or path &mdash; including {@code GET /api/history} and the CORS preflight
 * {@code OPTIONS} &mdash; is forwarded immediately without touching a counter. The owning
 * {@link WebFilterConfig} also scopes the registration to the {@code /api/query} URL pattern; the
 * explicit method/path check here is the authoritative guard regardless of how the servlet
 * container maps the pattern.
 *
 * <p><strong>Ordering.</strong> This filter is registered at a lower precedence than the
 * {@link CorsFilter} (see {@link WebFilterConfig}), so CORS runs first and a {@code 429} response
 * already carries the {@code Access-Control-Allow-*} headers (Change 5). This filter does not add
 * or strip CORS headers.
 *
 * <p><strong>Algorithm.</strong> A {@link ConcurrentHashMap} keyed by client IP holds a
 * {@link Window} (window-start epoch-ms + an {@link AtomicInteger} count). The window length is
 * fixed at 60&nbsp;seconds and the limit defaults to
 * {@link AppProperties.RateLimit#requestsPerMinute()} (default 30). Each request atomically
 * read-modify-writes the window via {@link ConcurrentHashMap#compute}: if the current window has
 * expired ({@code now - windowStart >= 60_000ms}) it resets to a fresh window with count 1;
 * otherwise it increments. The request is blocked once the post-increment count exceeds the limit,
 * so the (limit+1)th request within one window from a single IP yields {@code 429}.
 *
 * <p><strong>Client IP / trust-proxy (Change 12).</strong> With {@code trust-proxy} OFF (default)
 * the client IP is the socket remote address ({@link HttpServletRequest#getRemoteAddr()}). With
 * {@code trust-proxy} ON the limiter reads the real client IP from the first entry of the
 * comma-separated {@code X-Forwarded-For} header, falling back to the remote address when that
 * header is absent or blank.
 */
public class RateLimitFilter extends HttpFilter {

    /** Fixed window length in milliseconds (60 seconds). */
    private static final long WINDOW_MILLIS = 60_000L;

    /** HTTP 429 Too Many Requests (not exposed as a servlet SC_* constant). */
    private static final int SC_TOO_MANY_REQUESTS = 429;

    /** The one method this filter limits. */
    private static final String LIMITED_METHOD = "POST";

    /** The one path this filter limits (matched against the servlet path / request URI). */
    private static final String LIMITED_PATH = "/api/query";

    private final int requestsPerMinute;
    private final boolean trustProxy;
    private final ObjectMapper objectMapper;

    /** Per-IP windows. Keyed by resolved client IP. */
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * @param appProperties supplies the configured limit and the trust-proxy flag.
     * @param objectMapper  shared mapper used to serialize the {@link ErrorResponse} 429 body.
     */
    public RateLimitFilter(AppProperties appProperties, ObjectMapper objectMapper) {
        this.requestsPerMinute = appProperties.rateLimit().requestsPerMinute();
        this.trustProxy = appProperties.rateLimit().trustProxy();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        // Only POST /api/query is limited; everything else (incl. GET /api/history, OPTIONS
        // preflight) passes straight through. (Requirement 9.1)
        if (!isLimited(request)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        if (allow(clientIp)) {
            chain.doFilter(request, response);
        } else {
            reject(response);
        }
    }

    /** True only for {@code POST} to the {@code /api/query} path (context-path aware). */
    private boolean isLimited(HttpServletRequest request) {
        if (!LIMITED_METHOD.equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        // getServletPath() excludes the context path, so it compares cleanly to "/api/query"
        // regardless of where the app is deployed.
        return LIMITED_PATH.equals(request.getServletPath());
    }

    /**
     * Atomically record a request for the given IP against its fixed window and decide whether it
     * is allowed. Resets the window when it has expired, otherwise increments the counter.
     *
     * @return {@code true} if the request is within the limit, {@code false} if it must be blocked.
     */
    private boolean allow(String clientIp) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(clientIp, (ip, existing) -> {
            if (existing == null || now - existing.startMillis >= WINDOW_MILLIS) {
                // Fresh window: start now, this request is the first.
                return new Window(now);
            }
            // Same window: count this request.
            existing.count.incrementAndGet();
            return existing;
        });
        return window.count.get() <= requestsPerMinute;
    }

    /**
     * Resolve the client IP honouring the trust-proxy flag (Change 12). With the flag OFF the
     * socket remote address is used. With it ON the first {@code X-Forwarded-For} entry is used,
     * falling back to the remote address when the header is missing or blank.
     */
    private String resolveClientIp(HttpServletRequest request) {
        if (trustProxy) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                // X-Forwarded-For: client, proxy1, proxy2 -> the leftmost entry is the client.
                String first = forwarded.split(",", 2)[0].trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }

    /** Write the 429 Error_Advice body (code {@code rate_limited}) as JSON. */
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(SC_TOO_MANY_REQUESTS);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ErrorResponse body =
                new ErrorResponse("rate_limited", "Too many requests. Please slow down.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /**
     * One IP's fixed-window state: the epoch-ms the window started and the number of requests
     * counted in it. The count is an {@link AtomicInteger} so increments inside the map's atomic
     * {@code compute} remain correct.
     */
    private static final class Window {
        private final long startMillis;
        private final AtomicInteger count;

        private Window(long startMillis) {
            this.startMillis = startMillis;
            this.count = new AtomicInteger(1);
        }
    }
}
