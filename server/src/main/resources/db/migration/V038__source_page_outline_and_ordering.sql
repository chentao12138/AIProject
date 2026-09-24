-- V038__source_page_outline_and_ordering.sql
-- Final Backend Feature Freeze — SourcePage outline + page ordering support (§3.10, §3.12).

ALTER TABLE source_page ADD COLUMN outline_node_id BIGINT NULL AFTER source_asset_id;

CREATE INDEX idx_source_page_outline ON source_page(space_id, outline_node_id);
