package com.aistudy.server.search.service;

import com.aistudy.server.search.dto.SearchPageResponse;

public interface SearchService {

    SearchPageResponse search(String ownerSubject,
                              String userSubject,
                              Long spaceId,
                              String query,
                              String types,
                              int page,
                              int size);
}
