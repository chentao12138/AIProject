package com.aistudy.server.search.mapper;

import com.aistudy.server.search.model.SearchProjection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Keyword search over one LearningSpace, implemented as a UNION ALL of typed
 * branches in {@code mapper/SearchMapper.xml}.
 *
 * <p>Both statements are two views of the SAME predicate set: the projection
 * page and the count must apply identical visibility and score formulas,
 * otherwise {@code totalElements} disagrees with the returned page.
 */
@Mapper
public interface SearchMapper {

    /**
     * One page of ranked hits.
     *
     * <p>Visibility is enforced per branch as pinned in the mapper XML
     * (SOURCE / CONTENT_BLOCK / KNOWLEDGE_POINT / QUESTION require
     * {@code PUBLISHED}, content-block rows must additionally belong to the
     * source's current extraction revision with a NULL-revision compatibility
     * escape, and NOTE and WRONG_QUESTION stay per-user with no publish gate).
     * The joined source_page row is provenance only and carries no predicate of
     * its own.
     *
     * @param spaceId         LearningSpace scope; required by every branch
     * @param ownerSubject    space owner subject, guards cross-owner access
     * @param userSubject     caller subject, scopes NOTE and WRONG_QUESTION
     * @param exactQuery      raw normalized query for exact-tier comparisons
     * @param prefixPattern   {@code %}-wrapped LIKE pattern, escaped
     * @param containsPattern {@code %}-wrapped LIKE pattern, escaped
     * @param matchQuery      FULLTEXT argument, never LIKE-escaped; ignored
     *                        when {@code useFulltext} is {@code false}
     * @param useFulltext     whether the V054 ngram indexes can tokenize this
     *                        query at all (see {@code SearchFulltextQuery});
     *                        when {@code false} no MATCH is evaluated
     * @param fulltextSelects whether {@code MATCH(...) > 0} may also decide
     *                        WHICH rows are returned; false keeps the escaped
     *                        LIKE predicate as the sole row filter
     * @param limit           row cap; callers pass size + 1 to detect a next page
     * @param offset          zero-based row offset
     * @param types           requested entity types; empty means all branches
     * @return hits with {@code score > 0}, ranked by score then sort key
     */
    List<SearchProjection> searchProjection(@Param("spaceId") Long spaceId,
                                            @Param("ownerSubject") String ownerSubject,
                                            @Param("userSubject") String userSubject,
                                            @Param("exactQuery") String exactQuery,
                                            @Param("prefixPattern") String prefixPattern,
                                            @Param("containsPattern") String containsPattern,
                                            @Param("matchQuery") String matchQuery,
                                            @Param("useFulltext") boolean useFulltext,
                                            @Param("fulltextSelects") boolean fulltextSelects,
                                            @Param("limit") int limit,
                                            @Param("offset") int offset,
                                            @Param("types") List<String> types);

    /**
     * Total number of hits for the same predicates as
     * {@link #searchProjection}, ignoring paging.
     *
     * <p>FAILURE MODE: any drift between the two statements' score formulas or
     * visibility predicates makes the reported {@code totalElements} and
     * {@code totalPages} wrong (this is how the KNOWLEDGE_POINT prefix/contains
     * under-count got introduced).
     *
     * @param spaceId         LearningSpace scope
     * @param ownerSubject    space owner subject
     * @param userSubject     caller subject, scopes NOTE and WRONG_QUESTION
     * @param exactQuery      raw normalized query for exact-tier comparisons
     * @param prefixPattern   {@code %}-wrapped LIKE pattern, escaped
     * @param containsPattern {@code %}-wrapped LIKE pattern, escaped
     * @param matchQuery      FULLTEXT argument, never LIKE-escaped
     * @param useFulltext     whether MATCH contributes to the score ladder
     * @param fulltextSelects whether MATCH may also widen the row set
     * @param types           requested entity types; empty means all branches
     * @return count of ranked hits, never negative
     */
    long searchCount(@Param("spaceId") Long spaceId,
                     @Param("ownerSubject") String ownerSubject,
                     @Param("userSubject") String userSubject,
                     @Param("exactQuery") String exactQuery,
                     @Param("prefixPattern") String prefixPattern,
                     @Param("containsPattern") String containsPattern,
                     @Param("matchQuery") String matchQuery,
                     @Param("useFulltext") boolean useFulltext,
                     @Param("fulltextSelects") boolean fulltextSelects,
                     @Param("types") List<String> types);
}
