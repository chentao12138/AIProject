-- V003__create_spike_space_membership.sql
--
-- SPIKE-004 MICRO-07A — SPIKE-only membership table used to back the
-- Space Authorization SPIKE with a database-driven lookup.
--
-- This table is NOT the production LearningSpace / User / Membership
-- model. It exists solely so that the future MICRO which replaces the
-- hard-coded allow/deny rule in SpikeSpaceAccess with a real
-- persistence-backed lookup has a minimum-viable table to query
-- against. Once the production User <-> LearningSpace membership
-- design lands (see ADR-046's migration story), this table is
-- dropped and replaced by real schema.
--
-- Design notes:
--   * SPIKE ONLY — do not reuse this schema for production.
--   * Only one table. No user table, no space table, no LearningSpace
--     table. user_subject and space_id are opaque string identifiers
--     supplied by the SPIKE's request/response and by the JWT's `sub`
--     claim; they are intentionally not foreign-keyed to any other
--     table.
--   * Only one unique index on (user_subject, space_id) — a single
--     membership per (user, space) pair. No extra indexes: the SPIKE
--     query pattern is `WHERE user_subject = ? AND space_id = ? AND
--     status = 'ACTIVE'`, and the (user_subject, space_id) unique
--     index already gives the optimizer a covering lookup for the
--     first two equality predicates; adding a composite index that
--     includes `status` would be redundant at SPIKE scale.
--   * No INSERT of seed data. The SPIKE integration test that consumes
--     this table is responsible for inserting and cleaning up its own
--     rows, so that the migration remains idempotent across repeated
--     Flyway runs and does not leak test fixtures into the schema
--     history.
--   * Charset is utf8mb4 with utf8mb4_unicode_ci, matching the other
--     SPIKE tables (flyway_spike_record, spike_record) so that
--     UTF-8 subject strings round-trip cleanly on MySQL 8.

CREATE TABLE spike_space_membership (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    user_subject   VARCHAR(128) NOT NULL,
    space_id       VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    created_at     DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uk_spike_space_membership_user_space
        UNIQUE (user_subject, space_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SPIKE ONLY. Temporary membership table backing SpikeSpaceAccess; replace with production LearningSpace membership schema before release.';
