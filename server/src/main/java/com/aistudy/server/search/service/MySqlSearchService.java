package com.aistudy.server.search.service;

import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.dto.SearchResult;
import com.aistudy.server.search.mapper.SearchMapper;
import com.aistudy.server.search.model.SearchProjection;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.util.SearchFulltextQuery;
import com.aistudy.server.search.util.SearchLikeEscape;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MySQL-backed {@link SearchService}: ranks typed branches with
 * {@link SearchMapper}, on top of the ngram FULLTEXT indexes of V054 with a
 * deterministic LIKE ladder kept as fallback.
 *
 * <p>Visibility (published / current-revision / per-user) is NOT decided here
 * but in the mapper statements, so the AI tutor path that reuses this service
 * inherits exactly the same guarantees.
 *
 * <p>FAILURE MODE: returns {@code null} when the space is absent or not owned
 * by {@code ownerSubject} (the controller maps that to 404); throws
 * {@link ResponseStatusException} with 400 for a blank or over-long query.
 */
@Service
public class MySqlSearchService implements SearchService {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;
    private static final int MIN_QUERY_LENGTH = 1;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final String DEFAULT_TYPES_JOIN = ",";

    private final SearchMapper searchMapper;
    private final LearningSpaceService learningSpaceService;

    public MySqlSearchService(SearchMapper searchMapper,
                              LearningSpaceService learningSpaceService) {
        this.searchMapper = searchMapper;
        this.learningSpaceService = learningSpaceService;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Paging is over-fetch-by-one: {@code safeSize + 1} rows are requested
     * so a next page can be detected without a second query, and the extra row
     * is dropped before mapping. {@code size} is clamped to 1..100 and
     * {@code page} to a minimum of 0.
     *
     * @param ownerSubject subject that must own {@code spaceId}
     * @param userSubject  subject scoping NOTE and WRONG_QUESTION branches
     * @param spaceId      LearningSpace id; {@code null} space yields {@code null}
     * @param query        raw keyword, trimmed to 1..200 characters
     * @param types        comma-separated {@link SearchEntityType} names, or
     *                     {@code null} for every branch
     * @param page         zero-based page index
     * @param size         requested page size, clamped to 1..100
     * @return an immutable page; {@code null} when the space is not visible to
     *         {@code ownerSubject}
     * @throws ResponseStatusException 400 when the query is blank or longer
     *                                 than 200 characters
     */
    @Override
    public SearchPageResponse search(String ownerSubject,
                                     String userSubject,
                                     Long spaceId,
                                     String query,
                                     String types,
                                     int page,
                                     int size) {
        if (learningSpaceService.getMine(ownerSubject, spaceId) == null) {
            return null;
        }

        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must not be blank");
        }
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "q must be at most 200 characters");
        }

        int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
        int safePage = Math.max(page, 0);
        int offset = safePage * safeSize;

        List<String> normalizedTypes = normalizedTypes(types);

        String escapedQuery = SearchLikeEscape.escape(normalized);
        String prefixPattern = escapedQuery + "%";
        String containsPattern = "%" + escapedQuery + "%";

        // V054 ngram indexes only carry token runs of at least ngram_token_size
        // word characters, so single-character or punctuation-only queries must
        // stay on the LIKE path or they would return nothing at all.
        boolean useFulltext = SearchFulltextQuery.isFulltextUsable(normalized);
        String matchQuery = SearchFulltextQuery.normalize(normalized);

        List<SearchProjection> projections = searchMapper.searchProjection(
                spaceId, ownerSubject, userSubject, normalized,
                prefixPattern, containsPattern, matchQuery, useFulltext,
                safeSize + 1, offset,
                normalizedTypes);

        boolean hasNext = projections.size() > safeSize;
        if (hasNext) {
            projections = new ArrayList<>(projections.subList(0, safeSize));
        }

        List<SearchResult> results = new ArrayList<>(projections.size());
        for (SearchProjection projection : projections) {
            if (projection.getScore() == null || projection.getScore() == 0) {
                continue;
            }
            results.add(SearchResult.from(projection, normalized));
        }

        long totalElements = searchMapper.searchCount(
                spaceId, ownerSubject, userSubject, normalized,
                prefixPattern, containsPattern, matchQuery, useFulltext,
                normalizedTypes);

        int totalPages = (int) Math.max(1, Math.ceil((double) totalElements / safeSize));

        return new SearchPageResponse(
                Collections.unmodifiableList(results),
                safePage,
                safeSize,
                totalElements,
                totalPages
        );
    }

    /**
     * Validates and normalizes the comma-separated {@code types} filter.
     *
     * @param types raw filter value; {@code null} or blank means all branches
     * @return immutable list of canonical {@link SearchEntityType} names,
     *         empty when no filter was supplied
     * @throws IllegalArgumentException for an unknown token; the controller's
     *                                  bean validation turns that into a 400
     *                                  before it reaches this method
     */
    private List<String> normalizedTypes(String types) {
        if (types == null || types.isBlank()) {
            return Collections.emptyList();
        }
        List<String> normalized = new ArrayList<>();
        for (String raw : types.split(DEFAULT_TYPES_JOIN)) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            normalized.add(SearchEntityType.valueOf(trimmed).name());
        }
        return Collections.unmodifiableList(normalized);
    }
}
