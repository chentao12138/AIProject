package com.aistudy.server.ingestion.zip;

import java.util.List;

/**
 * BUSINESS-005 — outcome of one {@link ZipArchiveInspector#inspect}
 * run.
 *
 * <p>{@code valid} is {@code true} only when the archive is readable
 * and NO violation was found against the applied
 * {@link ZipSafetyLimits}. {@code entries} carries the inspected
 * central-directory metadata (the manifest precursor) even for
 * invalid archives, so callers can audit why validation failed.
 *
 * @param valid                    true iff the archive is readable and all checks passed
 * @param violations               violations found (empty when valid)
 * @param entries                  inspected entry metadata, archive order
 * @param entryCount               number of entries inspected
 * @param totalUncompressedBytes   summed declared uncompressed size
 */
public record ZipInspectionResult(
        boolean valid,
        List<ZipViolation> violations,
        List<ZipEntryInfo> entries,
        int entryCount,
        long totalUncompressedBytes) {

    public static ZipInspectionResult invalid(List<ZipViolation> violations) {
        return new ZipInspectionResult(false, List.copyOf(violations),
                List.of(), 0, 0);
    }
}
