-- V019__create_mastery.sql
--
-- BUSINESS-014 — KnowledgePoint mastery (docs/data-model.md §15.1).
--
-- mastery: current capability state of (user, space, knowledge point).
-- History lives in practice_answer / exam_answer / review_record;
-- this table only ever holds the CURRENT state (recomputed from
-- evidence, never client-submitted).
--
-- Design notes:
--   * user_subject VARCHAR(128) = JWT sub (data-model userId).
--     Unique (user_subject, space_id, knowledge_point_id).
--   * mastery_score: DOUBLE 0..1 = correct / graded evidence
--     (practice + exam objective answers of questions linked to the
--     point, using CURRENT question→point links — documented V1
--     limitation). confidence: DOUBLE 0..1 = min(1, sampleCount/5).
--   * evidence counts are EXPLAINABLE (data-model §15: "avoid one
--     unexplained number"): practice_evidence_count /
--     exam_evidence_count / review_evidence_count (review records of
--     KNOWLEDGE_POINT-targeted review tasks).
--   * last_evidence_at = most recent evidence timestamp used.
--   * No CASCADE; utf8mb4; practical indexes.

CREATE TABLE mastery (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    user_subject           VARCHAR(128)  NOT NULL,
    space_id               BIGINT        NOT NULL,
    knowledge_point_id     BIGINT        NOT NULL,
    mastery_score          DOUBLE        NOT NULL,
    confidence             DOUBLE        NOT NULL,
    practice_evidence_count INT          NOT NULL DEFAULT 0,
    exam_evidence_count    INT           NOT NULL DEFAULT 0,
    review_evidence_count  INT           NOT NULL DEFAULT 0,
    last_evidence_at       DATETIME(6)   NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_mastery_user_space_kp (user_subject, space_id, knowledge_point_id),
    KEY idx_mastery_space_user (space_id, user_subject),

    CONSTRAINT fk_mastery_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_mastery_kp
        FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Mastery: current capability state per (user, space, knowledge point); recomputed from practice/exam/review evidence by MasteryScoringPolicy; never client-submitted.';
