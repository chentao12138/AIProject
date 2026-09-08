-- V014__create_practice_session.sql
--
-- BUSINESS-009 — Practice session (docs/data-model.md §11.1, §11.2).
--
-- practice_session            one practice run of the user in a space
-- practice_session_question   fixed question composition + snapshot
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub — the userId of
--     data-model.md §11 (no User table exists yet; same identity
--     source as learning_space.owner_subject). All reads scope on
--     BOTH user_subject and space_id.
--   * status V1: CREATED -> IN_PROGRESS -> SUBMITTED (start/finish
--     transitions; invalid transitions rejected with 409 by the
--     service). finished_at = submit time.
--   * scope_json: TEXT, server-written record of the selection
--     ({selectionMode, knowledgePointId, count, questionIds} as
--     appropriate). Informational only; not a query contract.
--   * question_snapshot_json: TEXT, server-written immutable copy of
--     the question at session creation ({questionType, stem,
--     options:[{optionKey,content,sortOrder}], answerData:{...}}).
--     Fixes the question order AND the grading truth: later edits to
--     the live question cannot change what this session asked or how
--     it is graded. answerData inside the snapshot is server-internal
--     and NEVER serialized into practice question views.
--   * FK to question is explicit (no CASCADE); a session keeps its
--     snapshot even if the live question is later soft-deleted
--     (answerData lives in the snapshot).
--   * redundant space_id on both tables for scoped reads/cleanup.
--   * utf8mb4, explicit FKs, practical indexes.

CREATE TABLE practice_session (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    status          VARCHAR(32)   NOT NULL,
    scope_json      TEXT          NULL,
    started_at      DATETIME(6)   NULL,
    finished_at     DATETIME(6)   NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_practice_session_user_space_status (user_subject, space_id, status),
    KEY idx_practice_session_space_created (space_id, created_at, id),

    CONSTRAINT fk_practice_session_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'PracticeSession: one practice run (user_subject, space_id); CREATED->IN_PROGRESS->SUBMITTED; scope_json = server-written selection record.';

CREATE TABLE practice_session_question (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    space_id               BIGINT        NOT NULL,
    practice_session_id    BIGINT        NOT NULL,
    question_id            BIGINT        NOT NULL,
    sort_order             INT           NOT NULL,
    question_snapshot_json TEXT          NOT NULL,
    created_at             DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_practice_session_question_pair (practice_session_id, question_id),
    KEY idx_psq_space_session (space_id, practice_session_id, sort_order),

    CONSTRAINT fk_psq_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_psq_practice_session
        FOREIGN KEY (practice_session_id) REFERENCES practice_session (id),
    CONSTRAINT fk_psq_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'PracticeSessionQuestion: fixed question composition of one session; question_snapshot_json freezes stem/options/answerData at creation (grading truth).';
