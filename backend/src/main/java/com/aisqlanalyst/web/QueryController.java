package com.aisqlanalyst.web;

import com.aisqlanalyst.dto.InteractionSummary;
import com.aisqlanalyst.dto.QueryRequest;
import com.aisqlanalyst.dto.QueryResponse;
import com.aisqlanalyst.persistence.InteractionRepository;
import com.aisqlanalyst.service.QueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST entry point for the AI SQL Analyst (Requirements 1.1, 7.1, 12.1).
 *
 * <p>Deliberately thin: it performs request-shape validation ({@code @Valid} on the
 * {@link QueryRequest}, which triggers the {@code @NotBlank} check) and delegates all business
 * logic to the {@link QueryService} and {@link InteractionRepository}. It holds no orchestration,
 * SQL, or error-mapping logic itself &mdash; exceptions thrown by the collaborators are translated
 * to safe HTTP responses by {@link GlobalExceptionHandler} (task 12.2), preserving the
 * controller/service separation required by Requirement 12.1.
 *
 * <ul>
 *   <li>{@code POST /api/query} &rarr; answer a natural-language question (Requirement 1.1).</li>
 *   <li>{@code GET /api/history} &rarr; the most recent interactions, newest-first (Requirement 7.1).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class QueryController {

    /** Maximum number of history rows returned by {@code GET /api/history} (Requirement 7.1). */
    private static final int HISTORY_LIMIT = 50;

    private final QueryService queryService;
    private final InteractionRepository interactionRepository;

    /**
     * Constructor injection of the two collaborators the controller delegates to.
     *
     * @param queryService          orchestrates generate &rarr; validate &rarr; execute &rarr; persist.
     * @param interactionRepository supplies the recent interaction history.
     */
    public QueryController(QueryService queryService, InteractionRepository interactionRepository) {
        this.queryService = queryService;
        this.interactionRepository = interactionRepository;
    }

    /**
     * Answer a natural-language question against the allowlisted schema (Requirement 1.1).
     *
     * <p>{@code @Valid} enforces {@code @NotBlank} on {@link QueryRequest#question()}; a blank or
     * missing question raises {@code MethodArgumentNotValidException}, mapped to HTTP 400 by the
     * advice (Requirement 1.3). Any failure from the service is mapped to its documented status by
     * {@link GlobalExceptionHandler}.
     *
     * @param request the validated request body carrying the user's question.
     * @return the query result (table, SQL, explanation) with HTTP 200 on success.
     */
    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        return queryService.handle(request.question());
    }

    /**
     * Return the most recent interactions, newest-first and capped at {@value #HISTORY_LIMIT}
     * (Requirement 7.1).
     *
     * @return the recent interaction summaries; an empty list when there are none.
     */
    @GetMapping("/history")
    public List<InteractionSummary> history() {
        return interactionRepository.findRecent(HISTORY_LIMIT);
    }
}
