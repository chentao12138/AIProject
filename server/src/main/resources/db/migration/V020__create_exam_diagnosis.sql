-- V020__create_exam_diagnosis.sql
--
-- BUSINESS-015 — structured ExamDiagnosis (docs/data-model.md §14.8).
--
-- exam_diagnosis       one structured diagnosis per SUBMITTED exam
--                      attempt (unique per attempt)
-- exam_diagnosis_item  per-dimension aggregated rows derived from
--                      the attempt's graded answers at submit time
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub (data-model userId); all
--     reads scoped user_subject + space_id + owner (JOIN).
--   * dimension_type V1: KNOWLEDGE_POINT | QUESTION_TYPE (CATEGORY
--     deferred — data-model lists it, BUSINESS-015 does not invent
--     threshold semantics for severity/recommendation, so those stay
--     NULL in V1).
--   * dimension_id: knowledge point id for KNOWLEDGE_POINT rows,
--     NULL for QUESTION_TYPE rows (the type name is the label).
--   * score/maxScore/accuracy/evidence_count are DERIVED at submit
--     from the frozen paper snapshot + graded answers (no AI, no
--     client input). SHORT_ANSWER (UNGRADED) items are excluded,
--     consistent with exam scoring.
--   * Multi-KP V1 rule: an item's score/maxScore contributes to EVERY
--     linked knowledge point (full attribution), documented as a V1
--     limitation — diagnosis rows persist the computed historical
--     snapshot; later question→KP edits never rewrite them.
--   * FKs explicit, no CASCADE; utf8mb4; practical indexes.
--
-- LATER NOTE (BUSINESS-016): diagnosis is consumed read-only as
-- explanatory reason text for generated StudyPlan tasks; no rewrite
-- API exists.

CREATE TABLE exam_diagnosis (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    exam_attempt_id BIGINT        NOT NULL,
    user_subject    VARCHAR(128)  NOT NULL,
    space_id        BIGINT        NOT NULL,
    summary         VARCHAR(1000) NULL,
    created_at      DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_exam_diagnosis_attempt (exam_attempt_id),
    KEY idx_exam_diagnosis_user_space (user_subject, space_id),

    CONSTRAINT fk_exam_diagnosis_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_exam_diagnosis_attempt
        FOREIGN KEY (exam_attempt_id) REFERENCES exam_attempt (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamDiagnosis: one structured diagnosis per submitted exam attempt (unique per attempt); generated deterministically at submit from graded paper items, never client-submitted.';

CREATE TABLE exam_diagnosis_item (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    exam_diagnosis_id BIGINT        NOT NULL,
    dimension_type    VARCHAR(32)   NOT NULL,
    dimension_id      BIGINT        NULL,
    label             VARCHAR(255)  NOT NULL,
    score             INT           NOT NULL,
    max_score         INT           NOT NULL,
    accuracy          DOUBLE        NOT NULL,
    evidence_count    INT           NOT NULL,
    severity          VARCHAR(32)   NULL,
    recommendation    VARCHAR(500)  NULL,
    created_at        DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_diagnosis_item_diagnosis (exam_diagnosis_id),
    KEY idx_diagnosis_item_dimension (dimension_type, dimension_id),

    CONSTRAINT fk_diagnosis_item_diagnosis
        FOREIGN KEY (exam_diagnosis_id) REFERENCES exam_diagnosis (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamDiagnosisItem: one dimension aggregate (KNOWLEDGE_POINT|QUESTION_TYPE) of a diagnosis; severity/recommendation NULL in V1 (no invented threshold taxonomy).';
