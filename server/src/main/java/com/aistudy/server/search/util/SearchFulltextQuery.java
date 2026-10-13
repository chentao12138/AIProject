package com.aistudy.server.search.util;

/**
 * Decides whether a user query can be served by the MySQL ngram FULLTEXT
 * indexes created by V054, and normalizes it for {@code MATCH ... AGAINST}.
 *
 * <p>WHY this gate exists: V054 builds every search index
 * {@code WITH PARSER ngram}, and the ngram parser only indexes token runs of
 * at least {@code ngram_token_size} characters (MySQL default {@code 2}).
 * A single-character term therefore has no index entry, and a term made only
 * of non-word characters (operators, punctuation) tokenizes to nothing at all.
 * For those inputs {@code MATCH ... AGAINST} silently returns {@code 0} for
 * every row, which would make search return nothing while {@code LIKE} still
 * finds the row. The caller must therefore fall back to the LIKE path.
 *
 * <p>FAILURE MODE: if {@link #isFulltextUsable(String)} is consulted with a
 * different parser or a raised {@code ngram_token_size} than the one recorded
 * in {@link #NGRAM_TOKEN_SIZE}, short queries may be routed to FULLTEXT and
 * under-return; only this constant has to be updated.
 */
public final class SearchFulltextQuery {

    /**
     * Token size the V054 indexes were built with
     * ({@code ngram_token_size}, MySQL default). Terms shorter than this are
     * not present in the index.
     */
    public static final int NGRAM_TOKEN_SIZE = 2;

    private static final int MAX_MATCH_QUERY_LENGTH = 200;

    private SearchFulltextQuery() {
    }

    /**
     * Whether the query has at least one token the ngram index can serve.
     *
     * @param query raw trimmed user query; {@code null} counts as unusable
     * @return {@code true} when a {@code MATCH ... AGAINST} predicate can
     *         contribute a score, {@code false} when the caller must rely on
     *         {@code LIKE} only
     */
    public static boolean isFulltextUsable(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        int run = 0;
        for (int i = 0; i < query.length(); i++) {
            if (isWordChar(query.charAt(i))) {
                run++;
                if (run >= NGRAM_TOKEN_SIZE) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    /**
     * Normalizes the query for the FULLTEXT argument.
     *
     * <p>The MATCH argument must NOT carry the LIKE escaping of
     * {@link SearchLikeEscape}: a backslash there is a literal character for
     * the index, not an escape, so escaping would corrupt the token.
     *
     * @param query raw trimmed user query; {@code null} yields the empty string
     * @return whitespace-collapsed, length-bounded search expression, never
     *         {@code null}
     */
    public static String normalize(String query) {
        if (query == null) {
            return "";
        }
        String collapsed = query.strip().replaceAll("\\s+", " ");
        if (collapsed.length() > MAX_MATCH_QUERY_LENGTH) {
            collapsed = collapsed.substring(0, MAX_MATCH_QUERY_LENGTH);
        }
        return collapsed;
    }

    /**
     * Whether a character belongs to an indexed word token.
     *
     * @param ch character under inspection
     * @return {@code true} for letters and digits (including CJK),
     *         {@code false} for punctuation, operators and whitespace
     */
    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch);
    }
}
