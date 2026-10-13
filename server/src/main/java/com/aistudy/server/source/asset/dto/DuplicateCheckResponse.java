package com.aistudy.server.source.asset.dto;

import com.aistudy.server.source.asset.service.SourceAssetService;

/**
 * F-13 — typed dry-run response of the duplicate precheck
 * ({@code GET .../assets/duplicate-check}), keyed by
 * (space, sha256) with the display-name cross-check.
 *
 * <p>The client uses this to choose its upload policy BEFORE sending
 * bytes: {@code duplicate=true} means SKIP will reuse
 * {@code matchedAssetId}; {@code SAME_NAME_DIFFERENT_HASH} means the
 * upload is a content change, not a duplicate.
 *
 * @param scenario        {@code NONE} | {@code SAME_HASH_SAME_NAME} |
 *                        {@code SAME_HASH_DIFFERENT_NAME} |
 *                        {@code SAME_NAME_DIFFERENT_HASH}
 * @param duplicate       true when (space, sha256) already exists
 * @param matchedAssetId  id of the existing hash match (null when
 *                        duplicate is false)
 * @param matchedName     display name of the hash match — lets the
 *                        client render "already imported as X"
 * @param matchedSizeBytes exact stored size of the hash match
 * @param nameConflictId  id of a same-name different-bytes asset in the
 *                        space (null when names do not collide)
 */
public record DuplicateCheckResponse(
        String scenario,
        boolean duplicate,
        Long matchedAssetId,
        String matchedName,
        Long matchedSizeBytes,
        Long nameConflictId) {

    /**
     * Maps a service probe to the wire response.
     *
     * @param probe service result (never null)
     */
    public static DuplicateCheckResponse from(SourceAssetService.DuplicateProbe probe) {
        Long matchedId = probe.matched() != null ? probe.matched().getId() : null;
        String matchedName = probe.matched() != null ? probe.matched().getOriginalName() : null;
        Long matchedSize = probe.matched() != null ? probe.matched().getSizeBytes() : null;
        Long nameConflict = probe.nameConflictAsset() != null
                ? probe.nameConflictAsset().getId() : null;
        return new DuplicateCheckResponse(probe.scenario(), probe.duplicate(),
                matchedId, matchedName, matchedSize, nameConflict);
    }
}
