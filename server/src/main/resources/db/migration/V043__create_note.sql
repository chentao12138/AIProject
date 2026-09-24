-- V043__create_note.sql
-- Final Backend Feature Freeze — Note domain.

CREATE TABLE note (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    space_id        BIGINT       NOT NULL,
    user_subject_id VARCHAR(128) NOT NULL,
    title           VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    content_format  VARCHAR(32)  NOT NULL DEFAULT 'PLAIN_TEXT',
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    archived_at     DATETIME(6)  NULL,

    PRIMARY KEY (id),

    KEY idx_note_space_user (space_id, user_subject_id, id),

    CONSTRAINT fk_note_space FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Note: user note linked to KnowledgePoint and SourceReference.';
