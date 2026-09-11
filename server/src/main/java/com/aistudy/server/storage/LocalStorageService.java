package com.aistudy.server.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.UUID;

/**
 * BUSINESS-004 — local-filesystem implementation of {@link StorageService}
 * (ADR-033 first implementation).
 *
 * <h3>Root</h3>
 *
 * <p>Root comes from {@code aistudy.storage.local.root}, env-overridable
 * via {@code AISTUDY_STORAGE_ROOT}. Fallback: {@code ${user.home}/.aistudy/resources}
 * (cross-platform, never inside the Git repo). No Windows path is ever
 * hard-coded in Java.
 *
 * <h3>Server-generated keys</h3>
 *
 * <p>{@link #store} generates {@code yyyy/MM/<uuid>} (e.g.
 * {@code 2026/09/550e8400-e29b-41d4-a716-446655440000}) with
 * {@link YearMonth} + {@link UUID}. The client's original filename is
 * NEVER part of the physical path (NFR-SEC-002, data-model.md §5.2).
 *
 * <h3>Streaming write</h3>
 *
 * <p>Bytes are streamed to a sibling {@code .part-<uuid>} temp file in
 * the same directory while SHA-256 and the byte count are computed in
 * one pass, then atomically moved to the final name
 * ({@link StandardCopyOption#ATOMIC_MOVE}, with a safe plain-move
 * fallback for filesystems that do not support it). Any failure deletes
 * the partial file — no {@code .part} or final file is left behind.
 * The business layer never buffers the upload in memory
 * ({@code readAllBytes} / {@code MultipartFile.getBytes()} are not used
 * on the production path).
 *
 * <h3>Path traversal defense (two layers)</h3>
 *
 * <p>Every {@code load}/{@code delete} (and the internal resolution
 * used by {@code store}) defends in depth:
 *
 * <ol>
 *   <li><b>Lexical validation</b> of the raw key text BEFORE any
 *       filesystem resolution: {@code null}/blank, absolute paths,
 *       Windows drive prefixes ({@code C:\...} / {@code C:/...}),
 *       rooted/UNC paths, and any {@code .} / {@code ..} segment —
 *       with BOTH {@code /} and {@code \} treated as separators —
 *       are rejected with {@link IllegalArgumentException}. This
 *       catches keys like {@code 2026/09/../../secret.txt} whose
 *       normalized form still sits inside the root (a plain
 *       {@code resolve().normalize() + startsWith(root)} check
 *       cannot see the traversal syntax).</li>
 *   <li><b>Normalized containment</b>: the resolved key must still
 *       start with the normalized root after
 *       {@code resolve().normalize()} — the second defense for any
 *       case the lexical check misses.</li>
 * </ol>
 *
 * <p>Rejected keys throw {@link IllegalArgumentException} and are
 * never wrapped into {@link IllegalStateException}; only legitimate
 * keys reaching real IO failure get the IO wrapper.</li>
 */
@Service
public class LocalStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path root;

    public LocalStorageService(
            @Value("${aistudy.storage.local.root:${user.home}/.aistudy/resources}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create storage root: " + this.root, e);
        }
    }

    @Override
    public StorageResult store(InputStream input, StorageMetadata metadata) {
        String storageKey = generateStorageKey();
        Path target = resolveWithinRoot(storageKey);
        Path part = target.resolveSibling(target.getFileName() + ".part-" + UUID.randomUUID());
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            byte[] buf = new byte[8192];
            try (OutputStream out = Files.newOutputStream(part)) {
                int n;
                while ((n = input.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    size += n;
                }
            }
            moveAtomic(part, target);
            return new StorageResult(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | NoSuchAlgorithmException e) {
            deleteQuietly(part);
            throw new IllegalStateException("failed to store object '" + storageKey + "'", e);
        }
    }

    @Override
    public InputStream load(String storageKey) {
        Path target = resolveWithinRoot(storageKey);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new IllegalStateException("failed to load object '" + storageKey + "'", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveWithinRoot(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new IllegalStateException("failed to delete object '" + storageKey + "'", e);
        }
    }

    // ==================== internals ====================

    /** Server-generated logical key: {@code yyyy/MM/<uuid>}. */
    private String generateStorageKey() {
        return YearMonth.now().toString().replace('-', '/') + "/" + UUID.randomUUID();
    }

    /**
     * Resolves {@code storageKey} under the root with TWO defenses
     * (see class Javadoc): first a lexical validation of the raw key
     * text (traversal segments, absolute / drive-qualified keys
     * rejected on either separator), then the normalized containment
     * check. Both must pass; the filesystem is never touched for
     * rejected keys.
     */
    private Path resolveWithinRoot(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey must not be blank");
        }
        // A. Lexical validation BEFORE any filesystem resolution.
        //    "2026/09/../../secret.txt" normalizes to <root>/secret.txt,
        //    so a plain startsWith(root) check cannot reject it — the
        //    traversal syntax must be rejected on the raw text.
        validateStorageKeyLexically(storageKey);
        // B. Normalized root containment (second defense).
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException(
                    "storageKey escapes the storage root: '" + storageKey + "'");
        }
        return target;
    }

    /**
     * Rejects traversal segments and absolute / drive-qualified keys
     * on the RAW TEXT of the key, treating BOTH {@code /} and
     * {@code \} as path separators. Server-generated keys are
     * {@code yyyy/MM/<uuid>}, so {@code .} / {@code ..} segments,
     * drive prefixes and rooted paths are never legitimate.
     */
    private void validateStorageKeyLexically(String storageKey) {
        // Windows drive absolute: C:\secret.txt or C:/secret.txt.
        if (storageKey.length() >= 2
                && Character.isLetter(storageKey.charAt(0))
                && storageKey.charAt(1) == ':') {
            throw new IllegalArgumentException(
                    "storageKey must not be drive-qualified: '" + storageKey + "'");
        }
        // POSIX absolute path or UNC / rooted path.
        if (storageKey.startsWith("/") || storageKey.startsWith("\\")) {
            throw new IllegalArgumentException(
                    "storageKey must be relative: '" + storageKey + "'");
        }
        // '.' / '..' segments on either separator (e.g.
        // "2026/09/../../secret.txt" and "2026\09\..\..\secret.txt").
        for (String segment : storageKey.split("[/\\\\]+")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException(
                        "storageKey must not contain '.' or '..' path segments: '"
                                + storageKey + "'");
            }
        }
    }

    private void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Safe fallback for filesystems without atomic rename.
            Files.move(from, to);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("failed to remove partial storage file {}", path, e);
        }
    }
}
