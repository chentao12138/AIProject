-- V039__content_block_structured.sql
-- Final Backend Feature Freeze — ContentBlock richer structured data (§3.13).
-- ContentBlock already carries structuredDataJson/locatorJson/sourceOutlineNodeId from V011.
-- This migration adds the outline_node_id index to support SourceOutlineNode linkage.

CREATE INDEX idx_content_block_outline ON content_block(space_id, source_outline_node_id);
