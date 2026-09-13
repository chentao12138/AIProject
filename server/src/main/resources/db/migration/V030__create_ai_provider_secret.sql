-- V030__create_ai_provider_secret.sql
-- AI-009: per-user encrypted API key blob. Ciphertext only; master key is
-- AISTUDY_AI_SECRET_KEY from the runtime environment, never committed.

CREATE TABLE ai_provider_secret (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(64)   NOT NULL,
    ciphertext      TEXT          NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_provider_secret_user (user_subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
