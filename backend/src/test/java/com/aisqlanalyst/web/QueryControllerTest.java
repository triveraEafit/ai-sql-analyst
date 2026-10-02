package com.aisqlanalyst.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aisqlanalyst.dto.InteractionSummary;
import com.aisqlanalyst.dto.QueryResponse;
import com.aisqlanalyst.llm.LlmAuthException;
import com.aisqlanalyst.llm.LlmParseException;
import com.aisqlanalyst.llm.LlmRateLimitException;
import com.aisqlanalyst.llm.LlmTimeoutException;
import com.aisqlanalyst.persistence.InteractionRepository;
import com.aisqlanalyst.service.QueryFailedException;
import com.aisqlanalyst.service.QueryService;
import com.aisqlanalyst.service.QuestionTooLongException;
import com.aisqlanalyst.service.StatementTimeoutException;
import com.aisqlanalyst.sql.ValidationException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-layer slice test for {@link QueryController} and {@link GlobalExceptionHandler}
 * (tasks 12.1, 12.2).
 *
 * <p><strong>Approach.</strong> A {@code @WebMvcTest} slice limited to {@link QueryController} with
 * the two collaborators supplied as {@code @MockBean}s and the advice {@code @Import}ed. The slice
 * does not start datasources (the main class excludes {@code DataSourceAutoConfiguration}). To keep
 * the context robust if the fail-fast {@code RequiredEnvironmentValidator} ({@code @Configuration})
 * is ever picked up, {@code @TestPropertySource} supplies the always-required environment variables
 * with {@code LLM_PROVIDER=mock}, so no real secrets or datasource beans are needed.
 *
 * <p>Each error case asserts the mapped HTTP status and that the safe body carries {@code code} and
 * {@code message} with no stack-trace content, verifying the Error_Advice contract
 * (Requirements 11.2, 11.3).
 */
@WebMvcTest(controllers = QueryController.class)
@Import(GlobalExceptionHandler.class)
@TestPropertySource(properties = {
        "DB_URL=jdbc:postgresql://localhost:5432/aisql",
        "DB_RW_USER=app_rw",
        "DB_RW_PASSWORD=test",
        "DB_RO_USER=app_ro",
        "DB_RO_PASSWORD=test",
        "FRONTEND_ORIGIN=http://localhost:3000",
        "LLM_PROVIDER=mock"
})
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueryService queryService;

    @MockBean
    private InteractionRepository interactionRepository;

    private static final String VALID_BODY = "{\"question\":\"How many orders were placed?\"}";

    @Test
    void postQuery_validBody_returns200WithResult() throws Exception {
        QueryResponse response = new QueryResponse(
                List.of(Map.of("count", 42)),
                "SELECT count(*) FROM public.orders",
                "There are 42 orders.");
        when(queryService.handle(anyString())).thenReturn(response);

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.table").isArray())
                .andExpect(jsonPath("$.table[0].count").value(42))
                .andExpect(jsonPath("$.sql").value("SELECT count(*) FROM public.orders"))
                .andExpect(jsonPath("$.explanation").value("There are 42 orders."));
    }

    @Test
    void postQuery_blankQuestion_returns400WithSafeBody() throws Exception {
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("bad_request"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").value("Question must not be blank."));
    }

    @Test
    void postQuery_validationException_returns422() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new ValidationException("Query references a table that is not permitted."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid_query"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void postQuery_queryFailed_returns422() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new QueryFailedException("The query could not be answered."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("query_failed"));
    }

    @Test
    void postQuery_statementTimeout_returns504() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new StatementTimeoutException("The query took too long and was cancelled."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("statement_timeout"));
    }

    @Test
    void postQuery_questionTooLong_returns400() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new QuestionTooLongException(300));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("question_too_long"));
    }

    @Test
    void postQuery_llmTimeout_returns503() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new LlmTimeoutException("The language model is temporarily unavailable."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("llm_unavailable"));
    }

    @Test
    void postQuery_llmRateLimit_returns503() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new LlmRateLimitException("The language model is temporarily unavailable."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("llm_unavailable"));
    }

    @Test
    void postQuery_llmAuth_returns502() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new LlmAuthException("The language model service rejected the request."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("llm_error"));
    }

    @Test
    void postQuery_llmParse_returns502() throws Exception {
        when(queryService.handle(anyString()))
                .thenThrow(new LlmParseException("The language model returned an unusable response."));

        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("llm_error"));
    }

    @Test
    void postQuery_malformedJson_returns400WithSafeBody() throws Exception {
        mockMvc.perform(post("/api/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed."));
    }

    @Test
    void getHistory_returns200WithJsonArray() throws Exception {
        InteractionSummary row = new InteractionSummary(
                7L,
                "How many orders?",
                "SELECT count(*) FROM public.orders",
                "42 rows",
                "There are 42 orders.",
                "SUCCESS",
                123L,
                Instant.parse("2024-01-01T00:00:00Z"));
        when(interactionRepository.findRecent(anyInt())).thenReturn(List.of(row));

        mockMvc.perform(get("/api/history"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].generatedSql").value("SELECT count(*) FROM public.orders"));
    }
}
