-- V006__create_knowledge_category.sql
--
-- BUSINESS-003 — production KnowledgeCategory table.
--
-- KnowledgeCategory is the user-facing knowledge classification tree
-- INSIDE one LearningSpace (docs/data-model.md §4.2). Every category
-- belongs to exactly one space; the tree structure is expressed with
-- a nullable parent_id.
--
-- Design notes:
--   * Ownership: a category's owner is derived from its LearningSpace
--     (space_id -> learning_space.owner_subject). No owner_subject
--     column — consistent with the Source (V005) design.
--   * parent_id NULL = root category; non-NULL = child. The parent
--     invariant ("parent must belong to the SAME space") is enforced
--     in the service layer with owner-scoped SQL — the FK below only
--     guarantees the parent row exists, not same-space membership.
--   * FK space_id -> learning_space(id), parent_id ->
--     knowledge_category(id). No ON DELETE CASCADE: the project does
--     not implement deletion yet, RESTRICT semantics are fine.
--   * Index: (space_id, parent_id, sort_order, id) supports the
--     canonical listing query `WHERE space_id = ? AND parent_id = ?
--     ORDER BY sort_order ASC, id ASC` as a covering index, and the
--     leading space_id column serves any space-scoped scan. No
--     uniqueness constraint on (space_id, name) — MySQL NULL-parent
--     uniqueness semantics are intentionally avoided this round.
--   * charset/collation utf8mb4 / utf8mb4_unicode_ci, matching
--     V003/V004/V005.

CREATE TABLE knowledge_category (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    space_id    BIGINT       NOT NULL,
    parent_id   BIGINT       NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512) NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    KEY idx_knowledge_category_space_parent_sort
        (space_id, parent_id, sort_order, id),

    CONSTRAINT fk_knowledge_category_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_knowledge_category_parent
        FOREIGN KEY (parent_id) REFERENCES knowledge_category (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'KnowledgeCategory: user-facing knowledge classification tree inside one LearningSpace; parent must belong to the same space (service-enforced).';
