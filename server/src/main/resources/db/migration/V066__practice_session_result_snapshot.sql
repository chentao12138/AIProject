-- V066__practice_session_result_snapshot.sql
--
-- Final Backend, frontend contract C06 / A5.3.
--
-- `POST .../practice-sessions/{id}/finish` computed score/maxScore/
-- answeredCount/correctCount and returned them once; `GET` on the same
-- session carried none, so a finished session could not show its result
-- after a reload and the client was forbidden from recomputing scores
-- locally (ADR-031). The summary is a historical fact of that submission,
-- so it is stored with the session instead of being re-derived on read.
--
-- Nullable with no default: sessions finished before this migration keep
-- NULL, which the result endpoint reports as "no stored result" rather than
-- inventing a score of 0.

ALTER TABLE practice_session
    ADD COLUMN score          INT NULL AFTER finished_at,
    ADD COLUMN max_score      INT NULL AFTER score,
    ADD COLUMN answered_count INT NULL AFTER max_score,
    ADD COLUMN correct_count  INT NULL AFTER answered_count;
