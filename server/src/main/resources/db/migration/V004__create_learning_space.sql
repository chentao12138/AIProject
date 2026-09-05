-- V004__create_learning_space.sql
--
-- BUSINESS-001 — first production table: LearningSpace.
--
-- LearningSpace is the first-class isolation boundary of the product
-- (docs/data-model.md §4.1, docs/business-baseline.md §2.1). Every
-- user's learning data lives inside one or more LearningSpaces, and
-- every query in the system is scoped by it.
--
-- Design notes:
--   * Production schema — this is NOT a SPIKE table. It replaces nothing;
--     the SPIKE-only spike_space_membership table remains untouched and
--     will be dropped in a later migration once its role is obsolete.
--   * Owner model: a LearningSpace has exactly one owner in v1,
--     identified by the JWT `sub` claim (owner_subject). There is no
--     formal User table yet and no FK to one — the subject string is
--     the identity key until a real User entity exists (deferred).
--     No membership / collaboration table is created in this migration;
--     multi-owner spaces are a future ADR, not this vertical slice.
--   * owner_subject is indexed because the two owner-scoped query
--     patterns (list-by-owner, get-by-id-and-owner) both lead with it.
--   * name is NOT NULL (create API requires it). description is NULLable.
--   * status: VARCHAR(32) NOT NULL with DEFAULT 'ACTIVE'. Values are
--     ACTIVE / ARCHIVED per data-model.md §4.1; the create API only
--     ever writes ACTIVE in this vertical slice. ARCHIVED is reserved
--     for the future archive/restore feature.
--   * created_at / updated_at: DATETIME(6) NOT NULL, matching the
--     timestamp style already used by the SPIKE tables (microsecond
--     precision, no timezone). The application layer sets both on
--     create; only updated_at changes on future updates.
--   * No seed data: the integration test that consumes this table
--     inserts and cleans up its own rows.
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching the existing
--     migrations so Chinese names round-trip cleanly on MySQL 8.

CREATE TABLE learning_space (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    name           VARCHAR(128) NOT NULL,
    description    VARCHAR(512) NULL,
    owner_subject  VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    KEY idx_learning_space_owner_subject (owner_subject)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LearningSpace: first-class isolation boundary, owned by a single subject (JWT sub) in v1.';
