package com.aistudy.server.space.dto;

import com.aistudy.server.space.entity.LearningSpace;

import java.time.LocalDateTime;

/**
 * BUSINESS-001 — typed response for all LearningSpace endpoints:
 * {@code POST /api/v1/spaces}, {@code GET /api/v1/spaces},
 * {@code GET /api/v1/spaces/{spaceId}}.
 *
 * <p>Deliberately does NOT include {@code ownerSubject}: the
 * api-guidelines.md response contract does not require the server to
 * echo the internal owner identity back to clients, and exposing it
 * would leak the JWT subject of the space owner to every consumer of
 * the space object (relevant once multi-owner collaboration exists).
 * The client already knows its own subject; it does not need it
 * echoed per space.
 *
 * <h3>Stable JSON contract</h3>
 *
 * <p>Field names are fixed camelCase ({@code createdAt},
 * {@code updatedAt}) — never snake_case — because this record is the
 * schema source for the OpenAPI contract
 * ({@code components.schemas.LearningSpaceResponse}) and ultimately
 * for the TypeScript client that will be generated from it. Any
 * rename here is a breaking API change.
 *
 * <h3>Timestamps</h3>
 *
 * <p>{@link LocalDateTime} serializes to ISO-8601 local date-time
 * (api-guidelines.md §16) via Jackson's JSR-310 module, e.g.
 * {@code "2026-09-05T10:30:00.123"}. The DB columns are
 * {@code DATETIME(6)} with no timezone (matching the V003 SPIKE
 * convention), so no zone offset is attached at this layer.
 *
 * @param id          DB auto-increment id
 * @param name        space name (non-blank, max 128)
 * @param description optional description, may be {@code null}
 * @param status      ACTIVE / ARCHIVED; v1 create always writes ACTIVE
 * @param createdAt   creation timestamp, set by the service
 * @param updatedAt   last-update timestamp, equals createdAt on create
 * @param archivedAt  archive timestamp, null when not archived
 */
public record LearningSpaceResponse(
        Long id,
        String name,
        String description,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime archivedAt
) {

    /**
     * Maps the persistence entity to the API response shape.
     * Excludes {@code ownerSubject} by design (see class Javadoc).
     *
     * @param space entity from the mapper; must not be {@code null}
     * @return the response record
     */
    public static LearningSpaceResponse from(LearningSpace space) {
        return new LearningSpaceResponse(
                space.getId(),
                space.getName(),
                space.getDescription(),
                space.getStatus(),
                space.getCreatedAt(),
                space.getUpdatedAt(),
                space.getArchivedAt()
        );
    }
}
