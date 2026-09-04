-- ============================================================
-- V002__add_note_to_flyway_spike_record.sql
-- SPIKE-003 ONLY. NOT A BUSINESS MIGRATION.
--
-- V002 is a minimal schema upgrade used to prove that Flyway can:
--   (1) Detect an existing schema already at version 001;
--   (2) Apply only V002 without re-running V001;
--   (3) Preserve pre-existing rows untouched during the upgrade;
--   (4) Record V002 in flyway_schema_history with success = true.
--
-- The 'note' column is a nullable VARCHAR(255) added to the SPIKE-only
-- 'flyway_spike_record' table. This column has NO business meaning; it
-- exists purely to demonstrate that a subsequent migration can ALTER
-- an existing SPIKE table.
--
-- When SPIKE-003 is retired, both V001 and V002 are removed together
-- with the SPIKE-only table. Real business migrations belong to the
-- Platform Skeleton phase.
-- ============================================================

ALTER TABLE `flyway_spike_record`
    ADD COLUMN `note` VARCHAR(255) NULL
    COMMENT 'SPIKE-003 V002 upgrade field';
