package com.aistudy.server.storage;

import com.aistudy.server.config.properties.StorageProperties;
import com.aistudy.server.config.properties.UploadProperties;
import com.aistudy.server.operations.LocalStorageOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.UUID;

/**
 * BUSINESS-004 / BUSINESS-023 — local-filesystem implementation of
 * {@link StorageService} (ADR-033 first implementation).
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
 * <h3>Path / symlink defense (four layers)</h3>
 *
 * <p>Every {@code load}/{@code delete} (and the internal resolution
 * used by {@code store}) defends in depth:
 *
 * <ol>
 *   <li><b>Lexical validation</b> of the raw key text BEFORE any
 *       filesystem resolution: {@code null}/blank, absolute paths,
 *       Windows drive prefixes ({@code C:\\...} / {@code C:/...}),
 *       rooted/UNC paths, and any {@code .} / {@code ..} segment —
 *       with BOTH {@code /} and {@code \\} treated as separators —
 *       are rejected with {@link StorageInvalidKeyException}. This
 *       catches keys like {@code 2026/09/../../secret.txt} whose
 *       normalized form still sits inside the root (a plain
 *       {@code resolve().normalize() + startsWith(root)} check
 *       cannot see the traversal syntax).</li>
 *   <li><b>Normalized containment</b>: the resolved key must still
 *       start with the normalized root after
 *       {@code resolve().normalize()} — the second defense for any
 *       case the lexical check misses.</li>
 *   <li><b>Symlink / special-file check</b>:
 *       {@link Files#readAttributes} with {@link LinkOption#NOFOLLOW_LINKS}
 *       rejects symlinks and non-regular files before any stream
 *       open or delete.</li>
 *   <li><b>Bounded write</b>: the storage layer enforces an
 *       independent byte ceiling during stream copy, so a misreported
 *       or chunked upload never exceeds the configured limit.</li>
 * </ol>
 *
 * <p>Rejected keys throw {@link StorageInvalidKeyException} and are
 * never wrapped into storage IO exceptions; only legitimate keys
 * reaching real IO failure get the IO wrapper.
 */
@Service
public class LocalStorageService implements StorageService, LocalStorageOperations {

    private static final Logger log = LoggerFactory.getLogger(LocalStorageService.class);

    private final Path root;
    private final long maxStorageBytes;

    public LocalStorageService(StorageProperties storageProperties,
                               UploadProperties uploadProperties) {
        String configuredRoot = storageProperties.getLocal().getRoot();
        if (configuredRoot == null || configuredRoot.isBlank()) {
            throw new IllegalStateException(
                    "aistudy.storage.local.root must be configured");
        }
        this.root = Path.of(configuredRoot).toAbsolutePath().normalize();
        this.maxStorageBytes = uploadProperties.getMaxFileSize().toBytes();
        try {
            Files.createDirectories(this.root);
            if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException(
                        "storage root is not a directory: " + this.root);
            }
            if (!Files.isWritable(this.root)) {
                throw new IllegalStateException(
                        "storage root is not writable: " + this.root);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot initialize storage root: " + this.root, e);
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
                    size += n;
                    if (size > maxStorageBytes) {
                        throw new StorageLimitExceededException(maxStorageBytes, size);
                    }
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }
            moveAtomic(part, target);
            return new StorageResult(storageKey, size, HexFormat.of().formatHex(digest.digest()));
        } catch (StorageLimitExceededException e) {
            deleteQuietly(part);
            throw e;
        } catch (IOException | NoSuchAlgorithmException e) {
            deleteQuietly(part);
            throw new StorageWriteException("failed to store object", e);
        }
    }

    @Override
    public InputStream load(String storageKey) {
        Path target = resolveWithinRoot(storageKey, true);
        try {
            return Files.newInputStream(target);
        } catch (IOException e) {
            throw new StorageWriteException("failed to load object", e);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveWithinRoot(storageKey, true);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new StorageWriteException("failed to delete object", e);
        }
    }

    // ==================== internals ====================

    /** Server-generated logical key: {@code yyyy/MM/<uuid>}. */
    private String generateStorageKey() {
        return YearMonth.now().toString().replace('-', '/') + "/" + UUID.randomUUID();
    }

    private Path resolveWithinRoot(String storageKey) {
        return resolveWithinRoot(storageKey, false);
    }

    /**
     * Resolves {@code storageKey} under the root with four defenses
     * (see class Javadoc): lexical validation, normalized containment,
     * symlink / special-file check, and bounded write enforcement.
     *
     * <p>For {@code load}/{@code delete} the target must exist and be a
     * regular file (not a symlink / directory / special file). A symlink
     * at a key position is never a legitimate storage object (server
     * keys are server-generated), so it is rejected as an invalid key
     * ({@link StorageInvalidKeyException}) — including a link pointing
     * outside the root — while a simply absent target throws
     * {@link StorageNotFoundException}. For {@code store} the target is
     * freshly generated and does not yet exist, so only the first two
     * defenses apply.
     */
    private Path resolveWithinRoot(String storageKey, boolean requireRegularFile) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new StorageInvalidKeyException("storageKey must not be blank");
        }
        validateStorageKeyLexically(storageKey);
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new StorageInvalidKeyException(
                    "storageKey escapes the storage root");
        }
        if (requireRegularFile) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target)) {
                    throw new StorageInvalidKeyException(
                            "storageKey must not reference a symbolic link");
                }
                throw new StorageNotFoundException(storageKey);
            }
        }
        return target;
    }

    /**
     * Rejects traversal segments and absolute / drive-qualified keys
     * on the RAW TEXT of the key, treating BOTH {@code /} and
     * {@code \\} as path separators. Server-generated keys are
     * {@code yyyy/MM/<uuid>}, so {@code .} / {@code ..} segments,
     * drive prefixes and rooted paths are never legitimate.
     */
    private void validateStorageKeyLexically(String storageKey) {
        if (storageKey.length() >= 2
                && Character.isLetter(storageKey.charAt(0))
                && storageKey.charAt(1) == ':') {
            throw new StorageInvalidKeyException(
                    "storageKey must not be drive-qualified");
        }
        if (storageKey.startsWith("/") || storageKey.startsWith("\\")) {
            throw new StorageInvalidKeyException(
                    "storageKey must be relative");
        }
        for (String segment : storageKey.split("[/\\\\]+")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new StorageInvalidKeyException(
                        "storageKey must not contain '.' or '..' path segments");
            }
        }
    }

    // package-private for operations/health indicators
    public Path getRoot() {
        return this.root;
    }

    private void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("failed to remove partial storage file", e);
        }
    }
}
