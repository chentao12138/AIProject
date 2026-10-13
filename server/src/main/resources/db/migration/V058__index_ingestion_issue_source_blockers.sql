-- V058__index_ingestion_issue_source_blockers.sql
--
-- Publish-gate support for the Source review state machine (§3.14:
-- "blocker issue 禁止 publish", F-05 / I-11).
--
-- Every publish now asks one question of ingestion_issue: does this source
-- still have a row with severity='HIGH' and status='OPEN'? V053 added the
-- source_id column without an index, and the only indexes V042 created start
-- with (space_id, ingestion_job_id) / (space_id, source_page_id), so that
-- predicate had no usable index at all — a review action was scanning the
-- whole issue table, and it scans it on the request thread.
--
-- Design notes:
--   * Column order is equality-first and matches the single query shape
--     (space_id, source_id, severity, status), so the same index also serves
--     the ADMIN governance issue list keyed by (space_id, source_id).
--   * severity/status are included rather than filtered after the lookup:
--     the vast majority of rows on an active source are resolved or MEDIUM,
--     and covering them keeps the COUNT(*) an index-only scan.
--   * No CHECK constraint on the vocabulary: V042 left severity/status plain
--     VARCHAR(32), the pipeline writes 'HIGH'/'MEDIUM' + 'OPEN'/'RESOLVED'/
--     'IGNORED', and tightening the column would strand rows written before
--     this change. The gate is enforced in SQL predicates instead.
--   * Additive only; nothing published in V001-V057 is modified.

ALTER TABLE ingestion_issue
    ADD INDEX idx_issue_space_source_blockers (space_id, source_id, severity, status);
