-- V049__review_state_and_sm2.sql
--
-- BUSINESS-017 — ReviewState entity + SM-2 scheduling policy.
-- Adds the review_state table and columns required by §5.4/5.5/5.7.

-- ============================================================
-- §5.7  ReviewState — per-target SM-2 spaced-repetition state
-- ============================================================
CREATE TABLE review_state (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject     VARCHAR(128)  NOT NULL,
    space_id         BIGINT        NOT NULL,
    target_type      VARCHAR(32)   NOT NULL,
    target_id        BIGINT        NOT NULL,
    ease_factor      DOUBLE        NOT NULL DEFAULT 2.5,
    interval_days    INT           NOT NULL DEFAULT 1,
    repetitions      INT           NOT NULL DEFAULT 0,
    policy_version   INT           NOT NULL DEFAULT 1,
    next_due_at      DATETIME(6)   NULL,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_review_state_target (user_subject, space_id, target_type, target_id),
    KEY idx_review_state_user_space (user_subject, space_id),
    KEY idx_review_state_due (next_due_at),
    CONSTRAINT fk_review_state_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ReviewState: per-target SM-2 spaced-repetition scheduling state.';

-- ============================================================
-- §5.4  dismissedAt on wrong_question
-- ============================================================
ALTER TABLE wrong_question
    ADD COLUMN dismissed_at DATETIME(6) NULL AFTER updated_at,
    ADD KEY idx_wrong_question_dismissed (user_subject, space_id, dismissed_at);

-- ============================================================
-- §5.3  correctAnswerSummary on practice_answer
-- ============================================================
ALTER TABLE practice_answer
    ADD COLUMN correct_answer_summary TEXT NULL AFTER feedback_json;
