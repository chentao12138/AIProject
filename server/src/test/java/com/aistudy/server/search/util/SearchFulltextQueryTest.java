package com.aistudy.server.search.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F-03 gating: which queries may be handed to the V054 ngram FULLTEXT indexes.
 *
 * <p>The ngram parser only indexes runs of at least
 * {@link SearchFulltextQuery#NGRAM_TOKEN_SIZE} word characters, so routing a
 * shorter or punctuation-only query to {@code MATCH ... AGAINST} would return
 * zero rows while {@code LIKE} still finds them.
 */
class SearchFulltextQueryTest {

    @Test
    void multiCharacterCjkAndLatinTermsAreIndexable() {
        assertTrue(SearchFulltextQuery.isFulltextUsable("数据库"));
        assertTrue(SearchFulltextQuery.isFulltextUsable("范式"));
        assertTrue(SearchFulltextQuery.isFulltextUsable("Java"));
        assertTrue(SearchFulltextQuery.isFulltextUsable("索引B+树"));
        assertTrue(SearchFulltextQuery.isFulltextUsable("事务隔离级别 说明"));
    }

    @Test
    void singleCharacterTermsFallBackToLike() {
        assertFalse(SearchFulltextQuery.isFulltextUsable("数"),
                "one character is below ngram_token_size and has no index entry");
        assertFalse(SearchFulltextQuery.isFulltextUsable("a"));
        assertFalse(SearchFulltextQuery.isFulltextUsable("a 数"));
    }

    @Test
    void nonWordOnlyQueriesFallBackToLike() {
        assertFalse(SearchFulltextQuery.isFulltextUsable("+"));
        assertFalse(SearchFulltextQuery.isFulltextUsable("<>-*&|()"));
        assertFalse(SearchFulltextQuery.isFulltextUsable("%_%"));
        assertFalse(SearchFulltextQuery.isFulltextUsable("   "));
        assertFalse(SearchFulltextQuery.isFulltextUsable(""));
        assertFalse(SearchFulltextQuery.isFulltextUsable(null));
    }

    @Test
    void singleCharactersSeparatedByPunctuationAreNotAToken() {
        // "a.b" has no two adjacent word characters, so ngram sees nothing.
        assertFalse(SearchFulltextQuery.isFulltextUsable("a.b"));
        assertFalse(SearchFulltextQuery.isFulltextUsable("1 2 3"));
        assertTrue(SearchFulltextQuery.isFulltextUsable("100%完成"),
                "the run '100' alone is long enough");
    }

    @Test
    void normalizeCollapsesWhitespaceAndNeverKeepsLikeEscaping() {
        assertEquals("数据库 教程", SearchFulltextQuery.normalize("  数据库\n 教程  "));
        assertEquals("100\\%完成", SearchFulltextQuery.normalize("100\\%完成"),
                "the MATCH argument is raw text: a backslash is a literal for the index");
        assertEquals("", SearchFulltextQuery.normalize(null));
        assertEquals(200, SearchFulltextQuery.normalize("数".repeat(400)).length(),
                "oversized arguments are truncated rather than rejected");
    }
}
