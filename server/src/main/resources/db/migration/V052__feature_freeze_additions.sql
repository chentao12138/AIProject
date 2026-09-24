-- V052__feature_freeze_additions.sql
--
-- Final Backend feature-freeze schema additions.
-- Renumbered from V047 (duplicate version with create_exam_blueprint).
-- Adds: system_config, ai_usage_record, ai_generation_job,
--       mastery_calibration_config; plus columns on mastery, ai_message, study_task.

-- ============================================================
-- §9.8  SystemConfig — typed whitelist registry, DB-backed
--         versioned settings for mutable keys.
-- ============================================================
CREATE TABLE system_config (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    config_key      VARCHAR(128)  NOT NULL,
    config_value    TEXT          NOT NULL,
    config_type     VARCHAR(32)   NOT NULL,
    version         INT           NOT NULL DEFAULT 1,
    validation_schema TEXT        NULL,
    restart_required TINYINT(1)   NOT NULL DEFAULT 0,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_system_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SystemConfig: typed whitelist registry for DB-backed mutable settings; ADMIN only.';

-- ============================================================
-- §8.1  AIUsageRecord — never stores secrets or raw upstream body
-- ============================================================
CREATE TABLE ai_usage_record (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject      VARCHAR(128)  NOT NULL,
    space_id          BIGINT        NULL,
    provider          VARCHAR(64)   NOT NULL,
    model             VARCHAR(128)  NOT NULL,
    purpose           VARCHAR(32)   NOT NULL,
    request_id        VARCHAR(128)  NULL,
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    total_tokens      INT           NULL,
    latency_ms        INT           NULL,
    status            VARCHAR(32)   NOT NULL,
    error_code        VARCHAR(64)   NULL,
    created_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    KEY idx_ai_usage_user_subject (user_subject),
    KEY idx_ai_usage_space_created (space_id, created_at, id),
    KEY idx_ai_usage_purpose (purpose),
    CONSTRAINT fk_ai_usage_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AIUsageRecord: per-AI-call usage ledger; NEVER stores API keys, Authorization headers, or raw upstream bodies.';

-- ============================================================
-- §8.6  AIGenerationJob — long-running AI batch jobs
-- ============================================================
CREATE TABLE ai_generation_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    job_type        VARCHAR(32)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    progress        INT           NOT NULL DEFAULT 0,
    requester       VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NULL,
    source_id       BIGINT        NULL,
    revision_id     BIGINT        NULL,
    success_count   INT           NOT NULL DEFAULT 0,
    failure_count   INT           NOT NULL DEFAULT 0,
    error_code      VARCHAR(64)   NULL,
    safe_message    TEXT          NULL,
    started_at      DATETIME(6)   NULL,
    finished_at     DATETIME(6)   NULL,
    retry_count     INT           NOT NULL DEFAULT 0,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    KEY idx_ai_job_space_status (space_id, status, created_at, id),
    KEY idx_ai_job_requester (requester),
    CONSTRAINT fk_ai_job_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'AIGenerationJob: long-running AI batch job tracking; worker-driven state transitions.';

-- ============================================================
-- §7.3  MasteryCalibrationConfig — versioned weight configuration
-- ============================================================
CREATE TABLE mastery_calibration_config (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    version               VARCHAR(32)   NOT NULL,
    confidence_full_samples INT         NOT NULL,
    recency_days_cap      INT           NOT NULL,
    volume_cap            INT           NOT NULL,
    active                TINYINT(1)    NOT NULL DEFAULT 0,
    created_at            DATETIME(6)   NOT NULL,
    updated_at            DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_calibration_version (version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'MasteryCalibrationConfig: versioned weight/confidence policy for mastery recompute; admin dry-run/recompute uses active version.';

-- ============================================================
-- §7.2  algorithm_version on mastery
-- ============================================================
ALTER TABLE mastery
    ADD COLUMN algorithm_version VARCHAR(16) NOT NULL DEFAULT '1.0'
    AFTER confidence;

-- ============================================================
-- §8.2  grounding_mode on ai_message
-- ============================================================
ALTER TABLE ai_message
    ADD COLUMN grounding_mode VARCHAR(16) NULL
    AFTER total_tokens;

-- ============================================================
-- §7.4  sort_order on study_task (reorder)
-- ============================================================
ALTER TABLE study_task
    ADD COLUMN sort_order INT NOT NULL DEFAULT 0
    AFTER updated_at,
    ADD KEY idx_study_task_plan_order (study_plan_id, sort_order, id);
