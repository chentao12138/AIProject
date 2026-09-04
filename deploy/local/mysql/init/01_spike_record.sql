-- FOR SPIKE-002 ONLY.
-- This schema will be dropped when SPIKE-002 is retired.
-- Do NOT treat this as formal V001 schema. Formal schema versioning belongs to SPIKE-003.

CREATE TABLE IF NOT EXISTS `spike_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Auto-generated id',
    `name` VARCHAR(100) NOT NULL COMMENT 'Chinese-text test field',
    `description` VARCHAR(255) NOT NULL COMMENT 'Chinese-text test field',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'Insert timestamp, microsecond precision',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='SPIKE-002 verification table. Not a business entity.';
