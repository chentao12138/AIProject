package com.aistudy.server.ingestion.zip;

import com.aistudy.server.ingestion.job.service.IngestionErrorCode;
import com.aistudy.server.ingestion.job.service.IngestionFailure;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * A ZIP archive the safety gate refused.
 *
 * <p>{@link ZipArchiveInspector} reports violations as data rather than
 * exceptions, so whoever expands the archive has to turn an invalid result into
 * a typed failure; doing it here is what lets the job record
 * {@code ZIP_SAFETY_VIOLATION} instead of the generic ingestion error, which is
 * how a blocked zip-slip attempt stays distinguishable from an internal fault.
 *
 * <p>The safe message names violation kinds only: entry names are exactly the
 * hostile input we rejected and never reach the client.
 */
public class ZipSafetyException extends RuntimeException implements IngestionFailure {

    public ZipSafetyException(List<ZipViolation> violations) {
        super(describe(violations));
    }

    @Override
    public IngestionErrorCode errorCode() {
        return IngestionErrorCode.ZIP_SAFETY_VIOLATION;
    }

    @Override
    public String safeMessage() {
        return getMessage();
    }

    private static String describe(List<ZipViolation> violations) {
        String kinds = violations.stream()
                .map(violation -> violation.type().name().toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.joining(", "));
        return "ZIP rejected by the safety gate: " + (kinds.isEmpty() ? "unspecified" : kinds);
    }
}
