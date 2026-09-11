package com.aistudy.server.ingestion.job.dto;

import jakarta.validation.constraints.NotNull;

/**
 * BUSINESS-005 — request body for
 * {@code POST /api/v1/spaces/{spaceId}/sources/{sourceId}/ingestion-jobs}.
 *
 * <p>Only the asset to ingest is client-supplied. Everything else —
 * spaceId/sourceId from the path, owner from the JWT, status/stage/
 * progress/timestamps from the server.
 *
 * @param assetId the source asset to ingest (must belong to the
 *                source AND the space, validated server-side)
 */
public record CreateIngestionJobRequest(@NotNull Long assetId) {
}
