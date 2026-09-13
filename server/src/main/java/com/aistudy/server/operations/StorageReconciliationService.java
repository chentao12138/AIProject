package com.aistudy.server.operations;

import com.aistudy.server.config.properties.OperationsProperties;
import com.aistudy.server.source.asset.mapper.SourceAssetMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BUSINESS-025 — bounded storage reconciliation for the local backend.
 *
 * <p>Recursively scans the configured storage root for regular objects
 * (including the canonical {@code yyyy/MM/uuid} layout produced by
 * {@code LocalStorageService}) without following symbolic links. Objects
 * that are old enough and not referenced by {@code source_asset.storage_key}
 * are reported; only explicit CLEANUP mode deletes them.
 */
@Service
public class StorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(StorageReconciliationService.class);

    private final OperationsProperties operationsProperties;
    private final LocalStorageOperations storageOperations;
    private final SourceAssetMapper sourceAssetMapper;

    public StorageReconciliationService(OperationsProperties operationsProperties,
                                         LocalStorageOperations storageOperations,
                                         SourceAssetMapper sourceAssetMapper) {
        this.operationsProperties = operationsProperties;
        this.storageOperations = storageOperations;
        this.sourceAssetMapper = sourceAssetMapper;
    }

    public StorageReconciliationResult reconcile(boolean cleanup) {
        Path root = storageOperations.getRoot().toAbsolutePath().normalize();
        int maxScanEntries = Math.max(operationsProperties.getStorageReconciliation().getMaxScanEntries(), 0);
        long minAgeMillis = Math.max(operationsProperties.getStorageReconciliation().getMinAge().toMillis(), 0);

        List<String> scannedKeys = new ArrayList<>();
        int[] skippedRecent = new int[]{0};
        int[] skippedUnsafe = new int[]{0};
        boolean[] truncated = new boolean[]{false};

        Instant cutoff = Instant.now().minusMillis(minAgeMillis);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (truncated[0]) {
                        return FileVisitResult.TERMINATE;
                    }
                    // walkFileTree is invoked WITHOUT FOLLOW_LINKS, so symlink
                    // directories are not descended into. Extra guard for safety.
                    if (Files.isSymbolicLink(dir)) {
                        skippedUnsafe[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (truncated[0]) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        if (Files.isSymbolicLink(file)) {
                            skippedUnsafe[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            skippedUnsafe[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        String storageKey = storageKeyOf(root, file);
                        if (storageKey == null) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (Instant.ofEpochMilli(Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                                .toMillis()).isAfter(cutoff)) {
                            skippedRecent[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (scannedKeys.size() >= maxScanEntries) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        scannedKeys.add(storageKey);
                    } catch (IOException e) {
                        skippedUnsafe[0]++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    skippedUnsafe[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("storage reconciliation failed to scan root", e);
            throw new IllegalStateException("failed to scan storage root", e);
        }

        List<String> candidateOrphans = new ArrayList<>();
        Set<String> referenced = Collections.emptySet();
        if (!scannedKeys.isEmpty()) {
            try {
                referenced = new HashSet<>(sourceAssetMapper.selectExistingStorageKeys(scannedKeys));
            } catch (Exception e) {
                log.error("storage reconciliation failed to load referenced keys", e);
                throw new IllegalStateException("failed to load referenced storage keys", e);
            }
        }
        for (String key : scannedKeys) {
            if (!referenced.contains(key)) {
                candidateOrphans.add(key);
            }
        }

        List<String> deleted = Collections.emptyList();
        if (cleanup) {
            deleted = new ArrayList<>();
            for (String storageKey : new ArrayList<>(candidateOrphans)) {
                try {
                    Path target = root.resolve(storageKey).normalize();
                    if (!target.startsWith(root)) {
                        skippedUnsafe[0]++;
                        continue;
                    }
                    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                        skippedUnsafe[0]++;
                        continue;
                    }
                    Files.deleteIfExists(target);
                    deleted.add(storageKey);
                } catch (IOException e) {
                    skippedUnsafe[0]++;
                }
            }
            candidateOrphans = new ArrayList<>(candidateOrphans.size() - deleted.size());
        }

        int deletedCount = deleted.size();
        int candidateCount = candidateOrphans.size();
        return new StorageReconciliationResult(
                scannedKeys.size(),
                referenced.size(),
                candidateCount + deletedCount,
                deletedCount,
                skippedRecent[0],
                skippedUnsafe[0],
                truncated[0],
                cleanup ? "CLEANUP" : "DRY_RUN"
        );
    }

    /**
     * Canonical storage key: POSIX-slash relative path under root, matching
     * {@code LocalStorageService.generateStorageKey()} ({@code yyyy/MM/uuid}).
     */
    private static String storageKeyOf(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize()).normalize();
        String candidate = relative.toString().replace('\\', '/');
        if (candidate.isBlank()
                || candidate.startsWith("/")
                || candidate.contains("..")
                || candidate.length() >= 2
                    && Character.isLetter(candidate.charAt(0))
                    && candidate.charAt(1) == ':') {
            return null;
        }
        return candidate;
    }
}
