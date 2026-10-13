-- V061__ai_generation_job_claim_token.sql
--
-- F-29 — AI generation job lease identity + space-scoped read indexes.
--
-- ai_generation_job already had claimed_by / claimed_at (V056), but a claim
-- was never renewed and a terminal write was only guarded by status. Two
-- consequences that this migration closes:
--
--   1. recoverStaleJobs() requeues any PROCESSING row whose claimed_at is
--      older than the lease, so a long provider call that outlives the lease
--      gets handed to a second worker while the first is still running -
--      duplicate draft output. Renewing the lease needs a per-claim identity
--      to renew *for*, otherwise a restarted/re-used worker id silently
--      re-owns a lease it never won.
--   2. status alone cannot tell the current holder from an abandoned one:
--      both see 'PROCESSING'. claim_token is minted on every successful claim
--      and is the value the heartbeat and the terminal writes must match, so
--      an abandoned run can neither renew nor finalize a job it no longer owns.
--
-- Backfill safety: the column is nullable and NULL simply means "no lease".
-- Rows that were mid-flight when this runs already carry claimed_by, so the
-- token is seeded from it rather than left NULL: that keeps those rows' lease
-- ageable by the existing claimed_at value (recovery logic is unchanged) and
-- gives them a comparable identity, instead of making every in-flight job
-- instantly look like an unclaimed one.
--
-- Existing rows are never invalidated: no column is dropped, renamed, made
-- NOT NULL, or re-typed, and published migrations V001-V057 are untouched.

ALTER TABLE ai_generation_job
    ADD COLUMN claim_token VARCHAR(64) NULL AFTER claimed_at;

-- Seed a lease identity for rows that are mid-flight at deploy time.
-- Deterministic (derived from claimed_by) and idempotent: a second run of this
-- statement matches nothing, because claim_token is then already NOT NULL.
UPDATE ai_generation_job
SET claim_token = claimed_by
WHERE status = 'PROCESSING'
  AND claimed_by IS NOT NULL
  AND claim_token IS NULL;

-- Recovery sweep predicate: WHERE status = 'PROCESSING' AND claimed_at < ?.
-- The table previously had no index starting from status, so the periodic
-- lease sweep scanned every job row on every tick.
ALTER TABLE ai_generation_job
    ADD INDEX idx_ai_job_lease (status, claimed_at);

-- Space-scoped owner list/count (F-21):
-- WHERE space_id = ? AND requester = ? ORDER BY created_at DESC, id DESC.
-- idx_ai_job_space_status leads on space_id but not on requester, and
-- idx_ai_job_requester leads on requester but not on space_id, so neither can
-- serve the new statement without a sort.
ALTER TABLE ai_generation_job
    ADD INDEX idx_ai_job_space_requester (space_id, requester, created_at, id);
