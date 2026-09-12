package com.aistudy.server.search.mapper;

import com.aistudy.server.search.model.SearchProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SearchMapper {

    List<SearchProjection> searchProjection(@Param("spaceId") Long spaceId,
                                            @Param("ownerSubject") String ownerSubject,
                                            @Param("userSubject") String userSubject,
                                            @Param("exactQuery") String exactQuery,
                                            @Param("prefixPattern") String prefixPattern,
                                            @Param("containsPattern") String containsPattern,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset,
                                            @Param("types") List<String> types);

    long searchCount(@Param("spaceId") Long spaceId,
                     @Param("ownerSubject") String ownerSubject,
                     @Param("userSubject") String userSubject,
                     @Param("exactQuery") String exactQuery,
                     @Param("prefixPattern") String prefixPattern,
                     @Param("containsPattern") String containsPattern,
                     @Param("types") List<String> types);
}
