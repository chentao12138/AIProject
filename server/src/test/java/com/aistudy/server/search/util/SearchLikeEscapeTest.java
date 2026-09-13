package com.aistudy.server.search.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchLikeEscapeTest {

    @Test
    void escapesBackslashPercentAndUnderscore() {
        assertEquals("a\\\\b", SearchLikeEscape.escape("a\\b"));
        assertEquals("100\\%", SearchLikeEscape.escape("100%"));
        assertEquals("a\\_b", SearchLikeEscape.escape("a_b"));
        assertEquals("\\%\\_\\\\", SearchLikeEscape.escape("%_\\"));
    }

    @Test
    void nullBecomesEmptyAndPlainTextUnchanged() {
        assertEquals("", SearchLikeEscape.escape(null));
        assertEquals("hello", SearchLikeEscape.escape("hello"));
        assertEquals("数据库", SearchLikeEscape.escape("数据库"));
    }
}
