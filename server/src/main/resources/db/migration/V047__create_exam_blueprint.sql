-- V047__create_exam_blueprint.sql
--
-- BUSINESS-012 supplement — ExamBlueprint rule engine.
--
-- exam_blueprint stores a JSON rule set that deterministically selects
-- questions and produces an immutable ExamPaper + ExamQuestion snapshot.
--
-- status: DRAFT | USED (used after paper generation locks the rules)
-- rules_json: server-validated JSON document (see ExamBlueprintService)
--
CREATE TABLE exam_blueprint (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    space_id      BIGINT        NOT NULL,
    title         VARCHAR(255)  NOT NULL,
    rules_json    TEXT          NOT NULL,
    status        VARCHAR(32)   NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_exam_blueprint_space (space_id),

    CONSTRAINT fk_exam_blueprint_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExamBlueprint: deterministic rule set for paper generation; rules_json server-validated; status DRAFT|USED.';
