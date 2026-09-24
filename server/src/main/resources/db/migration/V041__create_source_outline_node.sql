-- V041__create_source_outline_node.sql
-- Final Backend Feature Freeze — SourceOutlineNode (document tree).

CREATE TABLE source_outline_node (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    space_id      BIGINT       NOT NULL,
    source_id     BIGINT       NOT NULL,
    parent_id     BIGINT       NULL,
    node_type     VARCHAR(32)  NOT NULL,
    title         VARCHAR(255) NOT NULL,
    number_label  VARCHAR(64)  NULL,
    sort_order    INT          NOT NULL,
    start_page    INT          NULL,
    end_page      INT          NULL,
    status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    confidence    DOUBLE      NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    KEY idx_outline_space_source (space_id, source_id, sort_order, id),

    CONSTRAINT fk_outline_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_outline_source FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SourceOutlineNode: document outline tree (BOOK/CHAPTER/SECTION/SUBSECTION).';
