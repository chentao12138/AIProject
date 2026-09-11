package com.aistudy.server.ingestion.zip;

/**
 * BUSINESS-005 — one ZIP safety violation found by
 * {@link ZipArchiveInspector}.
 *
 * <p>Machine-readable {@link ZipViolationType} plus a safe, bounded
 * diagnostic message. The message may contain the offending entry
 * name (archive content, not server paths) but never stack traces or
 * absolute filesystem paths.
 *
 * @param type      stable machine-readable violation kind
 * @param entryName offending entry name, or {@code null} when the
 *                  violation is archive-wide (e.g. INVALID_ARCHIVE)
 * @param message   safe human-readable diagnostic
 */
public record ZipViolation(ZipViolationType type, String entryName, String message) {

    public enum ZipViolationType {
        /** Archive is not a readable ZIP (corrupt / truncated / wrong
         * bytes / encrypted — the JDK rejects all of these the same
         * way when the entry data stream is opened). */
        INVALID_ARCHIVE,
        /** Entry name contains a {@code ..} traversal segment (zip-slip). */
        TRAVERSAL_NAME,
        /** Entry name is rooted (starts with {@code /} or {@code \}). */
        ROOTED_NAME,
        /** Entry name starts with a Windows drive prefix ({@code C:\} / {@code C:/}). */
        DRIVE_NAME,
        /** Entry name is blank. */
        BLANK_NAME,
        /** Entry uses a compression method the V1 pipeline cannot handle. */
        UNSUPPORTED_METHOD,
        /** Entry uncompressed size exceeds the configured per-entry limit. */
        ENTRY_TOO_LARGE,
        /** Total uncompressed size exceeds the configured archive limit. */
        TOTAL_TOO_LARGE,
        /** Entry count exceeds the configured archive limit. */
        TOO_MANY_ENTRIES,
        /** Compression ratio looks like a decompression bomb. */
        SUSPICIOUS_RATIO
    }
}
