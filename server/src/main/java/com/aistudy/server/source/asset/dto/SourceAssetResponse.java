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
        LocalDateTime createdAt) {

    public static SourceAssetResponse from(SourceAsset asset) {
        return new SourceAssetResponse(
                asset.getId(),
                asset.getSpaceId(),
                asset.getSourceId(),
                asset.getAssetRole(),
                asset.getOriginalName(),
                asset.getMimeType(),
                asset.getSizeBytes(),
                asset.getSha256(),
                asset.getCreatedAt());
    }
}
