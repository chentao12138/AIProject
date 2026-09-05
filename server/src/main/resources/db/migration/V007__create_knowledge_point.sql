-- V007__create_knowledge_point.sql
--
-- BUSINESS-003 — production KnowledgePoint table.
--
-- KnowledgePoint is the core learning unit (docs/data-model.md §8.1),
-- belonging to one LearningSpace and optionally to one
-- KnowledgeCategory inside that space.
--
-- This slice covers USER_CURATED manual knowledge points only:
--   origin_type = 'USER_CURATED'
--   status      = 'DRAFT' -> 'PUBLISHED'  (manual publish lifecycle)
--
-- SOURCE_DERIVED / AI_DERIVED / ADMIN_CURATED are documented values
-- (data-model.md §8.1) but are NOT produced by this slice: they
-- depend on ContentBlock/ingestion and on a formal Admin API, neither
-- of which exists yet. KnowledgePointSource (M:N to ContentBlock) is
-- deliberately NOT created.
--
-- Design notes:
--   * Ownership derived via space_id -> learning_space.owner_subject.
--     No owner_subject column.
--   * category_id NULL = uncategorized. When non-NULL the service
--     verifies the category belongs to the SAME space_id (and same
--     owner) with scoped SQL — the FK alone cannot prove that.
--   * FKs: space_id -> learning_space(id), category_id ->
--     knowledge_category(id). No ON DELETE CASCADE.
--   * created_by_user_id: VARCHAR(128) NULL, audit field (who created
--     it), filled from JWT sub. NOT returned in API responses.
--   * difficulty: free-form bounded string (no enum decided in docs);
--     NULL = not set. Max 32 chars.
--   * status: 'DRAFT' | 'PUBLISHED' this slice. ARCHIVED / REJECTED /
--     PROCESSING / NEEDS_REVIEW belong to ingestion/admin flows and
--     are intentionally not introduced.
--   * deleted_at: soft-delete marker. ALL business reads filter
--     `deleted_at IS NULL` (service/mapper contract). No delete API
--     exists this round.
--   * Indexes per data-model.md guidance "KnowledgePoint(spaceId,
--     status, categoryId)": idx_knowledge_point_space_status_category
--     (space_id, status, category_id) for the canonical listing, plus
--     idx_knowledge_point_space_created (space_id, created_at, id)
--     for newest-first listing. Both lead with space_id.
--   * charset/collation utf8mb4 / utf8mb4_unicode_ci.

CREATE TABLE knowledge_point (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    space_id            BIGINT        NOT NULL,
    category_id         BIGINT        NULL,
    title               VARCHAR(255)  NOT NULL,
    summary             VARCHAR(1000) NULL,
    content             TEXT          NOT NULL,
    origin_type         VARCHAR(32)   NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    difficulty          VARCHAR(32)   NULL,
    created_by_user_id  VARCHAR(128)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    published_at        DATETIME(6)   NULL,
    deleted_at          DATETIME(6)   NULL,

    PRIMARY KEY (id),

    KEY idx_knowledge_point_space_status_category
        (space_id, status, category_id),
    KEY idx_knowledge_point_space_created
        (space_id, created_at, id),

    CONSTRAINT fk_knowledge_point_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_knowledge_point_category
        FOREIGN KEY (category_id) REFERENCES knowledge_category (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'KnowledgePoint: core learning unit inside one LearningSpace; USER_CURATED DRAFT->PUBLISHED lifecycle in BUSINESS-003; business reads filter deleted_at IS NULL.';
