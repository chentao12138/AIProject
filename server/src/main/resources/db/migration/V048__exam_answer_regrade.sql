-- V048__exam_answer_regrade.sql
--
-- BUSINESS-012 supplement — subjective grading audit trail.
--
-- Adds manual grading + regrade audit fields to exam_answer.
-- grading_status values: GRADED | UNGRADED | NEEDS_REVIEW
-- graded_by: ADMIN userId who set/confirmed the grade
-- graded_at: when the grade was last confirmed
-- previous_score / previous_feedback: pre-regrade snapshot for audit
--
ALTER TABLE exam_answer
    ADD COLUMN graded_by VARCHAR(128) NULL AFTER feedback,
    ADD COLUMN graded_at DATETIME(6) NULL AFTER graded_by,
    ADD COLUMN previous_score INT NULL AFTER graded_at,
    ADD COLUMN previous_feedback TEXT NULL AFTER previous_score;
