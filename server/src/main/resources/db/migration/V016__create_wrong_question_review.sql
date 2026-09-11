-- V016__create_wrong_question_review.sql
--
-- BUSINESS-011 — WrongQuestion + ReviewTask/ReviewRecord
-- (docs/data-model.md §12, §13).
--
-- wrong_question   one row per (user_subject, space_id, question_id)
--                  tracking wrong history + current status
-- review_task      scheduled review of a QUESTION or KNOWLEDGE_POINT
-- review_record    completion history of a review task
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub (data-model userId, no
--     User table yet); every read scopes user_subject + space_id +
--     owner (JOIN learning_space).
--   * wrong_question.status V1: ACTIVE -> IMPROVING -> MASTERED
--     (service policy: any new wrong -> ACTIVE; correct review ->
--     IMPROVING; second consecutive correct -> MASTERED; DISMISSED
--     reserved, no API). Unique (user_subject, space_id, question_id)
--     per data-model §12.
--   * review_task.target_type/target_id is a CONTROLLED polymorphic
--     reference (KNOWLEDGE_POINT | QUESTION) validated by the
--     service — deliberately NO FK on target_id (data-model §13.1).
--     FK only to learning_space.
--   * review_record.result: CORRECT | WRONG (service-validated);
--     score/durationMs/notes optional.
--   * due policy lives in ReviewSchedulePolicy (V1: first wrong +1d;
--     correct +3d; consecutive correct +7d; wrong again +1d) —
--     replaceable, never hardcoded in services.
--   * No CASCADE; utf8mb4; practical indexes.

CREATE TABLE wrong_question (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    question_id     BIGINT        NOT NULL,
    first_wrong_at  DATETIME(6)   NOT NULL,
    last_wrong_at   DATETIME(6)   NOT NULL,
    wrong_count     INT           NOT NULL DEFAULT 1,
    last_correct_at DATETIME(6)   NULL,
    status          VARCHAR(32)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_wrong_question_user_space_question
        (user_subject, space_id, question_id),
    KEY idx_wrong_question_space_status (space_id, status),

    CONSTRAINT fk_wrong_question_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_wrong_question_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'WrongQuestion: one aggregate row per (user, space, question); wrong_count++ and last_wrong_at refresh on every wrong answer; status ACTIVE/IMPROVING/MASTERED (DISMISSED reserved).';

CREATE TABLE review_task (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    target_type     VARCHAR(32)   NOT NULL,
    target_id       BIGINT        NOT NULL,
    reason          VARCHAR(255)  NULL,
    due_at          DATETIME(6)   NOT NULL,
    priority        VARCHAR(16)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_review_task_user_space_due (user_subject, space_id, due_at),
    KEY idx_review_task_space_status (space_id, status),

    CONSTRAINT fk_review_task_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ReviewTask: scheduled review of a QUESTION or KNOWLEDGE_POINT (controlled polymorphic target, service-validated, no FK on target_id); status PENDING/COMPLETED; due_at driven by ReviewSchedulePolicy.';

CREATE TABLE review_record (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    review_task_id  BIGINT        NOT NULL,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    result          VARCHAR(16)   NOT NULL,
    score           INT           NULL,
    completed_at    DATETIME(6)   NOT NULL,
    duration_ms     INT           NULL,
    notes           VARCHAR(1000) NULL,
    created_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_review_record_user_space (user_subject, space_id),
    KEY idx_review_record_task (review_task_id),

    CONSTRAINT fk_review_record_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_review_record_task
        FOREIGN KEY (review_task_id) REFERENCES review_task (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ReviewRecord: completion history of a review task (result CORRECT/WRONG); immutable history row, never soft-deleted.';
