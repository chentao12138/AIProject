package com.aistudy.server.search.util;

public final class SearchLikeEscape {

    private SearchLikeEscape() {
    }

    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\")
                  .replace("%", "\\%")
                  .replace("_", "\\_");
    }
}
