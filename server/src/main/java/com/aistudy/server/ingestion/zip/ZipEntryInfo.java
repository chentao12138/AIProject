package com.aistudy.server.ingestion.zip;

/**
 * BUSINESS-005 — metadata of ONE archive entry produced by
 * {@link ZipArchiveInspector} (the in-memory "internal manifest"
 * precursor, content-ingestion.md §7 step 3). No extraction happens
 * during inspection — this is central-directory metadata only.
 *
 * @param name             entry name exactly as stored in the archive
 * @param directory        whether the entry is a directory
 * @param method           ZIP compression method ({@link java.util.zip.ZipEntry#STORED}
 *                         = 0, {@link java.util.zip.ZipEntry#DEFLATED} = 8)
 * @param uncompressedSize declared uncompressed size, or {@code -1} if unknown
 * @param compressedSize   declared compressed size, or {@code -1} if unknown
 */
public record ZipEntryInfo(
        String name,
        boolean directory,
        int method,
        long uncompressedSize,
        long compressedSize) {
}
