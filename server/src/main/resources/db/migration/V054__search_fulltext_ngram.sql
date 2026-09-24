-- V054__search_fulltext_ngram.sql
--
-- Chinese-friendly FULLTEXT indexes for unified search.
-- MySQL 8.x ngram parser; LIKE remains as deterministic fallback when
-- FULLTEXT cannot match a token.

ALTER TABLE source
    ADD FULLTEXT INDEX ft_source_title (title) WITH PARSER ngram;

ALTER TABLE knowledge_point
    ADD FULLTEXT INDEX ft_kp_text (title, summary, content) WITH PARSER ngram;

ALTER TABLE question
    ADD FULLTEXT INDEX ft_question_stem (stem) WITH PARSER ngram;

ALTER TABLE note
    ADD FULLTEXT INDEX ft_note_text (title, content) WITH PARSER ngram;

ALTER TABLE content_block
    ADD FULLTEXT INDEX ft_content_block_text (normalized_text) WITH PARSER ngram;
