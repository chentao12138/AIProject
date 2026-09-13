-- V029__create_ai_provider_settings.sql
-- AI-009: per-user runtime AI provider settings.
-- Ownership key is the authenticated JWT subject. Secrets live in
-- ai_provider_secret (same user_subject).

CREATE TABLE ai_provider_settings (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(64)   NOT NULL,
    enabled         TINYINT(1)    NOT NULL DEFAULT 0,
    provider        VARCHAR(64)   NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
    preset          VARCHAR(32)   NULL,
    base_url        VARCHAR(512)  NULL,
    model           VARCHAR(128)  NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_ai_provider_settings_user (user_subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
