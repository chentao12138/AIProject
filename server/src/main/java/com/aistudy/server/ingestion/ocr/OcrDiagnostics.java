package com.aistudy.server.ingestion.ocr;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Log-safe reduction of engine output (F-09 part c).
 *
 * <p>Diagnostics must be useful (the first lines of a crash usually explain
 * it) and bounded (a 200 MB stdout dump in {@code INFO} is neither). Control
 * characters are dropped so an OCR result cannot inject forged log lines, and
 * the local temp paths the engine echoes are replaced with a placeholder:
 * absolute paths and storage keys must never reach a log that leaves the
 * host.
 */
final class OcrDiagnostics {

    /** Matches a Windows drive path or a POSIX absolute path token. */
    private static final Pattern PATHISH = Pattern.compile("[A-Za-z]:\\\\[^\\s\"']*|/(?:[\\w.\\-/~+@%]+/)+[\\w.\\-+@%]+");

    private OcrDiagnostics() {
    }

    /**
     * @param raw        engine output, may be null
     * @param maxChars   ceiling of the returned text; values below 1 clamp to 1
     * @param knownPaths exact local paths to mask first (may be null/empty)
     * @return a single-line, path-masked, control-char-free excerpt, or an
     *         empty string when {@code raw} is blank
     */
    static String redact(String raw, int maxChars, List<String> knownPaths) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int limit = Math.max(1, maxChars);
        String text = raw;
        if (knownPaths != null) {
            for (String path : knownPaths) {
                if (path != null && !path.isBlank()) {
                    text = text.replace(path, "<local-temp>");
                }
            }
        }
        text = PATHISH.matcher(text).replaceAll("<local-temp>");

        StringBuilder out = new StringBuilder(Math.min(limit, text.length()) + 8);
        boolean ellipsis = false;
        for (int i = 0; i < text.length() && out.length() < limit; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
                continue;
            }
            if (Character.isISOControl(c)) {
                out.append('?');
                continue;
            }
            out.append(c);
        }
        if (text.length() > out.length()) {
            ellipsis = true;
        }
        if (ellipsis) {
            // Reserve room for the marker so the ceiling is honoured exactly.
            while (out.length() + 3 > limit && out.length() > 0) {
                out.setLength(out.length() - 1);
            }
            out.append("...");
        }
        return out.toString();
    }
}
