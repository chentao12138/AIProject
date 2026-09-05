package com.aistudy.server.source.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * BUSINESS-002 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/sources}.
 *
 * <p>Deliberately does NOT contain {@code spaceId} (it is already in
 * the path) and does NOT contain {@code ownerSubject} /
 * {@code createdByUserId} (both come from the authenticated request
 * server-side). A client can never choose which space a source
 * belongs to or who created it.
 *
 * <h3>Fields</h3>
 *
 * <ul>
 *   <li>{@code title} — human-readable material title
 *       (data-model.md §5.1 calls this field {@code title}).
 *       Required, non-blank, max 255 (matches V005
 *       {@code source.title VARCHAR(255)}).</li>
 *   <li>{@code sourceType} — required, one of the DOCUMENTED values
 *       from data-model.md §5.1:
 *       {@code DESKTOP_UPLOAD}, {@code DESKTOP_FOLDER_IMPORT},
 *       {@code ADMIN_UPLOAD}, {@code ADMIN_MANUAL}. No invented
 *       enum values. This slice only registers metadata — the
 *       sourceType records the intended origin channel; no upload
 *       behavior is implemented.</li>
 * </ul>
 *
 * <p>File-metadata fields (originalFilename, mimeType, sizeBytes,
 * storageKey) are deliberately NOT exposed this round: there is no
 * upload behavior, and the docs place those on SourceAsset
 * (data-model.md §5.2), not SourceDocument. They arrive with the
 * upload slice.
 */
public record CreateSourceRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 255, message = "title must be at most 255 characters")
        String title,

        @NotBlank(message = "sourceType must not be blank")
        @Pattern(
                regexp = "DESKTOP_UPLOAD|DESKTOP_FOLDER_IMPORT|ADMIN_UPLOAD|ADMIN_MANUAL",
                message = "sourceType must be one of the documented values: "
                        + "DESKTOP_UPLOAD, DESKTOP_FOLDER_IMPORT, ADMIN_UPLOAD, ADMIN_MANUAL")
        String sourceType
) {
}
