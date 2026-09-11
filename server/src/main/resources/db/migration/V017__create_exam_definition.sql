-- V017__create_exam_definition.sql
--
-- BUSINESS-012 — Exam definition + fixed paper
-- (docs/data-model.md §14.2, §14.3, §14.4).
--
-- exam           exam definition (DRAFT -> PUBLISHED, immutable after)
-- exam_paper     fixed paper VERSION of a published exam
-- exam_question  one scored question slot of a paper (snapshot)
--
-- Design notes:
--   * Publishing an exam creates paper_version 1 with its frozen
--     question composition; exam_question.question_snapshot_json
--     freezes stem/options/answerData/explanation exactly like the
--     practice snapshot (V014) — grading truth for ExamAttempt (013).
--     The live question is only referenced for identity.
--   * exam.status: DRAFT | PUBLISHED (ARCHIVED reserved, no API).
--     PUBLISHED exams are immutable in V1: no composition edit
--     endpoints exist.
--   * total_score = SUM(exam_question.score) enforced by the service
--     at create; time_limit_minutes nullable (no timer when null);
--     exam_type V1: 'STANDARD' (only value produced this slice;
--     data-model documents examType without enumerating V1 values).
--   * exam_paper.status V1: 'PUBLISHED' only (papers are created by
--     publishing; the column exists per data-model §14.3).
--   * FKs explicit, no CASCADE; utf8mb4; practical indexes.

CREATE TABLE exam (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    space_id             BIGINT        NOT NULL,
    title                VARCHAR(255)  NOT NULL,
    description          VARCHAR(1000) NULL,
    exam_type            VARCHAR(32)   NOT NULL,
    time_limit_minutes   INT           NULL,
    total_score          INT           NOT NULL,
    status               VARCHAR(32)   NOT NULL,
    created_by_user_id   VARCHAR(128)  NULL,
    created_at           DATETIME(6)   NOT NULL,
    updated_at           DATETIME(6)   NOT NULL,
    published_at         DATETIME(6)   NULL,

    PRIMARY KEY (id),

    KEY idx_exam_space_status (space_id, status),
    KEY idx_exam_space_created (space_id, created_at, id),

    CONSTRAINT fk_exam_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Exam: exam definition inside one LearningSpace; DRAFT->PUBLISHED (immutable composition after publish); total_score = sum of paper item scores.';

CREATE TABLE exam_paper (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    space_id       BIGINT       NOT NULL,
    exam_id        BIGINT       NOT NULL,
    paper_version  INT          NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_exam_paper_version (exam_id, paper_version),
    KEY idx_exam_paper_space (space_id),

    CONSTRAINT fk_exam_paper_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_paper_exam
        FOREIGN KEY (exam_id) REFERENCES exam (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamPaper: fixed paper version of an exam, created at publish time (V1: version 1 only, status PUBLISHED).';

CREATE TABLE exam_question (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    exam_paper_id          BIGINT        NOT NULL,
    space_id               BIGINT        NOT NULL,
    question_id            BIGINT        NOT NULL,
    sort_order             INT           NOT NULL,
    score                  INT           NOT NULL,
    question_snapshot_json TEXT          NOT NULL,
    created_at             DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_exam_question_pair (exam_paper_id, question_id),
    KEY idx_exam_question_space_paper (space_id, exam_paper_id, sort_order),

    CONSTRAINT fk_exam_question_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_question_paper
        FOREIGN KEY (exam_paper_id) REFERENCES exam_paper (id),
    CONSTRAINT fk_exam_question_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamQuestion: one scored question slot of a paper; question_snapshot_json freezes content + answer data at publish (history restore + grading truth).';
