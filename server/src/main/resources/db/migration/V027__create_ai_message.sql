-- V027__create_ai_message.sql
-- AI-002: conversation messages (USER / ASSISTANT only; SYSTEM is server-side only).

CREATE TABLE ai_message (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    conversation_id     BIGINT        NOT NULL,
    role                VARCHAR(32)   NOT NULL,
    content             LONGTEXT      NOT NULL,
    provider            VARCHAR(64)   NULL,
    model               VARCHAR(128)  NULL,
    prompt_tokens       INT           NULL,
    completion_tokens   INT           NULL,
    total_tokens        INT           NULL,
    created_at          DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_message_conversation_created (conversation_id, created_at, id),
    CONSTRAINT fk_ai_message_conversation
        FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id),
    CONSTRAINT chk_ai_message_role
        CHECK (role IN ('USER', 'ASSISTANT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
