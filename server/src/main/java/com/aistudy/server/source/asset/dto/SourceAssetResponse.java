package com.aistudy.server.source.asset.dto;

import com.aistudy.server.source.asset.entity.SourceAsset;

import java.time.LocalDateTime;

/**
 * BUSINESS-004 — typed SourceAsset response.
 *
 * <p>Deliberately does NOT expose {@code storageKey} (the physical
 * object location is storage-internal, ADR-033) nor
 * {@code originalRelativePath} (fixed null this round). The client
 * sees identity, role, display name, type and integrity metadata
 * only.
 *
 * @param id           asset id
 * @param spaceId      parent space id
 * @param sourceId     parent source id
 * @param assetRole    ORIGINAL_PACKAGE | ORIGINAL_FILE (server-set)
 * @param originalName display/audit basename of the uploaded file
 * @param mimeType     stored MIME (may be application/octet-stream fallback)
 * @param sizeBytes    exact stored byte count
 * @param sha256       lowercase hex SHA-256 of the RAW bytes
 * @param createdAt    creation timestamp
 * @param isDuplicate  true when another (space, sha256) asset existed
 *                     before this upload decision
 * @param uploadDecision F-13 outcome of an upload: {@code STORED} |
 *                     {@code REUSED_EXISTING} | {@code IMPORTED_AGAIN};
 *                     {@code null} on plain reads (list / detail)
 * @param duplicateOfId pre-existing asset id with the same
 *                     (space, sha256), or {@code null}
 */
public record SourceAssetResponse(
        Long id,
        Long spaceId,
        Long sourceId,
        String assetRole,
        String originalName,
        String mimeType,
        Long sizeBytes,
        String sha256,
        LocalDateTime createdAt,
        boolean isDuplicate,
        String uploadDecision,
        Long duplicateOfId) {

    public static SourceAssetResponse from(SourceAsset asset) {
        return from(asset, false);
    }

    public static SourceAssetResponse from(SourceAsset asset, boolean isDuplicate) {
        return from(asset, isDuplicate, null, null);
    }

    /**
     * Full F-13 form.
     *
     * @param asset          persisted (or reused) asset row
     * @param isDuplicate    duplicate flag derived by the service
     * @param uploadDecision STORED | REUSED_EXISTING | IMPORTED_AGAIN
     *                       or null for non-upload reads
     * @param duplicateOfId  pre-existing twin id when duplicated
     */
    public static SourceAssetResponse from(SourceAsset asset,
                                          boolean isDuplicate,
                                          String uploadDecision,
                                          Long duplicateOfId) {
        return new SourceAssetResponse(
                asset.getId(),
                asset.getSpaceId(),
                asset.getSourceId(),
                asset.getAssetRole(),
                asset.getOriginalName(),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getSha256(),
                asset.getCreatedAt(),
                isDuplicate,
                uploadDecision,
                duplicateOfId);
    }
}
