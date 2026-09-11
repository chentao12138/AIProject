-- V013__create_question_domain.sql
--
-- BUSINESS-008 — Question domain (docs/data-model.md §10, §20).
--
-- Question is the formal question-bank entity that Practice (009/010)
-- and Exam (012/013) consume. This migration creates the four tables:
--
--   question                  core question row
--   question_option           objective-choice options (NO is_correct
--                             column: correct answers live in
--                             question.answer_data_json, server-side)
--   question_knowledge_point  M:N question <-> knowledge_point
--   question_source           M:N question <-> content_block (provenance;
--                             table reserved — no API this slice)
--
-- Design notes:
--   * Ownership derives via space_id -> learning_space.owner_subject.
--     No owner column on content tables (same as knowledge_point).
--   * answer_data_json: TEXT, server-written JSON carrying the
--     question-type-specific correct answer (e.g.
--     {"correctOptionKey":"A"} / {"correctOptionKeys":["A","C"]} /
--     {"correctBoolean":true} / {"referenceAnswer":"..."}).
--     NEVER returned to practice/exam clients before submit — the DB
--     snapshot is server-internal (api-guidelines.md §9).
--   * Relation tables carry redundant space_id (same as V012
--     knowledge_point_source) so every read and every test cleanup is
--     space-scoped without a JOIN.
--   * question_type V1: SINGLE_CHOICE | MULTIPLE_CHOICE | TRUE_FALSE |
--     SHORT_ANSWER. status V1: DRAFT -> PUBLISHED (idempotent publish,
--     same semantic as V007 knowledge_point). origin_type V1:
--     USER_CURATED only (ADMIN_CURATED/AI_DERIVED need Admin/AI APIs).
--   * deleted_at soft-delete; all business reads filter
--     `deleted_at IS NULL`. No delete API this slice.
--   * FKs explicit, no ON DELETE CASCADE. All tables utf8mb4.
--   * Indexes per data-model.md §19: Question(space_id, status,
--     question_type) for filtered listing; relation tables lead with
--     space_id and pair-key UKs.

CREATE TABLE question (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    space_id            BIGINT        NOT NULL,
    question_type       VARCHAR(32)   NOT NULL,
    stem                TEXT          NOT NULL,
    answer_data_json    TEXT          NOT NULL,
    explanation         TEXT          NULL,
    difficulty          VARCHAR(32)   NULL,
    origin_type         VARCHAR(32)   NOT NULL,
    status              VARCHAR(32)   NOT NULL,
    created_by_user_id  VARCHAR(128)  NULL,
    created_at          DATETIME(6)   NOT NULL,
    updated_at          DATETIME(6)   NOT NULL,
    published_at        DATETIME(6)   NULL,
    deleted_at          DATETIME(6)   NULL,

    PRIMARY KEY (id),

    KEY idx_question_space_status_type (space_id, status, question_type),
    KEY idx_question_space_created (space_id, created_at, id),

    CONSTRAINT fk_question_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Question: formal question-bank row inside one LearningSpace; USER_CURATED DRAFT->PUBLISHED in BUSINESS-008; correct answer only in answer_data_json (server-internal).';

CREATE TABLE question_option (
    id          BIGINT        NOT NULL AUTO_INCREMENT,
    space_id    BIGINT        NOT NULL,
    question_id BIGINT        NOT NULL,
    option_key  VARCHAR(16)   NOT NULL,
    content     VARCHAR(500)  NOT NULL,
    sort_order  INT           NOT NULL,
    created_at  DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_question_option_question_key (question_id, option_key),
    KEY idx_question_option_space_question (space_id, question_id),

    CONSTRAINT fk_question_option_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_question_option_question
        FOREIGN KEY (question_id) REFERENCES question (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'QuestionOption: objective-choice options; correctness lives in question.answer_data_json, never here.';

CREATE TABLE question_knowledge_point (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    space_id          BIGINT      NOT NULL,
    question_id       BIGINT      NOT NULL,
    knowledge_point_id BIGINT     NOT NULL,
    weight            INT         NULL,
    created_at        DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_question_kp_pair (question_id, knowledge_point_id),
    KEY idx_question_kp_space (space_id),
    KEY idx_question_kp_point (knowledge_point_id),

    CONSTRAINT fk_question_kp_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_question_kp_question
        FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_question_kp_point
        FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'QuestionKnowledgePoint: M:N question<->knowledge_point (data-model 10.3); same-space invariant enforced by service; weight NULL in V1.';

CREATE TABLE question_source (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    space_id          BIGINT      NOT NULL,
    question_id       BIGINT      NOT NULL,
    content_block_id  BIGINT      NOT NULL,
    relation_type     VARCHAR(32) NULL,
    created_at        DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_question_source_pair (question_id, content_block_id),
    KEY idx_question_source_space (space_id),
    KEY idx_question_source_block (content_block_id),

    CONSTRAINT fk_question_source_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_question_source_question
        FOREIGN KEY (question_id) REFERENCES question (id),
    CONSTRAINT fk_question_source_block
        FOREIGN KEY (content_block_id) REFERENCES content_block (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'QuestionSource: M:N question<->content_block provenance (data-model 10.4); reserved in BUSINESS-008, no API yet; relation_type NULL in V1.';
