package com.aistudy.server.search.controller;

import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * C-Search endpoint: one keyword search across every typed entity of a single
 * LearningSpace.
 *
 * <p>Visibility is enforced in SQL (see {@code mapper/SearchMapper.xml}), so a
 * client can never reach DRAFT or unreviewed content through this endpoint,
 * and the AI tutor inherits the same guarantees by reusing SearchService.
 *
 * <p>FAILURE MODE: 400 on an invalid {@code q}, {@code types}, {@code page} or
 * {@code size}; 404 when the space is absent or owned by another subject;
 * 401 when unauthenticated.
 */
@RestController
@RequestMapping(value = "/api/v1/spaces/{spaceId}/search", produces = MediaType.APPLICATION_JSON_VALUE)
@SecurityRequirement(name = "bearerAuth")
public class SearchController {

    private static final String TYPE_PATTERN = "^(SOURCE|CONTENT_BLOCK|KNOWLEDGE_POINT|QUESTION|WRONG_QUESTION|NOTE)(,(" +
            "SOURCE|CONTENT_BLOCK|KNOWLEDGE_POINT|QUESTION|WRONG_QUESTION|NOTE))*$";

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(
            summary = "Unified workspace keyword search",
            description = "Keyword search scoped to ONE LearningSpace. " +
                    "Search indexes safe user-visible text such as source title, " +
                    "content block text, knowledge point title/summary/content, " +
                    "question stem and wrong-question stems, plus the caller's own " +
                    "note titles and bodies. " +
                    "Only user-visible content is returned: SOURCE, CONTENT_BLOCK, " +
                    "KNOWLEDGE_POINT and QUESTION rows must be PUBLISHED, page and " +
                    "block rows must additionally belong to the source's current " +
                    "extraction revision (rows predating revision ownership stay " +
                    "visible), and NOTE / WRONG_QUESTION are scoped to the caller. " +
                    "Ranking combines the deterministic LIKE tiers with the MySQL " +
                    "ngram FULLTEXT relevance score, falling back to LIKE alone for " +
                    "queries FULLTEXT cannot tokenize. " +
                    "A NOTE hit carries noteId so the client can open it directly. " +
                    "Space isolation is enforced at the SQL level.",
            parameters = {
                    @Parameter(name = "spaceId", in = ParameterIn.PATH, required = true,
                            schema = @Schema(type = "integer", format = "int64")),
                    @Parameter(name = "q", in = ParameterIn.QUERY, required = true,
                            schema = @Schema(type = "string", minLength = 1, maxLength = 200)),
                    @Parameter(name = "types", in = ParameterIn.QUERY, required = false,
                            description = "Comma-separated: SOURCE,CONTENT_BLOCK,KNOWLEDGE_POINT,QUESTION,WRONG_QUESTION,NOTE",
                            schema = @Schema(type = "string", example = "SOURCE,QUESTION,NOTE")),
                    @Parameter(name = "page", in = ParameterIn.QUERY, required = false,
                            schema = @Schema(type = "integer", minimum = "0", defaultValue = "0")),
                    @Parameter(name = "size", in = ParameterIn.QUERY, required = false,
                            schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            },
            responses = {
                    @ApiResponse(responseCode = "200", description = "Typed heterogeneous search results"),
                    @ApiResponse(responseCode = "400", description = "Invalid query, type, page or size"),
                    @ApiResponse(responseCode = "401", description = "Unauthenticated"),
                    @ApiResponse(responseCode = "404", description = "LearningSpace absent/not owned")
            }
    )
    /**
     * Handles the search request.
     *
     * @param spaceId        path variable, must be owned by the caller
     * @param query          {@code q} parameter, 1..200 characters, validated
     * @param types          optional comma-separated entity filter, validated
     *                       against {@link #TYPE_PATTERN}
     * @param page           zero-based page index
     * @param size           page size, 1..100
     * @param authentication caller principal; its name is used as BOTH the
     *                       space-owner scope and the per-user NOTE /
     *                       WRONG_QUESTION scope, so a caller can never widen
     *                       the search to another subject's private rows
     * @return ranked page of typed hits
     * @throws ResponseStatusException 404 when the space is not owned by the
     *                                 caller
     */
    public SearchPageResponse search(
            @PathVariable Long spaceId,
            @RequestParam("q") @Size(min = 1, max = 200, message = "q must be 1..200 characters") String query,
            @RequestParam(value = "types", required = false) @Pattern(regexp = TYPE_PATTERN,
                    message = "types must be a comma-separated subset of SOURCE,CONTENT_BLOCK,KNOWLEDGE_POINT,QUESTION,WRONG_QUESTION,NOTE") String types,
            @RequestParam(value = "page", required = false, defaultValue = "0") @Min(0) Integer page,
            @RequestParam(value = "size", required = false, defaultValue = "20") @Min(1) @Max(100) Integer size,
            Authentication authentication) {
        SearchPageResponse response = searchService.search(
                authentication.getName(),
                authentication.getName(),
                spaceId,
                query,
                types,
                page,
                size);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "LearningSpace not found");
        }
        return response;
    }
}
