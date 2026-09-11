-- V015__create_practice_answer.sql
--
-- BUSINESS-010 — Practice answers (docs/data-model.md §11.3).
--
-- practice_answer records the user's answer to ONE slot
-- (practice_session_question) of a practice session, plus the
-- server-side grading result for objective types.
--
-- Design notes:
--   * Keyed on practice_session_question_id (the fixed slot), with
--     denormalized question_id for reporting/mastery (014). Unique
--     (practice_session_question_id) → one latest answer per slot;
--     re-answering while IN_PROGRESS upserts the SAME row.
--   * answer_data_json: TEXT, server-written typed payload
--     ({"selectedOptionKeys":[...]} / {"booleanAnswer":true} /
--     {"textAnswer":"..."}).
--   * is_correct / score: NULL while a SHORT_ANSWER stays ungraded
--     (no AI/human grading in V1 — data-model.md §11.3 allows NULL).
--     Objective types are graded by the shared
--     QuestionAnswerEvaluator at answer time.
--   * feedback_json: reserved (post-submit explanation summary);
--     written by the service, never free-form client JSON.
--   * user_subject + space_id scope every read (user state entity,
--     ADR-038). duration_ms optional client-provided; server
--     tolerates null.
--   * utf8mb4, explicit FKs (no CASCADE), practical indexes.

CREATE TABLE practice_answer (
    id                           BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject                 VARCHAR(128)  NOT NULL,
    space_id                     BIGINT        NOT NULL,
    practice_session_question_id BIGINT        NOT NULL,
    question_id                  BIGINT        NOT NULL,
    answer_data_json             TEXT          NOT NULL,
    is_correct                   TINYINT(1)    NULL,
    score                        INT           NULL,
    submitted_at                 DATETIME(6)   NOT NULL,
    duration_ms                  INT           NULL,
    feedback_json                TEXT          NULL,
    created_at                   DATETIME(6)   NOT NULL,
    updated_at                   DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_practice_answer_slot (practice_session_question_id),
    KEY idx_practice_answer_user_space (user_subject, space_id, question_id),
    KEY idx_practice_answer_space_session (space_id, practice_session_question_id),

    CONSTRAINT fk_practice_answer_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_practice_answer_psq
        FOREIGN KEY (practice_session_question_id) REFERENCES practice_session_question (id),
    CONSTRAINT fk_practice_answer_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'PracticeAnswer: latest answer per practice slot; objective types graded server-side (QuestionAnswerEvaluator); SHORT_ANSWER stays ungraded (is_correct/score NULL) in V1.';
