package com.aistudy.server.ingestion.zip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * BUSINESS-005 — reusable ZIP safety inspector (content-ingestion.md
 * §7 step 2: 校验压缩包安全：防 zip-slip、路径穿越、异常解压比等).
 *
 * <p>Inspection performs NO extraction, NO decompression and NO
 * writes to arbitrary paths. The input stream is spooled to a
 * temporary file so the JDK can read the archive's central directory
 * ({@link ZipFile}), which carries the declared sizes and method for
 * every entry; the temp file is always deleted before returning. The
 * caller's stream is fully consumed and closed by this method.
 *
 * <p>Checks enforced (never trusts entry names or declared sizes):
 *
 * <ul>
 *   <li>entry name: any {@code ..} segment (zip-slip), rooted names
 *       (leading {@code /} or {@code \}), Windows drive prefixes
 *       ({@code C:\} / {@code C:/}) and blank names are rejected —
 *       both separators are treated as path separators, so
 *       {@code ..\..\evil} is caught exactly like {@code ../../evil}</li>
 *   <li>entry count: over {@link ZipSafetyLimits#maxEntries}</li>
 *   <li>per-entry uncompressed size: over
 *       {@link ZipSafetyLimits#maxEntryUncompressedBytes}</li>
 *   <li>total uncompressed size: over
 *       {@link ZipSafetyLimits#maxTotalUncompressedBytes}</li>
 *   <li>suspicious compression ratio (decompression bomb): any
 *       non-directory entry whose declared uncompressed/compressed
 *       ratio exceeds {@link ZipSafetyLimits#maxCompressionRatio}</li>
 *   <li>encrypted entries: ALWAYS rejected in V1. The JDK cannot read
 *       encrypted entries with the standard library; there is no
 *       {@code reject-encrypted} toggle because a switch with no real
 *       "allow" implementation would be a lie
 *       (AUTORUN-4H-PRE-RUNTIME-FIX-01).</li>
 *   <li>unsupported compression methods: only STORED (0) and
 *       DEFLATED (8) are accepted — the V1 pipeline cannot process
 *       Deflate64 / bzip2 / LZMA / ZSTD archives</li>
 * </ul>
 *
 * <p><strong>Encryption detection (pure JDK):</strong>
 * {@code java.util.zip.ZipEntry} exposes no public encryption-flag
 * accessor, and iterating the central directory alone does not
 * surface encryption. The JDK DOES reject an encrypted entry when
 * its data stream is opened ({@code ZipFile.getInputStream} throws
 * {@code ZipException} — "invalid CEN header (encrypted entry)").
 * This inspector therefore opens and immediately closes each
 * non-directory entry's data stream as part of inspection; no data
 * is read or decompressed (the stream is never read from). The
 * resulting {@code ZipException} is folded into the safe
 * {@link ZipViolation.ZipViolationType#INVALID_ARCHIVE} verdict —
 * the same stable business outcome the ingestion job maps to
 * {@code ZIP_SAFETY_VIOLATION}. Corrupt entries surface the same
 * way.
 *
 * <p><strong>Residual risk (documented, accepted for V1):</strong>
 * the checks above use the central directory, which a hostile archive
 * can falsify (declared sizes smaller than the real stream). Full
 * enforcement must re-check every limit while actually streaming the
 * data — that belongs to the future extraction step, not to
 * inspection (BACKEND_AUTORUN_4H.md §5.4). This inspector's job is to
 * reject obvious structural abuse before any extraction exists.
 *
 * <p>Pure Java: no Spring, no storage dependency, no IO on the
 * caller's paths.
 */
public final class ZipArchiveInspector {

    private static final Logger log = LoggerFactory.getLogger(ZipArchiveInspector.class);

    /** Windows drive prefix, e.g. {@code C:} or {@code c:} followed by a separator. */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:[/\\\\]");

    private ZipArchiveInspector() {
    }

    /**
     * Inspects one ZIP archive against {@code limits}.
     *
     * <p>Never throws for archive-content problems: corrupt input,
     * hostile entry names, encrypted entries and limit violations
     * all produce an {@code invalid} result with typed violations.
     * Only real IO failures (temp-file creation, disk read errors)
     * escape as {@link IllegalStateException} — those are environment
     * failures, not archive verdicts.
     *
     * @param in     the archive bytes (consumed and closed)
     * @param limits safety limits to enforce
     * @return inspection result; {@code valid} true iff all checks passed
     * @throws IllegalStateException on IO/environment failure
     */
    public static ZipInspectionResult inspect(InputStream in, ZipSafetyLimits limits) {
        Path temp = null;
        try {
            temp = Files.createTempFile("aistudy-zip-inspect-", ".zip");
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return inspectFile(temp, limits);
        } catch (IOException e) {
            // Environment failure (temp dir, disk), not an archive verdict.
            throw new IllegalStateException("failed to spool archive for ZIP inspection", e);
        } finally {
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignored) {
                // caller stream is consumed; close failure is not a verdict
            }
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("failed to delete zip inspection temp file {}: {}",
                            temp, e.getMessage());
                }
            }
        }
    }

    private static ZipInspectionResult inspectFile(Path file, ZipSafetyLimits limits) throws IOException {
        List<ZipViolation> violations = new ArrayList<>();
        List<ZipEntryInfo> entries = new ArrayList<>();
        long totalUncompressed = 0;
        int count = 0;

        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry entry = en.nextElement();
                count++;
                String name = entry.getName();

                ZipViolation nameViolation = validateName(name);
                if (nameViolation != null) {
                    violations.add(nameViolation);
                }
                if (!entry.isDirectory()
                        && entry.getMethod() != ZipEntry.STORED
                        && entry.getMethod() != ZipEntry.DEFLATED) {
                    violations.add(new ZipViolation(
                            ZipViolation.ZipViolationType.UNSUPPORTED_METHOD, name,
                            "entry '" + displayName(name) + "' uses unsupported compression method "
                                    + entry.getMethod()));
                }
                long size = entry.getSize();
                if (!entry.isDirectory() && size >= 0
                        && size > limits.maxEntryUncompressedBytes()) {
                    violations.add(new ZipViolation(
                            ZipViolation.ZipViolationType.ENTRY_TOO_LARGE, name,
                            "entry '" + displayName(name) + "' declares " + size
                                    + " uncompressed bytes (limit "
                                    + limits.maxEntryUncompressedBytes() + ")"));
                }
                if (size > 0) {
                    totalUncompressed += size;
                    if (totalUncompressed > limits.maxTotalUncompressedBytes()) {
                        violations.add(new ZipViolation(
                                ZipViolation.ZipViolationType.TOTAL_TOO_LARGE, null,
                                "total uncompressed size exceeds limit of "
                                        + limits.maxTotalUncompressedBytes() + " bytes"));
                    }
                }
                long compressed = entry.getCompressedSize();
                if (!entry.isDirectory() && size > 0 && compressed > 0
                        && (double) size / compressed > limits.maxCompressionRatio()) {
                    violations.add(new ZipViolation(
                            ZipViolation.ZipViolationType.SUSPICIOUS_RATIO, name,
                            "entry '" + displayName(name) + "' has suspicious compression ratio "
                                    + size + "/" + compressed
                                    + " (limit " + limits.maxCompressionRatio() + ":1)"));
                }
                if (count > limits.maxEntries()) {
                    violations.add(new ZipViolation(
                            ZipViolation.ZipViolationType.TOO_MANY_ENTRIES, null,
                            "archive has more than " + limits.maxEntries() + " entries"));
                    break;
                }

                // Encryption probe: the JDK rejects an encrypted entry
                // when its data stream is opened (ZipException "invalid
                // CEN header (encrypted entry)"). Open + close WITHOUT
                // reading a single byte — this is the pure-JDK way to
                // surface encryption that central-directory iteration
                // alone would silently pass. The ZipException propagates
                // to the outer catch and becomes a safe INVALID_ARCHIVE
                // verdict. (ZipEntry has no public encryption-flag
                // accessor; the JDK internal flag is not accessible.)
                probeEntryStream(zip, entry);

                entries.add(new ZipEntryInfo(name, entry.isDirectory(), entry.getMethod(),
                        size, entry.getCompressedSize()));
            }
        } catch (ZipException e) {
            // Corrupt AND encrypted archives both land here — one safe,
            // stable external outcome (AUTORUN-4H-PRE-RUNTIME-FIX-01).
            return ZipInspectionResult.invalid(List.of(
                    new ZipViolation(ZipViolation.ZipViolationType.INVALID_ARCHIVE, null,
                            "archive is not a readable ZIP: " + safeReason(e))));
        } catch (IOException e) {
            // Read error on a structurally readable file — environment
            // failure, not an archive verdict.
            throw new IllegalStateException("failed to read archive during ZIP inspection", e);
        }

        return new ZipInspectionResult(violations.isEmpty(),
                List.copyOf(violations), List.copyOf(entries), count, totalUncompressed);
    }

    /**
     * Opens and immediately closes one entry's data stream. For an
     * encrypted entry the JDK throws {@link ZipException}; for a
     * non-directory entry whose local header is corrupt it throws
     * too. Nothing is read from the stream.
     */
    private static void probeEntryStream(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.isDirectory()) {
            return;
        }
        try (InputStream ignored = zip.getInputStream(entry)) {
            // open + close only — no data read, no decompression
        } catch (IOException e) {
            // Encrypted or corrupt entry streams are normal ZIP failure
            // modes. Bubble up as a checked IO so the caller can emit a
            // safe verdict without logging internal message text here.
            throw e;
        }
    }

    /**
     * Lexical entry-name validation (BACKEND_AUTORUN_4H.md §5.4:
     * must not trust entry names). Both {@code /} and {@code \} are
     * treated as separators; {@code ..} segments, rooted names,
     * Windows drive prefixes and blank names are rejected BEFORE any
     * filesystem interpretation of the name happens.
     *
     * @return a violation, or {@code null} when the name is acceptable
     */
    private static ZipViolation validateName(String name) {
        if (name == null || name.isBlank()) {
            return new ZipViolation(ZipViolation.ZipViolationType.BLANK_NAME, name,
                    "archive contains an entry with a blank name");
        }
        if (name.charAt(0) == '/' || name.charAt(0) == '\\') {
            return new ZipViolation(ZipViolation.ZipViolationType.ROOTED_NAME, name,
                    "entry name '" + displayName(name) + "' is rooted (absolute)");
        }
        if (DRIVE_PREFIX.matcher(name).find()) {
            return new ZipViolation(ZipViolation.ZipViolationType.DRIVE_NAME, name,
                    "entry name '" + displayName(name) + "' uses a Windows drive prefix");
        }
        for (String segment : name.split("[/\\\\]+")) {
            if ("..".equals(segment)) {
                return new ZipViolation(ZipViolation.ZipViolationType.TRAVERSAL_NAME, name,
                        "entry name '" + displayName(name) + "' contains a '..' traversal segment");
            }
        }
        return null;
    }

    /** Bounded, locale-safe rendering of an entry name for messages. */
    private static String displayName(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.length() > 200 ? lower.substring(0, 200) + "..." : lower;
    }

    /** Bounded exception reason for safe messages (never stack traces). */
    private static String safeReason(IOException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return e.getClass().getSimpleName();
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.length() > 300 ? lower.substring(0, 300) + "..." : lower;
    }
}
