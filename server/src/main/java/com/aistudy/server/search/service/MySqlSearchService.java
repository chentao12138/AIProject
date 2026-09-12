package com.aistudy.server.search.service;

import com.aistudy.server.search.dto.SearchPageResponse;
import com.aistudy.server.search.dto.SearchResult;
import com.aistudy.server.search.mapper.SearchMapper;
import com.aistudy.server.search.model.SearchProjection;
import com.aistudy.server.search.type.SearchEntityType;
import com.aistudy.server.search.util.SearchLikeEscape;
import com.aistudy.server.space.service.LearningSpaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        List<SearchProjection> projections = searchMapper.searchProjection(
                spaceId, ownerSubject, userSubject, normalized,
                prefixPattern, containsPattern, safeSize + 1, offset,
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
                prefixPattern, containsPattern, normalizedTypes);

        int totalPages = (int) Math.max(1, Math.ceil((double) totalElements / safeSize));

        return new SearchPageResponse(
                Collections.unmodifiableList(results),
                safePage,
                safeSize,
                totalElements,
                totalPages
        );
    }

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
