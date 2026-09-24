-- V050__create_question_source.sql
-- Final Backend Feature Freeze — QuestionSource M:N.
--
-- V013 already creates this table with the identical shape, so a plain
-- CREATE TABLE made every from-scratch migration chain die here with
-- "Table 'question_source' already exists". Kept as IF NOT EXISTS so the
-- version stays in history and any database that is genuinely missing the
-- table still gets it.

CREATE TABLE IF NOT EXISTS question_source (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    space_id         BIGINT      NOT NULL,
    question_id      BIGINT      NOT NULL,
    content_block_id BIGINT      NOT NULL,
    relation_type    VARCHAR(32) NULL,
    created_at       DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_question_source_pair (question_id, content_block_id),
    KEY idx_question_source_space (space_id),
    KEY idx_question_source_block (content_block_id),

    CONSTRAINT fk_question_source_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_question_source_question FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_question_source_block FOREIGN KEY (content_block_id) REFERENCES content_block (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'QuestionSource: M:N question<->content_block provenance.';
