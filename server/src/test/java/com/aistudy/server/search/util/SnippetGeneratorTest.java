package com.aistudy.server.search.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnippetGeneratorTest {

    @Test
    void nullOrEmptyTextReturnsEmpty() {
        assertEquals("", SnippetGenerator.snippet(null, "q", 50));
        assertEquals("", SnippetGenerator.snippet("", "q", 50));
    }

    @Test
    void shortTextReturnedAsIs() {
        assertEquals("hello world", SnippetGenerator.snippet("hello world", "hello", 250));
    }

    @Test
    void matchVicinityIsCenteredAndBounded() {
        String text = "aaa ".repeat(100) + "NEEDLE" + " bbb".repeat(100);
        String snippet = SnippetGenerator.snippet(text, "NEEDLE", 80);
        assertTrue(snippet.contains("NEEDLE"));
        assertTrue(snippet.length() <= 80 + 6, "snippet must stay near max length: " + snippet.length());
        assertTrue(snippet.startsWith("...") || snippet.contains("NEEDLE"));
    }

    @Test
    void unicodeIsNotSplit() {
        String text = "数".repeat(200) + "据库" + "库".repeat(200);
        String snippet = SnippetGenerator.snippet(text, "数据库", 40);
        assertTrue(snippet.contains("据库") || snippet.contains("数据库"));
        // no lone surrogates
        for (int i = 0; i < snippet.length(); i++) {
            char c = snippet.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < snippet.length() && Character.isLowSurrogate(snippet.charAt(i + 1)));
            }
        }
    }

    @Test
    void noHtmlInjection() {
        String snippet = SnippetGenerator.snippet("<script>alert(1)</script>", "script", 100);
        assertEquals("<script>alert(1)</script>", snippet);
        assertFalse(snippet.contains("<b>"));
    }

    @Test
    void nonPositiveMaxUsesDefault() {
        String text = "x".repeat(500);
        String snippet = SnippetGenerator.snippet(text, "zzz", 0);
        assertTrue(snippet.length() <= 250 + 6);
    }
}
