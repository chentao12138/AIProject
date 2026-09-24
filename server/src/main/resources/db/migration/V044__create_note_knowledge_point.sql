-- V044__create_note_knowledge_point.sql
-- Final Backend Feature Freeze — NoteKnowledgePoint M:N.

CREATE TABLE note_knowledge_point (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    note_id           BIGINT      NOT NULL,
    space_id          BIGINT      NOT NULL,
    knowledge_point_id BIGINT     NOT NULL,
    created_at        DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_note_kp_pair (note_id, knowledge_point_id),
    KEY idx_note_kp_space (space_id),
    KEY idx_note_kp_point (knowledge_point_id),

    CONSTRAINT fk_note_kp_note FOREIGN KEY (note_id) REFERENCES note (id),
    CONSTRAINT fk_note_kp_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_note_kp_point FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'NoteKnowledgePoint: M:N note<->knowledge_point (same-space invariant enforced by service).';
