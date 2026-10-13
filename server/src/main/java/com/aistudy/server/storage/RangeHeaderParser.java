package com.aistudy.server.storage;

/**
 * F-15 — pure HTTP {@code Range} header parser shared by every RAW
 * download surface (BUSINESS-004 owner endpoint and the ADMIN twin).
 *
 * <p>A controller-returned {@code Resource} body never receives
 * Spring's automatic range handling, so each streaming controller must
 * interpret the header itself. This class is deliberately static and
 * dependency-free (no Spring, no IO) so the parsing contract can be
 * unit-tested without a database or a filesystem.
 *
 * <h3>Supported grammar (single-range only)</h3>
 *
 * <pre>
 *   Range        = "bytes" "=" range
 *   range        = first-last | first- | "-" suffixLength
 * </pre>
 *
 * <p>V1 deliberately serves at most ONE range per request. Multi-range
 * headers ({@code bytes=0-1,4-5}) would require a
 * {@code multipart/byteranges} body; the client is expected to request
 * ranges one at a time (PDF readers do exactly that). A multi-range
 * header is therefore answered as {@link Disposition#UNSATISFIABLE}
 * (HTTP 416) — a deterministic, documented outcome rather than a
 * silently ignored range.
 *
 * <h3>Disposition rules</h3>
 *
 * <ul>
 *   <li>{@code null} / blank header → {@link Disposition#FULL_REQUEST}
 *       (serve 200 as today).</li>
 *   <li>Syntactically invalid single range → {@link Disposition#FULL_REQUEST}
 *       — RFC 9110 §14.2: a server MUST ignore an unrecognized Range.</li>
 *   <li>Unit other than {@code bytes} (case-insensitive) →
 *       {@link Disposition#UNSATISFIABLE}. We cannot serve another
 *       unit, and RFC 9110 permits 416 when no range of the requested
 *       unit can be satisfied; failing loudly beats pretending the
 *       unit was a byte range.</li>
 *   <li>Multiple comma-separated ranges → {@link Disposition#UNSATISFIABLE}
 *       (see V1 note above).</li>
 *   <li>Start at/after end-of-object, or a suffix length of 0, or a
 *       zero-length object with a Range header →
 *       {@link Disposition#UNSATISFIABLE}.</li>
 *   <li>Otherwise → {@link Disposition#SATISFIED} with an inclusive
 *       {@code [start, end]} window clamped to {@code totalSize - 1}
 *       (RFC 9110: an end beyond the object is clamped, not rejected).</li>
 * </ul>
 *
 * <p>Failures here never throw: the parser is a total function on
 * (String, long) so controllers cannot be crashed by a malicious
 * header.
 */
public final class RangeHeaderParser {

    /**
     * Outcome classification for one Range header.
     */
    public enum Disposition {
        /** No usable Range was present — serve the whole body with 200. */
        FULL_REQUEST,
        /** One satisfiable byte window was parsed — serve 206. */
        SATISFIED,
        /** A Range was present but cannot be served — respond 416. */
        UNSATISFIABLE
    }

    /**
     * Immutable parse outcome. {@code start}/{@code endInclusive} are
     * meaningful ONLY for {@link Disposition#SATISFIED}.
     *
     * @param disposition how the controller should react
     * @param start       first byte of the window (0-based, inclusive)
     * @param endInclusive last byte of the window (inclusive, &lt; totalSize)
     */
    public record Result(Disposition disposition, long start, long endInclusive) {

        static Result full() {
            return new Result(Disposition.FULL_REQUEST, 0, -1);
        }

        static Result unsatisfiable() {
            return new Result(Disposition.UNSATISFIABLE, 0, -1);
        }

        static Result window(long start, long endInclusive) {
            return new Result(Disposition.SATISFIED, start, endInclusive);
        }

        /** Byte count of the window; never used for non-SATISFIED outcomes. */
        public long length() {
            return endInclusive - start + 1;
        }
    }

    private RangeHeaderParser() {
    }

    /**
     * Parses one {@code Range} header value against an object size.
     *
     * @param headerValue raw {@code Range} header value, may be {@code null}
     * @param totalSize   exact size in bytes of the stored object
     *                    (non-negative; zero-length objects reject every range)
     * @return the disposition; never {@code null}, never throws
     */
    public static Result parse(String headerValue, long totalSize) {
        if (headerValue == null || headerValue.isBlank()) {
            return Result.full();
        }
        String value = headerValue.trim();
        int eq = value.indexOf('=');
        if (eq <= 0 || eq == value.length() - 1) {
            // No '=' or empty spec list: unrecognized syntax → ignore.
            return Result.full();
        }
        String unit = value.substring(0, eq).trim();
        if (!"bytes".equalsIgnoreCase(unit)) {
            return Result.unsatisfiable();
        }
        String specList = value.substring(eq + 1).trim();
        if (specList.contains(",")) {
            // Multi-range is out of scope for V1 (no multipart/byteranges).
            return Result.unsatisfiable();
        }
        String spec = specList.trim();
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return Result.full();
        }
        String firstText = spec.substring(0, dash).trim();
        String lastText = spec.substring(dash + 1).trim();
        if (totalSize <= 0) {
            // A zero-length object satisfies nothing, whatever the range says.
            return Result.unsatisfiable();
        }

        try {
            if (firstText.isEmpty()) {
                // suffix form: bytes=-N → last N bytes.
                if (lastText.isEmpty()) {
                    return Result.full();
                }
                long suffix = Long.parseLong(lastText);
                if (suffix <= 0) {
                    return Result.unsatisfiable();
                }
                long start = Math.max(0, totalSize - suffix);
                return Result.window(start, totalSize - 1);
            }
            long start = Long.parseLong(firstText);
            if (start < 0 || start >= totalSize) {
                return Result.unsatisfiable();
            }
            if (lastText.isEmpty()) {
                // open-ended form: bytes=N- → N..EOF.
                return Result.window(start, totalSize - 1);
            }
            long end = Long.parseLong(lastText);
            if (end < start) {
                // first-last with first > last is invalid syntax → ignore.
                return Result.full();
            }
            return Result.window(start, Math.min(end, totalSize - 1));
        } catch (NumberFormatException e) {
            // Non-numeric bounds: unrecognized range → RFC 9110 says ignore it.
            return Result.full();
        }
    }

    /**
     * Formats the RFC 9110 {@code Content-Range} value for a satisfied
     * window: {@code bytes start-end/total}.
     */
    public static String contentRange(Result parsed, long totalSize) {
        return "bytes " + parsed.start() + "-" + parsed.endInclusive() + "/" + totalSize;
    }

    /**
     * Formats the {@code Content-Range: * /total} value used with 416.
     */
    public static String unsatisfiableContentRange(long totalSize) {
        return "bytes */" + totalSize;
    }
}
