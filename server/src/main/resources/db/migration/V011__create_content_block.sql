-- V011__create_content_block.sql
--
-- BUSINESS-006 — production ContentBlock table (EXTRACTED layer,
-- docs/data-model.md §6.3).
--
-- ContentBlock is the smallest referenceable unit of structured
-- body text (R-INGEST-008): HEADING / PARAGRAPH / LIST / TABLE /
-- FIGURE / CODE / FORMULA / OTHER. V1 (TXT/Markdown) produces
-- HEADING / PARAGRAPH / LIST / TABLE / CODE deterministically with no
-- AI; blocks are ordered by sort_order (document order) and carry
-- locator_json ({lineStart, lineEnd}, 1-based, normalized text) so
-- future KnowledgePoint provenance can cite an exact block range
-- (data-model.md §13: SourceDocument -> SourcePage -> ContentBlock).
--
-- Design notes:
--   * space_id + source_id + source_page_id: FK chain content_block ->
--     source_page -> source -> learning_space. Deliberately NO
--     owner_subject column — ownership is derived through the parent
--     chain; every read is an owner-scoped JOIN (BUSINESS-002/004
--     pattern) and writes validate source+asset FIRST via the
--     existing owner-scoped services.
--   * FKs use default RESTRICT (no CASCADE), matching V004..V010.
--     Never relax with CASCADE or FOREIGN_KEY_CHECKS=0.
--   * source_outline_node_id: reserved per data-model.md §6.3 but
--     deliberately has NO FK yet — the SourceOutlineNode table is
--     deferred (V1 TXT/MD has no outline extraction). The column
--     stays NULL; the FK is added together with the table in the
--     outline slice.
--   * normalized_text TEXT NOT NULL: up to 64KB per block; the
--     parser splits oversized paragraphs deterministically at line
--     boundaries (bounded blocks keep citations granular).
--   * structured_data_json / locator_json: TEXT columns storing JSON
--     text (server-side opaque in V1; no MySQL JSON type so the
--     MyBatis mapping stays plain String). locator_json is
--     populated from V1: {"lineStart":N,"lineEnd":M}.
--   * sort_order: 0-based document order, unique per (space, source)
--     via the index prefix.
--   * Indexes:
--       (space_id, source_id, sort_order, id)       — document-ordered
--                                                    block list
--       (space_id, source_page_id, sort_order, id)  — page-scoped
--                                                    block list
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching all siblings.

CREATE TABLE content_block (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    space_id               BIGINT        NOT NULL,
    source_id              BIGINT        NOT NULL,
    source_page_id         BIGINT        NULL,
    source_outline_node_id BIGINT        NULL,
    block_type             VARCHAR(32)   NOT NULL,
    sort_order             INT           NOT NULL,
    normalized_text        TEXT          NOT NULL,
    structured_data_json   TEXT          NULL,
    locator_json           TEXT          NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_content_block_space_source_order (space_id, source_id, sort_order, id),
    KEY idx_content_block_space_page_order (space_id, source_page_id, sort_order, id),

    CONSTRAINT fk_content_block_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_content_block_source
        FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT fk_content_block_page
        FOREIGN KEY (source_page_id) REFERENCES source_page (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ContentBlock: smallest referenceable unit of structured body text (data-model.md 6.3, R-INGEST-008). V1 TXT/MD deterministic blocks with locator_json line ranges for future provenance (BUSINESS-006).';
