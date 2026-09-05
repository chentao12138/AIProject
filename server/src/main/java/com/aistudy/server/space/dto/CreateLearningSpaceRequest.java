package com.aistudy.server.space.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BUSINESS-001 — request body for {@code POST /api/v1/spaces}.
 *
 * <p>Deliberately does NOT contain {@code ownerSubject}: the owner is
 * resolved server-side from the authenticated JWT subject
 * (api-guidelines.md §2: "禁止通过 query/header 让客户端自行声明
 * userId 作为所有权依据"). A client can never choose who owns a
 * space it creates.
 *
 * <h3>Validation</h3>
 *
 * <ul>
 *   <li>{@code name} — {@code @NotBlank} (non-null, non-empty after
 *       trim) per api-guidelines.md §14 (Bean Validation on request
 *       DTOs). Length capped at 128 to match the
 *       {@code learning_space.name VARCHAR(128)} column (V004).</li>
 *   <li>{@code description} — optional (nullable column
 *       {@code VARCHAR(512)}); capped at 512. {@code null} and
 *       {@code ""} are both accepted and stored as-is.</li>
 * </ul>
 *
 * <p>Java record, following the SPIKE-005 typed-DTO style: record
 * components give springdoc a natural source for the OpenAPI request
 * schema ({@code components.schemas.CreateLearningSpaceRequest})
 * with explicit property types — exactly what the deferred
 * TypeScript client generation will consume.
 */
public record CreateLearningSpaceRequest(
        @NotBlank(message = "name must not be blank")
        @Size(max = 128, message = "name must be at most 128 characters")
        String name,

        @Size(max = 512, message = "description must be at most 512 characters")
        String description
) {
}
