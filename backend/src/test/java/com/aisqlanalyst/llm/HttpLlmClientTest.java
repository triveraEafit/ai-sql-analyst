package com.aisqlanalyst.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aisqlanalyst.config.AppProperties;
import com.sun.net.httpserver.HttpServer;

/**
 * Failure-mapping and happy-path tests for {@link HttpLlmClient} (task 8.4, Requirements
 * 8.2&ndash;8.7, plus the 8.1 happy path).
 *
 * <h2>Test approach &mdash; local stub server, no real network</h2>
 * <p>These are fast component tests. The client builds its own {@link org.springframework.web.client.RestClient}
 * internally (so it can apply real connect/read timeouts to the request factory), which is why we do
 * <em>not</em> use {@code MockRestServiceServer}: that would bypass the real transport and could not
 * exercise the timeout path. Instead we stand up a lightweight {@link HttpServer} on
 * {@code 127.0.0.1} on an OS-assigned random port, point {@link AppProperties.Llm#baseUrl()} at it,
 * and construct {@link HttpLlmClient} directly with a hand-built {@link AppProperties}. No Spring
 * context and no real network dependency. The server is stopped in {@link #tearDown()}.
 *
 * <p>Each test installs a handler returning the status/body it needs. The timeout test configures a
 * very short Llm_Timeout (80ms) and makes the handler sleep longer (400ms).
 */
class HttpLlmClientTest {

    private static final String API_KEY = "super-secret-key-abc123";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/generate";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpLlmClient clientWithTimeout(Duration timeout) {
        AppProperties props = new AppProperties(
                300,
                100,
                Duration.ofSeconds(5),
                new AppProperties.Llm("http", timeout, API_KEY, baseUrl, "test-model"),
                new AppProperties.RateLimit(30, false),
                new AppProperties.Cors("http://localhost:3000"));
        return new HttpLlmClient(props);
    }

    private HttpLlmClient client() {
        return clientWithTimeout(Duration.ofSeconds(5));
    }

    /** Install a handler that returns the given status and body for every request. */
    private void respondWith(int status, String body) {
        server.createContext("/v1/generate", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    @Test
    void happyPath_stripsFencesAndTrailingSemicolon() {
        // Wrapped in ```json fences, with a trailing semicolon in the sql value.
        respondWith(200, "```json\n{\"sql\": \"SELECT 1;\", \"explanation\": \"ok\"}\n```");

        LlmResult result = client().generateSql("count things", "schema");

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(result.explanation()).isEqualTo("ok");
    }

    @Test
    void happyPath_plainJsonNoFences() {
        respondWith(200, "{\"sql\": \"SELECT 2\", \"explanation\": \"two\"}");

        LlmResult result = client().generateSql("q", "schema");

        assertThat(result.sql()).isEqualTo("SELECT 2");
        assertThat(result.explanation()).isEqualTo("two");
    }

    @Test
    void multilineSql_collapsesNewlinesToSingleSpace_doesNotConcatenateTokens() {
        // A pretty-printed model response with a newline between the select list and FROM must not
        // produce "total_spendFROM"; the newline becomes a single space.
        respondWith(200,
                "{\"sql\": \"SELECT c.name, SUM(x) AS total_spend\\nFROM customers c\\nGROUP BY c.name\","
                        + " \"explanation\": \"spend\"}");

        LlmResult result = client().generateSql("q", "schema");

        assertThat(result.sql())
                .isEqualTo("SELECT c.name, SUM(x) AS total_spend FROM customers c GROUP BY c.name");
        assertThat(result.sql()).doesNotContain("total_spendFROM");
    }

    @Test
    void http429_mapsToRateLimit() {
        respondWith(429, "{\"error\":\"slow down\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmRateLimitException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    @Test
    void http401_mapsToAuth() {
        respondWith(401, "{\"error\":\"bad key " + API_KEY + "\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmAuthException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    @Test
    void http403_mapsToAuth() {
        respondWith(403, "{\"error\":\"forbidden\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmAuthException.class);
    }

    @Test
    void http500_mapsToGenericLlmException() {
        respondWith(500, "boom");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    @Test
    void invalidJson_mapsToParse() {
        respondWith(200, "not json at all {");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    @Test
    void missingSqlField_mapsToParse() {
        respondWith(200, "{\"explanation\": \"only explanation\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void missingExplanationField_mapsToParse() {
        respondWith(200, "{\"sql\": \"SELECT 1\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void readTimeout_mapsToTimeout() {
        server.createContext("/v1/generate", exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = "{\"sql\":\"SELECT 1\",\"explanation\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpLlmClient client = clientWithTimeout(Duration.ofMillis(80));
        assertThatThrownBy(() -> client.generateSql("q", "schema"))
                .isInstanceOf(LlmTimeoutException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    /**
     * Assert a thrown message never leaks the api key, base url, host, model, or the raw provider
     * body. ("error" is a safe generic word and is not checked.)
     */
    private void assertSafeMessage(String message) {
        assertThat(message)
                .doesNotContain(API_KEY)
                .doesNotContain(baseUrl)
                .doesNotContain("127.0.0.1")
                .doesNotContain("test-model")
                .doesNotContain("slow down")
                .doesNotContain("boom")
                .doesNotContain("bad key");
    }
}
