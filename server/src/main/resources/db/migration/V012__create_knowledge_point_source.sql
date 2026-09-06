-- V012__create_knowledge_point_source.sql
--
-- BUSINESS-007 — production KnowledgePointSource table (provenance,
-- docs/data-model.md §8.2, R-KNOW-002, ADR-039).
--
-- KnowledgePointSource is the M:N provenance relation between a
-- KnowledgePoint and the ContentBlocks it was derived from (a point
-- may cite many blocks; a block may be cited by many points —
-- data-model.md §8.2 deliberately does NOT restrict a point to one
-- page). This slice builds the provenance INFRASTRUCTURE only — no
-- AI extraction populates it yet (runbook §7.3).
--
-- Design notes:
--   * space_id is stored EXPLICITLY (not only derivable): an ordinary
--     FK cannot prove that knowledge_point and content_block belong
--     to the SAME LearningSpace, and that invariant is CRITICAL
--     (runbook §7.2: "a KnowledgePoint and its provenance targets
--     must belong to the same LearningSpace"). Enforcement is
--     two-layered: the service validates BOTH endpoints with
--     owner-scoped reads against the path spaceId BEFORE inserting,
--     and every read is an owner-scoped JOIN.
--   * FKs use default RESTRICT (no CASCADE), matching V004..V011.
--     Never relax with CASCADE or FOREIGN_KEY_CHECKS=0.
--   * uk_knowledge_point_source_pair (knowledge_point_id,
--     content_block_id): one pair can be linked at most once;
--     the service treats re-linking an existing pair as a no-op.
--   * relation_type / relevance_score: documented optional fields
--     (data-model.md §8.2); NULL in V1 (no producer yet — reserved
--     for future ranking/typing of citations).
--   * created_by_user_id: audit metadata (who created the link),
--     NOT an authorization key (cf. V005 source).
--   * Indexes:
--       (space_id, knowledge_point_id, id) — point → blocks
--       (space_id, content_block_id, id)   — block → points
--                                         (reverse lookup, future AI
--                                         retrieval / citation audit)
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching all siblings.

CREATE TABLE knowledge_point_source (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    space_id             BIGINT        NOT NULL,
    knowledge_point_id   BIGINT        NOT NULL,
    content_block_id     BIGINT        NOT NULL,
    relation_type        VARCHAR(32)   NULL,
    relevance_score      DOUBLE        NULL,
    created_by_user_id   VARCHAR(128)  NULL,
    created_at           DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_knowledge_point_source_pair (knowledge_point_id, content_block_id),
    KEY idx_knowledge_point_source_space_point (space_id, knowledge_point_id, id),
    KEY idx_knowledge_point_source_space_block (space_id, content_block_id, id),

    CONSTRAINT fk_knowledge_point_source_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_knowledge_point_source_point
        FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id),
    CONSTRAINT fk_knowledge_point_source_block
        FOREIGN KEY (content_block_id) REFERENCES content_block (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'KnowledgePointSource: M:N provenance KnowledgePoint <-> ContentBlock (data-model.md 8.2, R-KNOW-002). Same-space invariant enforced by scoped service reads + owner-scoped JOIN reads; relation_type/relevance_score NULL in V1 (BUSINESS-007).';
