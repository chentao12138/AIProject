package com.aistudy.server.search;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-01 / F-02 / F-03 / F-04 defended WITHOUT a database: parses
 * {@code mapper/SearchMapper.xml} and asserts on the SQL and the bound
 * parameter sequence MyBatis actually emits for each combination of the
 * {@code types} filter.
 *
 * <p>WHY a unit test instead of only an integration test: the UNION ALL
 * assembly bug surfaces as MySQL error 1064 at runtime, and the
 * projection/count formula drift is invisible in the rendered SQL text
 * because {@code #{prefixPattern}} and {@code #{containsPattern}} both become
 * {@code ?}. Only the bound parameter sequence exposes it.
 *
 * <p>FAILURE MODE: if the mapper XML moves out of {@code classpath:/mapper/}
 * the resource lookup asserts loudly rather than passing vacuously.
 */
class SearchMapperSqlTest {

    private static final String NAMESPACE = "com.aistudy.server.search.mapper.SearchMapper";
    private static final String RESOURCE = "mapper/SearchMapper.xml";

    /** Every entity type SearchMapper.xml knows about, in branch order. */
    private static final List<String> ALL_TYPES = List.of(
            "SOURCE", "CONTENT_BLOCK", "KNOWLEDGE_POINT", "QUESTION",
            "WRONG_QUESTION", "NOTE");

    /** LIKE escape as it reaches the driver: two literal backslashes. */
    private static final String ESCAPE = "escape '\\\\'";

    private static final Pattern CASE_BLOCK = Pattern.compile("case(.*?)end", Pattern.DOTALL);

    private Configuration configuration;

    @BeforeEach
    void parseMapper() {
        configuration = new Configuration();
        try (InputStream in = SearchMapperSqlTest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            assertTrue(in != null, "missing classpath:" + RESOURCE);
            new XMLMapperBuilder(in, configuration, RESOURCE, configuration.getSqlFragments()).parse();
        } catch (Exception e) {
            throw new IllegalStateException("cannot parse " + RESOURCE, e);
        }
    }

    // ==================== F-01: UNION ALL assembly ====================

    /**
     * types=[NOTE] alone used to emit a LEADING separator because the NOTE
     * branch opened with UNION ALL, which {@code suffixOverrides} cannot strip.
     */
    @Test
    void singleTypeNoteEmitsNoLeadingOrDanglingSeparator() {
        String sql = projectionSql(List.of("NOTE"), false);
        assertFalse(Pattern.compile("from \\( ?union all").matcher(sql).find(),
                "types=[NOTE] must not begin its branch with UNION ALL: " + sql);
        assertEquals(0, occurrences(sql, "union all"), sql);
        assertSeparatorCount(countSql(List.of("NOTE"), false), 0);
    }

    /**
     * Every single-type request must be a well-formed single SELECT.
     */
    @Test
    void everySingleTypeIsWellFormed() {
        for (String type : ALL_TYPES) {
            assertSeparatorCount(projectionSql(List.of(type), false), 0);
            assertSeparatorCount(countSql(List.of(type), false), 0);
        }
    }

    /**
     * SOURCE,NOTE used to double the separator (SOURCE closed with one and
     * NOTE opened with one).
     */
    @Test
    void sourceAndNotePairEmitsExactlyOneSeparator() {
        assertNoAdjacentSeparators(projectionSql(List.of("SOURCE", "NOTE"), false));
        assertSeparatorCount(projectionSql(List.of("SOURCE", "NOTE"), false), 1);
        assertSeparatorCount(countSql(List.of("SOURCE", "NOTE"), false), 1);
    }

    /**
     * WRONG_QUESTION was the branch that omitted the trailing separator, so it
     * relied on NOTE's leading one; now it must end with one itself.
     */
    @Test
    void wrongQuestionAndNotePairEmitsExactlyOneSeparator() {
        String sql = projectionSql(List.of("WRONG_QUESTION", "NOTE"), false);
        assertNoAdjacentSeparators(sql);
        assertSeparatorCount(sql, 1);
        assertSeparatorCount(countSql(List.of("WRONG_QUESTION", "NOTE"), false), 1);
    }

    /**
     * The separator count must equal the number of joins for the full set and
     * for the no-filter case, in both statements and both scoring modes.
     */
    @Test
    void allTypesAndNoFilterEmitOneSeparatorPerJoin() {
        for (boolean fulltext : List.of(true, false)) {
            assertSeparatorCount(projectionSql(ALL_TYPES, fulltext), ALL_TYPES.size() - 1);
            assertSeparatorCount(countSql(ALL_TYPES, fulltext), ALL_TYPES.size() - 1);
            assertSeparatorCount(projectionSql(List.of(), fulltext), ALL_TYPES.size() - 1);
            assertSeparatorCount(countSql(List.of(), fulltext), ALL_TYPES.size() - 1);
            assertNoAdjacentSeparators(projectionSql(ALL_TYPES, fulltext));
        }
    }

    /**
     * {@code suffixOverrides} only strips a TRAILING separator, so the last
     * emitted branch must always be the one carrying it.
     */
    @Test
    void trailingSeparatorIsStrippedFromEverySubset() {
        for (List<String> subset : List.of(
                List.of("NOTE"),
                List.of("WRONG_QUESTION"),
                List.of("SOURCE", "NOTE"),
                List.of("QUESTION", "WRONG_QUESTION", "NOTE"),
                List.of("KNOWLEDGE_POINT", "WRONG_QUESTION"),
                ALL_TYPES)) {
            String sql = projectionSql(subset, true);
            assertFalse(Pattern.compile("union all ?\\) ?as results").matcher(sql).find(),
                    "page must not keep a trailing UNION ALL: " + sql);
            assertFalse(Pattern.compile("union all ?\\) ?as counted").matcher(countSql(subset, true)).find(),
                    "count must not keep a trailing UNION ALL");
        }
    }

    // ==================== F-04: projection / count parity ===================

    /**
     * The count is derived from the very same score ladder as the page: the
     * bound parameter sequence of each single-type statement must be the same
     * multiset, otherwise totalElements disagrees with the returned page.
     */
    @Test
    void projectionAndCountBindTheSameParameters() {
        for (String type : ALL_TYPES) {
            for (boolean fulltext : List.of(true, false)) {
                List<String> page = scoringParams("searchProjection", List.of(type), fulltext);
                List<String> count = scoringParams("searchCount", List.of(type), fulltext);
                assertEquals(page, count,
                        type + " (useFulltext=" + fulltext + ") must score and count with the same patterns");
            }
        }
    }

    /**
     * Regression for the KNOWLEDGE_POINT ladder: the count compared kp.title
     * against prefixPattern where the page used containsPattern, so a
     * contains-only title match scored 0 in the count and under-reported.
     */
    @Test
    void knowledgePointTitleContainsTierIsBoundToTheContainsPattern() {
        List<String> pageParams = params("searchProjection", List.of("KNOWLEDGE_POINT"), false);
        List<String> countParams = params("searchCount", List.of("KNOWLEDGE_POINT"), false);
        // 1 exactQuery + 1 prefixPattern + the rest containsPattern (3 WHERE
        // LIKEs plus the 4 remaining ladder tiers).
        assertEquals(1, occurrencesIn(pageParams, "exactQuery"), String.valueOf(pageParams));
        assertEquals(1, occurrencesIn(countParams, "exactQuery"), String.valueOf(countParams));
        assertEquals(1, occurrencesIn(pageParams, "prefixPattern"), String.valueOf(pageParams));
        assertEquals(1, occurrencesIn(countParams, "prefixPattern"),
                "only the tier-80 prefix comparison may bind prefixPattern: " + countParams);
        assertEquals(occurrencesIn(pageParams, "containsPattern"),
                occurrencesIn(countParams, "containsPattern"));
    }

    /**
     * Tier constants must not drift between the two statements either.
     */
    @Test
    void projectionAndCountShareIdenticalScoreLadders() {
        assertEquals(scoreLadders(projectionSql(ALL_TYPES, false)),
                scoreLadders(countSql(ALL_TYPES, false)),
                "one identical CASE ladder per UNION branch");
        assertEquals(ALL_TYPES.size(), scoreLadders(projectionSql(ALL_TYPES, false)).size());
    }

    // ==================== F-02: visibility predicates ====================

    /**
     * Pinned project-wide rule: published-only for shared content, with the
     * NULL-revision compatibility escape for pre-V053 rows.
     */
    @Test
    void publishedOnlyBranchesCarryThePublishGate() {
        for (String sql : List.of(projectionSql(ALL_TYPES, false), countSql(ALL_TYPES, false))) {
            assertTrue(sql.contains("and s.status = 'published'"), sql);
            assertTrue(sql.contains("and kp.status = 'published'"), sql);
            assertTrue(sql.contains("and q.status = 'published'"), sql);
            assertTrue(sql.contains("s.current_extraction_revision_id is null"), sql);
            assertTrue(sql.contains("or cb.extraction_revision_id = s.current_extraction_revision_id"), sql);
            assertFalse(sql.contains("sp.extraction_revision_id"),
                    "the source_page join is provenance only and must not over-filter: " + sql);
            assertTrue(sql.contains("and kp.deleted_at is null"), sql);
            assertTrue(sql.contains("and q.deleted_at is null"), sql);
        }
    }

    /**
     * The WRONG_QUESTION branch reads the Question through a join, so its
     * publish gate belongs on the join condition, not only in the standalone
     * QUESTION branch.
     */
    @Test
    void wrongQuestionJoinsOnlyPublishedQuestions() {
        for (String sql : List.of(projectionSql(ALL_TYPES, false), countSql(ALL_TYPES, false))) {
            assertTrue(sql.contains("and q.deleted_at is null and q.status = 'published'"), sql);
        }
    }

    /**
     * Private content stays per-user and must NOT gain a publish gate.
     */
    @Test
    void perUserScopeSurvivesForNoteAndWrongQuestion() {
        String note = projectionSql(List.of("NOTE"), false);
        assertTrue(note.contains("and n.user_subject_id = ?"), note);
        assertTrue(note.contains("and n.archived_at is null"), note);
        assertFalse(note.contains("'published'"), "notes have no publish state: " + note);

        String wrong = projectionSql(List.of("WRONG_QUESTION"), false);
        assertTrue(wrong.contains("and wq.user_subject = ?"), wrong);
        assertFalse(wrong.contains("n.status"), "notes must stay out of this branch");
        assertFalse(wrong.contains("archived"), "wrong questions have no archive gate");
    }

    // ==================== F-03: FULLTEXT wiring ====================

    /**
     * Each indexed column is matched in natural language mode, twice per
     * branch: once to widen the row set and once as the relevance boost.
     */
    @Test
    void everyIndexedColumnIsMatchableWhenFulltextIsUsable() {
        String page = projectionSql(ALL_TYPES, true);
        assertTrue(page.contains("match(s.title) against (? in natural language mode)"), page);
        assertTrue(page.contains("match(cb.normalized_text) against (? in natural language mode)"), page);
        assertTrue(page.contains("match(kp.title, kp.summary, kp.content) against (? in natural language mode)"), page);
        assertTrue(page.contains("match(q.stem) against (? in natural language mode)"), page);
        assertTrue(page.contains("match(n.title, n.content) against (? in natural language mode)"), page);
        assertEquals(ALL_TYPES.size() * 2, occurrences(page, "natural language mode"), page);
        assertEquals(ALL_TYPES.size() * 2,
                occurrences(countSql(ALL_TYPES, true), "natural language mode"));
    }

    /**
     * A punctuation-bearing query may be RANKED by FULLTEXT but must never be
     * ROW-SELECTED by it: ngram drops % and _ as delimiters, so allowing MATCH
     * in the WHERE would return "100X完成" for the literal query "100%" and
     * defeat the SearchLikeEscape wildcard behaviour.
     */
    @Test
    void punctuatedQueryIsRankedButNeverSelectedByFulltext() {
        String widening = sqlWith("searchProjection", ALL_TYPES, true, true, true);
        String rankingOnly = sqlWith("searchProjection", ALL_TYPES, true, false, true);
        assertEquals(ALL_TYPES.size() * 2, occurrences(widening, "natural language mode"), widening);
        assertEquals(ALL_TYPES.size(), occurrences(rankingOnly, "natural language mode"),
                "MATCH may only contribute the boost when the query carries punctuation");
        assertEquals(ALL_TYPES.size(),
                occurrences(rankingOnly, "end + least(9, round(match("), rankingOnly);
        assertEquals(occurrences(widening, ESCAPE), occurrences(rankingOnly, ESCAPE),
                "the escaped LIKE ladder must be identical in both modes");
    }

    /**
     * The boost is capped below the smallest tier gap (10) so relevance can
     * only ever refine ranking INSIDE a tier, and the LIKE ladder keeps its
     * escape handling in both modes.
     */
    @Test
    void fulltextBoostCannotReorderTheDeterministicTiers() {
        for (String sql : List.of(projectionSql(ALL_TYPES, true), countSql(ALL_TYPES, true))) {
            assertEquals(ALL_TYPES.size(), occurrences(sql, "end + least(9, round(match("), sql);
            assertEquals(0, occurrences(sql, "least(10"), sql);
        }
        // LIKE path stays intact whichever mode is active.
        assertEquals(occurrences(projectionSql(ALL_TYPES, false), ESCAPE),
                occurrences(projectionSql(ALL_TYPES, true), ESCAPE),
                "FULLTEXT must not replace the escaped LIKE comparisons");
    }

    /**
     * Queries the ngram parser cannot serve keep the pure LIKE shape: no MATCH
     * is evaluated at all, so nothing can silently score 0.
     */
    @Test
    void likeOnlyModeEvaluatesNoFulltext() {
        assertEquals(0, occurrences(projectionSql(ALL_TYPES, false), "match("));
        assertEquals(0, occurrences(countSql(ALL_TYPES, false), "match("));
    }

    /**
     * {@code score > 0} remains the row filter and the ordering stays
     * score-then-sort-key, so a page and its count cannot diverge.
     */
    @Test
    void scoreRemainsTheRowFilterAndSortKeyTiesRanks() {
        String page = projectionSql(ALL_TYPES, true);
        assertTrue(page.contains("where score > 0"), page);
        assertTrue(page.contains("order by score desc, sort_key asc"), page);
        assertTrue(page.contains("limit ? offset ?"), page);
        assertTrue(countSql(ALL_TYPES, false).replaceAll("[^>]", "").contains(">"),
                "count keeps the > 0 predicate");
    }

    // ==================== C-Search contract: noteId ====================

    /**
     * NOTE hits carry noteId; every other branch selects NULL so the UNION
     * keeps an identical column list.
     */
    @Test
    void noteBranchCarriesNoteIdAndEveryOtherBranchNullsIt() {
        String page = projectionSql(ALL_TYPES, false);
        assertEquals(1, occurrences(page, "n.id as note_id"), page);
        assertEquals(ALL_TYPES.size() - 1, occurrences(page, "null as note_id"), page);
        assertEquals(ALL_TYPES.size(), occurrences(page, "as entity_type"),
                "each branch keeps one entity discriminator");
    }

    /**
     * All six branches must project exactly the same number of columns, or
     * MySQL rejects the UNION outright.
     */
    @Test
    void everyBranchSelectsTheSameColumnCount() {
        int expected = -1;
        for (String type : ALL_TYPES) {
            String sql = projectionSql(List.of(type), false);
            int columns = occurrences(sql, " as ") - 1; // minus "as results"
            if (expected < 0) {
                expected = columns;
            }
            assertEquals(expected, columns, type + " branch must keep the shared projection width: " + sql);
        }
        assertEquals(13, expected, "entity/id/title/text/score + 7 provenance ids + sort_key");
    }

    /**
     * Dead SQL: {@code <sql id="typeFilter">} was never included anywhere.
     */
    @Test
    void deadTypeFilterFragmentIsGone() {
        try (InputStream in = SearchMapperSqlTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            String raw = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(raw.contains("id=\"typeFilter\""), "unused fragment must be deleted");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== helpers ====================

    private String projectionSql(List<String> types, boolean useFulltext) {
        return sqlText("searchProjection", types, useFulltext, true);
    }

    private String countSql(List<String> types, boolean useFulltext) {
        return sqlText("searchCount", types, useFulltext, false);
    }

    private String sqlText(String statement, List<String> types, boolean useFulltext, boolean paged) {
        return normalize(bound(statement, types, useFulltext, paged).getSql());
    }

    private List<String> params(String statement, List<String> types, boolean useFulltext) {
        boolean paged = "searchProjection".equals(statement);
        return bound(statement, types, useFulltext, paged).getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
    }

    /**
     * Sorted pattern properties bound by the score/visibility predicates only:
     * paging parameters belong to the page statement and must not participate
     * in the parity comparison.
     */
    private List<String> scoringParams(String statement, List<String> types, boolean useFulltext) {
        List<String> copy = params(statement, types, useFulltext).stream()
                .filter(property -> !"limit".equals(property) && !"offset".equals(property))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.Collections.sort(copy);
        return copy;
    }

    private BoundSql bound(String statement, List<String> types, boolean useFulltext, boolean paged) {
        return bound(statement, types, useFulltext, useFulltext, paged);
    }

    private BoundSql bound(String statement, List<String> types, boolean useFulltext,
                           boolean fulltextSelects, boolean paged) {
        MappedStatement ms = configuration.getMappedStatement(NAMESPACE + "." + statement);
        Map<String, Object> params = new HashMap<>();
        params.put("spaceId", 1L);
        params.put("ownerSubject", "owner-subject");
        params.put("userSubject", "user-subject");
        params.put("exactQuery", "数据库");
        params.put("prefixPattern", "数据库%");
        params.put("containsPattern", "%数据库%");
        params.put("matchQuery", "数据库");
        params.put("useFulltext", useFulltext);
        params.put("fulltextSelects", fulltextSelects);
        params.put("types", types);
        if (paged) {
            params.put("limit", 21);
            params.put("offset", 0);
        }
        return ms.getBoundSql(params);
    }

    private String sqlWith(String statement, List<String> types, boolean useFulltext,
                           boolean fulltextSelects, boolean paged) {
        return normalize(bound(statement, types, useFulltext, fulltextSelects, paged).getSql());
    }

    /**
     * Collapses whitespace and lower-cases, because MyBatis trims each dynamic
     * fragment independently.
     */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static List<String> scoreLadders(String sql) {
        List<String> ladders = new ArrayList<>();
        Matcher matcher = CASE_BLOCK.matcher(sql);
        while (matcher.find()) {
            ladders.add(matcher.group(1).trim());
        }
        return ladders;
    }

    private static void assertSeparatorCount(String sql, int expected) {
        assertEquals(expected, occurrences(sql, "union all"), sql);
    }

    private static void assertNoAdjacentSeparators(String sql) {
        assertFalse(sql.contains("union all union all"),
                "doubled UNION ALL is a MySQL 1064: " + sql);
    }

    private static int occurrencesIn(List<String> values, String needle) {
        return (int) values.stream().filter(needle::equals).count();
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
