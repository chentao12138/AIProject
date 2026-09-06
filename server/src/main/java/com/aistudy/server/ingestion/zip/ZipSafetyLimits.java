package com.aistudy.server.ingestion.zip;

/**
 * BUSINESS-005 — tunable limits for {@link ZipArchiveInspector}.
 *
 * <p>All limits are pure data with sane defaults; the production
 * configuration layer binds them from {@code aistudy.ingestion.zip.*}
 * (env-overridable, see application.yml). Pure-Java record so the
 * inspector itself stays storage- and Spring-free.
 *
 * <p>There is deliberately NO "reject encrypted" toggle: the JDK
 * cannot read encrypted ZIP entries with the standard library, the
 * V1 policy is that encrypted archives are ALWAYS rejected
 * (unsupported), and a config switch with no real implementation
 * would be a lie (AUTORUN-4H-PRE-RUNTIME-FIX-01).
 *
 * @param maxEntries                maximum number of entries in one archive
 * @param maxEntryUncompressedBytes maximum uncompressed size of one entry
 * @param maxTotalUncompressedBytes maximum total uncompressed size of one archive
 * @param maxCompressionRatio       maximum suspicious ratio
 *                                  (uncompressed / compressed) before the
 *                                  archive is treated as a decompression bomb
 */
public record ZipSafetyLimits(
        int maxEntries,
        long maxEntryUncompressedBytes,
        long maxTotalUncompressedBytes,
        long maxCompressionRatio) {

    /**
     * Defaults used when the configuration layer provides nothing
     * (and by unit tests that only override what they test):
     * 10,000 entries, 4 GiB per entry, 16 GiB total, 200:1 ratio.
     * Values are generous for scanned textbook packages (many page
     * images, possibly one large PDF) while still bounding
     * decompression bombs.
     */
    public static ZipSafetyLimits defaults() {
        return new ZipSafetyLimits(
                10_000,
                4L * 1024 * 1024 * 1024,
                16L * 1024 * 1024 * 1024,
                200);
    }
}
