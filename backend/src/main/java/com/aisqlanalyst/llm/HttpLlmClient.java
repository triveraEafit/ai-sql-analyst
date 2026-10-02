package com.aisqlanalyst.llm;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.aisqlanalyst.config.AppProperties;
import com.aisqlanalyst.sql.SqlValidator;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Real {@link LlmClient} that calls an OpenAI-compatible chat-completions endpoint (works for
 * OpenAI and Groq) and parses the model''s answer into an {@link LlmResult}
 * (design "Llm_Client" + Error Handling table, Requirements 8.1&ndash;8.7).
 *
 * <h2>Bean selection &mdash; exactly one {@link LlmClient} active</h2>
 * <p>The provider is bound as {@code app.llm.provider} (from {@code LLM_PROVIDER} via
 * {@code application.yml: app.llm.provider: ${LLM_PROVIDER:http}}). This bean is registered with
 * {@code @ConditionalOnProperty(prefix="app.llm", name="provider", havingValue="http",
 * matchIfMissing=true)} so it is active when the provider is {@code http} <em>or unset</em>, and is
 * <strong>not</strong> active when the provider is {@code mock}. {@link MockLlmClient} uses the
 * complementary condition {@code havingValue="mock"}, so exactly one {@link LlmClient} bean is
 * active in each mode: this HTTP client for {@code http}/unset, the mock for {@code mock}.
 *
 * <h2>Timeout approach (Llm_Timeout, Requirement 8.2 / 10.5)</h2>
 * <p>The configurable {@link AppProperties.Llm#timeout()} Duration is applied to the underlying
 * {@link ClientHttpRequestFactory} (connect + read timeouts). A slow or unreachable provider fails
 * with a {@code ResourceAccessException}/{@code RestClientException} wrapping a
 * {@link SocketTimeoutException}, mapped to {@link LlmTimeoutException}. The {@link RestClient} is
 * built once in the constructor and reused.
 *
 * <h2>Request / response contract (OpenAI-compatible)</h2>
 * <ul>
 *   <li><strong>Request:</strong> {@code POST {baseUrl}/chat/completions} with
 *       {@code Authorization: Bearer <apiKey>} and {@code Content-Type: application/json}. Body:
 *       {@code {"model": <model>, "temperature": 0, "messages": [{"role":"system","content": SYSTEM},
 *       {"role":"user","content": USER}]}}. The system prompt constrains the model to one read-only
 *       PostgreSQL SELECT over the provided schema, no semicolons / DML / DDL / schema-qualified
 *       names, only the injected Allowlisted_Functions, and a JSON-only answer. The user prompt is
 *       the schema context plus the question (plus the failed SQL and DB error on the retry).</li>
 *   <li><strong>Response:</strong> the model''s answer is read from
 *       {@code choices[0].message.content}; a missing/empty content maps to {@link LlmParseException}
 *       (502). That content is expected to be the JSON object {@code {"sql": "...",
 *       "explanation": "..."}} (optionally wrapped in Markdown code fences). It is cleaned (fences +
 *       whitespace stripped, internal whitespace collapsed, trailing semicolons removed) and parsed
 *       with Jackson into {@code {sql, explanation}}.</li>
 * </ul>
 *
 * <h2>Failure mapping (Error Handling table)</h2>
 * <ul>
 *   <li>read/connect timeout &rarr; {@link LlmTimeoutException} (8.2)</li>
 *   <li>HTTP 429 &rarr; {@link LlmRateLimitException} (8.3)</li>
 *   <li>HTTP 401/403 &rarr; {@link LlmAuthException} (8.4)</li>
 *   <li>any other non-2xx status &rarr; generic {@link LlmException}</li>
 *   <li>body/content not valid JSON &rarr; {@link LlmParseException} (8.5)</li>
 *   <li>missing/blank {@code sql} &rarr; {@link LlmParseException} (8.6)</li>
 *   <li>missing/blank {@code explanation} &rarr; {@link LlmParseException} (8.7)</li>
 * </ul>
 * Every thrown message is short and client-safe (no key, URL, or raw body). No SQL validation
 * happens here &mdash; the Query_Service calls the Sql_Validator later.
 */
@Component
@ConditionalOnProperty(prefix = "app.llm", name = "provider", havingValue = "http", matchIfMissing = true)
public class HttpLlmClient implements LlmClient {

    /** OpenAI-compatible chat-completions path, appended to the configured base URL. */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    /**
     * Production constructor: builds the {@link RestClient} from {@link AppProperties}, applying the
     * base URL, the {@code Authorization: Bearer} header, and connect/read timeouts from Llm_Timeout.
     */
    public HttpLlmClient(AppProperties properties) {
        this(properties, RestClient.builder());
    }

    /**
     * Testable constructor: lets a caller supply the {@link RestClient.Builder} (e.g. one bound to a
     * stub server) while applying all configuration from {@link AppProperties}.
     */
    public HttpLlmClient(AppProperties properties, RestClient.Builder builder) {
        AppProperties.Llm llm = properties.llm();
        this.model = llm.model();
        // Some models emit raw newlines inside the JSON string values they return; tolerate them
        // so the answer still parses (the sql value is whitespace-collapsed afterwards anyway).
        this.objectMapper = JsonMapper.builder()
                .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .build();
        this.systemPrompt = buildSystemPrompt();
        this.restClient = builder
                .baseUrl(llm.baseUrl())
                .requestFactory(requestFactory(llm.timeout()))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + llm.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static ClientHttpRequestFactory requestFactory(Duration timeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int millis = (int) Math.max(1, timeout.toMillis());
        factory.setConnectTimeout(millis);
        factory.setReadTimeout(millis);
        return factory;
    }

    @Override
    public LlmResult generateSql(String question, String schemaContext, String errorHint) {
        String userPrompt = buildUserPrompt(question, schemaContext, errorHint);
        String content = extractContent(post(userPrompt));
        return parse(content);
    }

    /**
     * Builds the system prompt. The Allowlisted_Functions list is injected from
     * {@link SqlValidator#allowlistedFunctions()} so the model is told exactly the functions the
     * validator will accept (single source of truth).
     */
    private static String buildSystemPrompt() {
        String functions = String.join(", ", SqlValidator.allowlistedFunctions());
        return "You translate questions into ONE PostgreSQL SELECT. "
                + "Use only the tables and columns in the schema provided. "
                + "Never use semicolons, INSERT/UPDATE/DELETE/DDL, or schema-qualified names. "
                + "Use only the functions in this Allowlisted_Functions list: " + functions + ". "
                + "Respond with ONLY a JSON object {\"sql\": \"...\", \"explanation\": \"...\"} "
                + "and nothing else.";
    }

    /** Schema context + question; on the retry path, also the failed SQL and the database error. */
    private static String buildUserPrompt(String question, String schemaContext, String errorHint) {
        StringBuilder sb = new StringBuilder();
        if (schemaContext != null && !schemaContext.isBlank()) {
            sb.append(schemaContext).append("\n\n");
        }
        sb.append("Question: ").append(question == null ? "" : question);
        if (errorHint != null && !errorHint.isBlank()) {
            sb.append("\n\nThe previous attempt failed. ").append(errorHint)
              .append("\nProduce a corrected query.");
        }
        return sb.toString();
    }

    /**
     * POSTs the chat-completions request and returns the raw response body as a String, mapping
     * transport/HTTP failures to the typed exceptions.
     */
    private String post(String userPrompt) {
        Map<String, Object> requestBody = Map.of(
                "model", model == null ? "" : model,
                "temperature", 0,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            return restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(status -> status.value() == 429,
                            (req, res) -> {
                                throw new LlmRateLimitException(
                                        "The AI provider is rate limiting requests. Please try again shortly.");
                            })
                    .onStatus(status -> status.value() == 401 || status.value() == 403,
                            (req, res) -> {
                                throw new LlmAuthException(
                                        "The AI provider rejected the request credentials.");
                            })
                    .onStatus(status -> status.isError(),
                            (req, res) -> {
                                throw new LlmException(
                                        "The AI provider returned an unexpected error.");
                            })
                    .body(String.class);
        } catch (LlmException e) {
            // Thrown by our own onStatus handlers; propagate unchanged.
            throw e;
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new LlmTimeoutException(
                        "The AI provider did not respond in time. Please try again.", e);
            }
            throw new LlmException("The AI provider could not be reached.", e);
        } catch (RestClientException e) {
            // A read timeout can also surface while extracting the body as a RestClientException.
            if (isTimeout(e)) {
                throw new LlmTimeoutException(
                        "The AI provider did not respond in time. Please try again.", e);
            }
            throw new LlmException("The AI provider returned an unexpected error.", e);
        }
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the model answer from {@code choices[0].message.content} of the chat-completions
     * response. A missing or empty content maps to {@link LlmParseException} (502).
     */
    private String extractContent(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw new LlmParseException("The AI provider returned an empty response.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new LlmParseException("The AI provider returned a response that could not be understood.", e);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmParseException("The AI response did not include an answer.");
        }
        JsonNode message = choices.get(0).get("message");
        JsonNode contentNode = message == null ? null : message.get("content");
        if (contentNode == null || contentNode.isNull() || !contentNode.isTextual()
                || contentNode.asText().isBlank()) {
            throw new LlmParseException("The AI response did not include an answer.");
        }
        return contentNode.asText();
    }

    /**
     * Cleans the model''s text answer (strips code fences + whitespace), parses it as JSON, and
     * extracts {@code sql} (whitespace collapsed, trailing semicolon stripped) and
     * {@code explanation}. Any failure maps to {@link LlmParseException}.
     */
    private LlmResult parse(String content) {
        String cleaned = stripCodeFences(content);

        JsonNode node;
        try {
            node = objectMapper.readTree(cleaned);
        } catch (Exception e) {
            throw new LlmParseException("The AI provider returned a response that could not be understood.", e);
        }

        JsonNode sqlNode = node.get("sql");
        if (sqlNode == null || sqlNode.isNull() || !sqlNode.isTextual() || sqlNode.asText().isBlank()) {
            throw new LlmParseException("The AI response did not include a query.");
        }
        JsonNode explanationNode = node.get("explanation");
        if (explanationNode == null || explanationNode.isNull()
                || !explanationNode.isTextual() || explanationNode.asText().isBlank()) {
            throw new LlmParseException("The AI response did not include an explanation.");
        }

        // Collapse any run of whitespace (incl. newlines) to a single space so tokens never get
        // concatenated (e.g. "total_spend\nFROM" -> "total_spend FROM"), then strip trailing ";".
        String sql = stripTrailingSemicolon(collapseWhitespace(sqlNode.asText()));
        String explanation = explanationNode.asText().trim();
        return new LlmResult(sql, explanation);
    }

    /**
     * Removes a single surrounding Markdown code fence (<code>```json ... ```</code> or
     * <code>``` ... ```</code>) and trims surrounding whitespace. If no fence is present the input
     * is returned trimmed.
     */
    static String stripCodeFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            } else {
                trimmed = trimmed.substring(3);
            }
            int closing = trimmed.lastIndexOf("```");
            if (closing >= 0) {
                trimmed = trimmed.substring(0, closing);
            }
        }
        return trimmed.strip();
    }

    /**
     * Replaces every run of whitespace (spaces, tabs, newlines) with a single space and trims the
     * ends, so adjacent SQL tokens on separate lines stay separated.
     */
    static String collapseWhitespace(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String stripTrailingSemicolon(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }
}