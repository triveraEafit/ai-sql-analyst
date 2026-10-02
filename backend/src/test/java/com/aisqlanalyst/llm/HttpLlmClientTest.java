package com.aisqlanalyst.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aisqlanalyst.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

/**
 * Failure-mapping, happy-path and request-shape tests for {@link HttpLlmClient} against the
 * OpenAI-compatible chat-completions API (Requirements 8.1&ndash;8.7).
 *
 * <h2>Test approach &mdash; local stub server, no real network</h2>
 * <p>The client builds its own {@link org.springframework.web.client.RestClient} internally (to
 * apply real connect/read timeouts), so instead of {@code MockRestServiceServer} we stand up a
 * lightweight {@link HttpServer} on {@code 127.0.0.1} on a random port, point
 * {@link AppProperties.Llm#baseUrl()} at its root, and the client POSTs to
 * {@code /chat/completions}. The server is stopped in {@link #tearDown()}.
 *
 * <p>Chat-completions responses are shaped as
 * {@code {"choices":[{"message":{"content": <model answer>}}]}} where the model answer is the JSON
 * object {@code {"sql": "...", "explanation": "..."}} the client parses.
 */
class HttpLlmClientTest {

    private static final String API_KEY = "super-secret-key-abc123";
    private static final String PATH = "/chat/completions";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
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

    /** Wrap a model answer string in the chat-completions response envelope. */
    private static String chatResponse(String content) {
        try {
            String escaped = MAPPER.writeValueAsString(content); // JSON-encodes + quotes the string
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + escaped + "}}]}";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Install a handler that returns the given status and raw body for every request. */
    private void respondWith(int status, String body) {
        server.createContext(PATH, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    /** Install a 200 handler whose model answer (choices[0].message.content) is the given string. */
    private void respondWithContent(String content) {
        respondWith(200, chatResponse(content));
    }

    @Test
    void happyPath_stripsFencesAndTrailingSemicolon() {
        // The model answer is JSON wrapped in ```json fences, with a trailing semicolon in sql.
        respondWithContent("```json\n{\"sql\": \"SELECT 1;\", \"explanation\": \"ok\"}\n```");

        LlmResult result = client().generateSql("count things", "schema");

        assertThat(result.sql()).isEqualTo("SELECT 1");
        assertThat(result.explanation()).isEqualTo("ok");
    }

    @Test
    void happyPath_plainJsonNoFences() {
        respondWithContent("{\"sql\": \"SELECT 2\", \"explanation\": \"two\"}");

        LlmResult result = client().generateSql("q", "schema");

        assertThat(result.sql()).isEqualTo("SELECT 2");
        assertThat(result.explanation()).isEqualTo("two");
    }

    @Test
    void multilineSql_collapsesNewlinesToSingleSpace_doesNotConcatenateTokens() {
        respondWithContent(
                "{\"sql\": \"SELECT c.name, SUM(x) AS total_spend\\nFROM customers c\\nGROUP BY c.name\","
                        + " \"explanation\": \"spend\"}");

        LlmResult result = client().generateSql("q", "schema");

        assertThat(result.sql())
                .isEqualTo("SELECT c.name, SUM(x) AS total_spend FROM customers c GROUP BY c.name");
        assertThat(result.sql()).doesNotContain("total_spendFROM");
    }

    @Test
    void rawNewlineInsideSqlString_isToleratedAndCollapsed() {
        // Some models emit an actual (unescaped) newline control char inside the JSON string value.
        // Standard JSON forbids that, but the client's mapper enables ALLOW_UNESCAPED_CONTROL_CHARS,
        // so it still parses; the sql is then whitespace-collapsed to a single-spaced query.
        // The response body is built by hand so the newline stays raw (not escaped by the mapper).
        String content = "{\"sql\": \"SELECT a\n FROM customers\", \"explanation\": \"ok\"}";
        String envelope = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + content.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}}]}";
        // Note: we escape quotes/backslashes for the OUTER JSON string, but deliberately leave the
        // inner newline raw so both the envelope and the content carry an unescaped control char.
        respondWith(200, envelope);

        LlmResult result = client().generateSql("q", "schema");

        assertThat(result.sql()).isEqualTo("SELECT a FROM customers");
        assertThat(result.explanation()).isEqualTo("ok");
    }

    @Test
    void requestBody_hasModelTemperatureZeroSystemAndUserMessages_apiKeyOnlyInHeader() throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext(PATH, exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = chatResponse("{\"sql\": \"SELECT 1\", \"explanation\": \"ok\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        client().generateSql("How many customers?", "SCHEMA_CONTEXT_HERE");

        JsonNode body = MAPPER.readTree(capturedBody.get());
        assertThat(body.get("model").asText()).isEqualTo("test-model");
        assertThat(body.get("temperature").asInt()).isZero();

        JsonNode messages = body.get("messages");
        assertThat(messages.isArray()).isTrue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).get("content").asText()).isNotBlank();
        assertThat(messages.get(1).get("role").asText()).isEqualTo("user");
        assertThat(messages.get(1).get("content").asText())
                .contains("SCHEMA_CONTEXT_HERE")
                .contains("How many customers?");

        // The API key appears ONLY in the Authorization header, never in the request body.
        assertThat(capturedAuth.get()).isEqualTo("Bearer " + API_KEY);
        assertThat(capturedBody.get()).doesNotContain(API_KEY);
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
    void invalidResponseJson_mapsToParse() {
        respondWith(200, "not json at all {");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class)
                .satisfies(e -> assertSafeMessage(e.getMessage()));
    }

    @Test
    void missingChoicesContent_mapsToParse() {
        // Valid JSON envelope but no choices -> no model answer.
        respondWith(200, "{\"choices\":[]}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void contentNotJson_mapsToParse() {
        // The model answer content is not the expected JSON object.
        respondWithContent("I cannot help with that.");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void missingSqlField_mapsToParse() {
        respondWithContent("{\"explanation\": \"only explanation\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void missingExplanationField_mapsToParse() {
        respondWithContent("{\"sql\": \"SELECT 1\"}");

        assertThatThrownBy(() -> client().generateSql("q", "schema"))
                .isInstanceOf(LlmParseException.class);
    }

    @Test
    void readTimeout_mapsToTimeout() {
        server.createContext(PATH, exchange -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = chatResponse("{\"sql\":\"SELECT 1\",\"explanation\":\"ok\"}")
                    .getBytes(StandardCharsets.UTF_8);
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
     * Assert a thrown message never leaks the api key, base url, host, model, or raw provider body.
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