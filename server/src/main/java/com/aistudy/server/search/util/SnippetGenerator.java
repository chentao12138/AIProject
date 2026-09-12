package com.aistudy.server.search.util;

public final class SnippetGenerator {

    private static final int DEFAULT_SNIPPET_MAX = 250;
    private static final String ELLIPSIS = "...";

    private SnippetGenerator() {
    }

    public static String snippet(String text, String query, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxLength <= 0) {
            maxLength = DEFAULT_SNIPPET_MAX;
        }

        String lowerText = text;
        String lowerQuery = query == null ? "" : query.toLowerCase();
        int idx = lowerText.toLowerCase().indexOf(lowerQuery);

        if (idx < 0) {
            return safeSubstring(text, maxLength);
        }

        int maxContent = Math.max(maxLength - ELLIPSIS.length() * 2, 1);
        int start = Math.max(0, idx - maxContent / 2);
        int end = Math.min(text.length(), start + maxContent);
        start = Math.max(0, end - maxContent);

        String candidate = safeSubstring(text, start, end);
        if (start > 0) {
            candidate = ELLIPSIS + candidate;
        }
        if (end < text.length()) {
            candidate = candidate + ELLIPSIS;
        }
        return candidate;
    }

    private static String safeSubstring(String text, int maxLength) {
        return safeSubstring(text, 0, maxLength);
    }

    private static String safeSubstring(String text, int start, int end) {
        if (text == null) {
            return "";
        }
        int len = text.length();
        if (start < 0) {
            start = 0;
        }
        if (end > len) {
            end = len;
        }
        if (end <= start) {
            return "";
        }

        if (start > 0
                && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            start--;
        }
        if (end < len
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end++;
        }
        return text.substring(start, end);
    }
}
