-- V046__create_knowledge_point_relation.sql
-- Final Backend Feature Freeze — KnowledgePointRelation.

CREATE TABLE knowledge_point_relation (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    space_id             BIGINT      NOT NULL,
    source_knowledge_point_id BIGINT NOT NULL,
    target_knowledge_point_id BIGINT NOT NULL,
    relation_type        VARCHAR(32) NOT NULL,
    weight               INT         NULL,
    notes                TEXT        NULL,
    created_at           DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_kp_relation_pair (source_knowledge_point_id, target_knowledge_point_id, relation_type),
    KEY idx_kp_relation_space (space_id),
    KEY idx_kp_relation_source (source_knowledge_point_id),
    KEY idx_kp_relation_target (target_knowledge_point_id),

    CONSTRAINT fk_kp_relation_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_kp_relation_source FOREIGN KEY (source_knowledge_point_id) REFERENCES knowledge_point (id),
    CONSTRAINT fk_kp_relation_target FOREIGN KEY (target_knowledge_point_id) REFERENCES knowledge_point (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'KnowledgePointRelation: structured KP relationships (PREREQUISITE/RELATED/PART_OF/CONTRAST).';
