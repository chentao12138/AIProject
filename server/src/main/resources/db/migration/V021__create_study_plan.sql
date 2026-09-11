-- V021__create_study_plan.sql
--
-- BUSINESS-016 — StudyPlan / StudyTask (docs/data-model.md §16).
--
-- study_plan   one current plan of (user, space); V1 lifecycle:
--              ACTIVE -> COMPLETED (all tasks DONE/SKIPPED). At most
--              one ACTIVE plan per (user_subject, space_id) —
--              service-enforced 409 on generate conflict (no partial
--              unique index in MySQL; single-user V1, documented).
-- study_task   one generated task of a plan.
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub (data-model userId); all
--     reads scoped user_subject + space_id + owner (JOIN).
--   * task_type V1: LEARN | PRACTICE | REVIEW | EXAM. status V1:
--     TODO | IN_PROGRESS | DONE | SKIPPED. priority V1: HIGH |
--     MEDIUM | LOW (mirrors review-task priority).
--   * target_type/target_id: CONTROLLED polymorphic reference
--     (QUESTION | KNOWLEDGE_POINT | EXAM), service-validated, NO FK
--     on target_id (same policy as review_task, data-model §13.1).
--   * Tasks are DETERMINISTICALLY generated from current backend
--     facts (pending review tasks, weakest mastery points, latest
--     diagnosis as reason) — no AI, no randomization.
--   * Completion of a study task flips TODO/IN_PROGRESS -> DONE and
--     does NOT create review_record (review history belongs to the
--     review-task completion API only).
--   * FKs explicit, no CASCADE; utf8mb4; practical indexes.

CREATE TABLE study_plan (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    name            VARCHAR(255)  NOT NULL,
    start_date      DATETIME(6)   NULL,
    end_date        DATETIME(6)   NULL,
    status          VARCHAR(32)   NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_study_plan_user_space (user_subject, space_id, created_at, id),

    CONSTRAINT fk_study_plan_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'StudyPlan: current plan of one (user, space); at most one ACTIVE plan (service-enforced 409), ACTIVE->COMPLETED when all tasks are DONE/SKIPPED.';

CREATE TABLE study_task (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    study_plan_id   BIGINT        NOT NULL,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    task_type       VARCHAR(32)   NOT NULL,
    target_type     VARCHAR(32)   NOT NULL,
    target_id       BIGINT        NULL,
    title           VARCHAR(255)  NOT NULL,
    reason          VARCHAR(500)  NULL,
    due_at          DATETIME(6)   NULL,
    priority        VARCHAR(16)   NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    completed_at    DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_study_task_plan (study_plan_id),
    KEY idx_study_task_user_space (user_subject, space_id),
    KEY idx_study_task_space_status (space_id, status),

    CONSTRAINT fk_study_task_plan
        FOREIGN KEY (study_plan_id) REFERENCES study_plan (id),
    CONSTRAINT fk_study_task_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'StudyTask: deterministic task of one plan; controlled polymorphic target (QUESTION|KNOWLEDGE_POINT|EXAM, no FK on target_id); status TODO/IN_PROGRESS/DONE/SKIPPED.';
