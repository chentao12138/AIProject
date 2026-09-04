-- ============================================================
-- V001__create_flyway_spike_record.sql
-- SPIKE-003 ONLY. NOT A BUSINESS TABLE.
--
-- This migration is a throwaway spike artifact used to prove that:
--   (1) Spring Boot + Flyway can run against a FRESH empty MySQL
--       database and apply a V001 migration end-to-end;
--   (2) flyway_schema_history is created and correctly records
--       the V001 install row with success = true;
--   (3) the resulting SPIKE-only table is utf8mb4 end-to-end.
--
-- When SPIKE-003 is retired, both this file and the SPIKE-only
-- 'flyway_spike_record' table it creates should be removed.
-- DO NOT evolve this migration into a business schema; the real
-- V001 belongs to the Platform Skeleton phase.
-- ============================================================

CREATE TABLE IF NOT EXISTS `flyway_spike_record` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'Auto-generated id',
    `name`       VARCHAR(100) NOT NULL COMMENT 'Chinese-text spike field (SPIKE-003)',
    `created_at` DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Insert timestamp, microsecond precision',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='SPIKE-003 Flyway migration verification table. Not a business entity.';
