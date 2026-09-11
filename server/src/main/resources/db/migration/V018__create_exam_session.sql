-- V018__create_exam_session.sql
--
-- BUSINESS-013 — Exam attempt / answer / result
-- (docs/data-model.md §14.5, §14.6, §14.7).
--
-- exam_attempt   one exam run of a user against a PUBLISHED paper
-- exam_answer    one answer of an attempt slot
-- exam_result    immutable result row computed at submit
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub (data-model userId); all
--     reads scoped user_subject + space_id + owner (JOIN).
--   * exam_attempt.status V1: NOT_STARTED -> IN_PROGRESS (start) ->
--     SUBMITTED (submit). SCORED/ABORTED reserved (data-model
--     enumerates them; V1 submit computes + persists exam_result,
--     status stays SUBMITTED). Repeated submit → 409; submit after
--     deadline → 409 EXAM_DEADLINE_EXCEEDED (server clock is the
--     authority, no background timer).
--   * deadline_at = started_at + exam.time_limit_minutes when the
--     exam declares a duration; NULL otherwise.
--   * exam_answer graded against the paper's FROZEN
--     question_snapshot_json by the shared QuestionAnswerEvaluator
--     (same semantic as practice). grading_status V1: 'GRADED'
--     (objective) / 'UNGRADED' (SHORT_ANSWER, no AI/human grading).
--     NO correct-answer fields are ever returned before submit
--     (api-guidelines.md §9): answer POST responses carry only the
--     stored identity; the result endpoint reveals correctness.
--   * exam_result is an immutable history row (never soft-deleted).
--   * FKs explicit, no CASCADE; utf8mb4; practical indexes.

CREATE TABLE exam_attempt (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    exam_id         BIGINT        NOT NULL,
    exam_paper_id   BIGINT        NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    started_at      DATETIME(6)   NULL,
    deadline_at     DATETIME(6)   NULL,
    submitted_at    DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_exam_attempt_user_space (user_subject, space_id, status),
    KEY idx_exam_attempt_space_exam (space_id, exam_id),
    KEY idx_exam_attempt_paper (exam_paper_id),

    CONSTRAINT fk_exam_attempt_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_attempt_exam
        FOREIGN KEY (exam_id) REFERENCES exam (id),
    CONSTRAINT fk_exam_attempt_paper
        FOREIGN KEY (exam_paper_id) REFERENCES exam_paper (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamAttempt: one exam run per (user, space, published paper); NOT_STARTED->IN_PROGRESS->SUBMITTED; deadline_at from exam duration (server clock authoritative).';

CREATE TABLE exam_answer (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    exam_attempt_id   BIGINT        NOT NULL,
    exam_question_id  BIGINT        NOT NULL,
    space_id          BIGINT        NOT NULL,
    answer_data_json  TEXT          NOT NULL,
    score             INT           NULL,
    is_correct        TINYINT(1)    NULL,
    answered_at       DATETIME(6)   NULL,
    grading_status    VARCHAR(32)   NOT NULL,
    feedback          TEXT          NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_exam_answer_attempt_question (exam_attempt_id, exam_question_id),
    KEY idx_exam_answer_space_attempt (space_id, exam_attempt_id),

    CONSTRAINT fk_exam_answer_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_answer_attempt
        FOREIGN KEY (exam_attempt_id) REFERENCES exam_attempt (id),
    CONSTRAINT fk_exam_answer_question
        FOREIGN KEY (exam_question_id) REFERENCES exam_question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamAnswer: latest answer per attempt slot, graded against the frozen paper snapshot by QuestionAnswerEvaluator; grading_status GRADED|UNGRADED; correctness never returned pre-submit.';

CREATE TABLE exam_result (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    exam_attempt_id   BIGINT        NOT NULL,
    user_subject      VARCHAR(128)  NOT NULL,
    space_id          BIGINT        NOT NULL,
    score             INT           NOT NULL,
    max_score         INT           NOT NULL,
    correct_count     INT           NOT NULL,
    wrong_count       INT           NOT NULL,
    unanswered_count  INT           NOT NULL,
    duration_ms       INT           NOT NULL,
    created_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_exam_result_attempt (exam_attempt_id),
    KEY idx_exam_result_user_space (user_subject, space_id),

    CONSTRAINT fk_exam_result_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_result_attempt
        FOREIGN KEY (exam_attempt_id) REFERENCES exam_attempt (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamResult: immutable per-attempt result computed at submit (score/maxScore/correct/wrong/unanswered/durationMs).';
