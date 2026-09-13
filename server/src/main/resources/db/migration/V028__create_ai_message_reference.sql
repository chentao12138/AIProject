-- V028__create_ai_message_reference.sql
-- AI-005: grounded answer references for ASSISTANT messages.

CREATE TABLE ai_message_reference (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    message_id      BIGINT        NOT NULL,
    ordinal         INT           NOT NULL,
    reference_type  VARCHAR(32)   NOT NULL,
    entity_id       BIGINT        NULL,
    title           VARCHAR(512)  NULL,
    snippet         TEXT          NULL,
    locator         VARCHAR(512)  NULL,
    created_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ai_message_reference_message_ordinal (message_id, ordinal),
    CONSTRAINT fk_ai_message_reference_message
        FOREIGN KEY (message_id) REFERENCES ai_message (id),
    CONSTRAINT chk_ai_message_reference_type
        CHECK (reference_type IN ('SOURCE','CONTENT_BLOCK','KNOWLEDGE_POINT','QUESTION','WRONG_QUESTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
