-- V055__exam_attempt_grading_status.sql
--
-- ExamAttempt grading lifecycle for subjective answers:
--   GRADED           all slots graded
--   PARTIALLY_GRADED some subjective slots still UNGRADED/NEEDS_REVIEW

ALTER TABLE exam_attempt
    ADD COLUMN grading_status VARCHAR(32) NULL AFTER status;
