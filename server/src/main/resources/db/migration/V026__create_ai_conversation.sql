-- V026__create_ai_conversation.sql
-- AI-002: persistent AI tutor conversations scoped to LearningSpace + user.

CREATE TABLE ai_conversation (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    space_id        BIGINT        NOT NULL,
    user_subject    VARCHAR(128)  NOT NULL,
    title           VARCHAR(255)  NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    last_message_at DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_conversation_space_user_created (space_id, user_subject, created_at, id),
    KEY idx_ai_conversation_space_user_status (space_id, user_subject, status),
    CONSTRAINT fk_ai_conversation_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT chk_ai_conversation_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
