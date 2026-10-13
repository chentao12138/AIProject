-- V064__source_page_backfill_contiguous_order.sql
--
-- F-31 — make the existing data match the page-order invariant that the
-- services now enforce: inside one Source (and one extraction revision),
-- page_order is a contiguous 1..N numbering with no duplicates.
--
-- Why a backfill at all: until this change every image asset wrote
-- page_order = 1 and every PDF numbered from 1 per asset, so any Source with
-- more than one asset already holds colliding orders, and partial reorder
-- requests left unlisted pages sitting on orders somebody else had just taken.
-- Renumbering here is what lets a later release add the unique index without a
-- separate cleanup window: the duplicates cannot be repaired by the services
-- alone, because they only ever look at the pages they are writing now.
--
-- Rule applied (identical to SourcePageService.batchUpdateOrder):
--   * the ordering scope is (space_id, source_id, extraction_revision_id);
--     NULL revision means "the draft currently being built" and is treated as
--     one scope through IFNULL(..., 0);
--   * within a scope pages keep their previous relative position
--     (ORDER BY page_order, id — `id` breaks ties deterministically, which is
--     the same tie-breaker every page listing uses);
--   * ROW_NUMBER() then rewrites that sequence as 1..N.
-- Pages whose page_order was NULL, 0 or negative sort to the front of their
-- scope and are given a legal positive value; that is the point of the
-- backfill, not an accident.
--
-- Why NO unique index yet (deliberate, not an oversight):
--   A UNIQUE (space_id, source_id, IFNULL(extraction_revision_id, 0), page_order)
--   would be the strongest statement of this invariant, but it cannot ship in
--   the same release as the services that satisfy it. The DOCX and TXT/Markdown
--   extraction paths (owned by other lanes) still hard-code page_order = 1, and
--   a ZIP asset is expanded into several entries that are all extracted by the
--   same job — so a "notes.zip" containing three .txt files writes three pages
--   at order 1 today. With a unique index that insert would fail inside the
--   job, and the stamp at publish time (stampRevisionOnSourcePages) would fail
--   the whole publication, turning a working feature into a red job. The index
--   belongs to the release where every extractor allocates through
--   SourcePageService.allocateOrderSlots; the matching SQL is documented in
--   docs/operations-ocr-docker.md so it is one migration away.
--
-- Safety:
--   * UPDATE over a derived ROW_NUMBER, no DELETE, no DDL on the columns, no
--     FOREIGN_KEY_CHECKS changes; content_block references source_page by id,
--     which is untouched.
--   * Idempotent in effect: re-running only re-derives the same 1..N sequence.
--   * MySQL 8.4 supports WITH ... UPDATE; the subquery is materialised, so the
--     table is not read and written by the same statement expression.

WITH ordered AS (
    SELECT
        sp.id,
        ROW_NUMBER() OVER (
            PARTITION BY sp.space_id, sp.source_id, IFNULL(sp.extraction_revision_id, 0)
            ORDER BY sp.page_order, sp.id
        ) AS rn
    FROM source_page sp
)
UPDATE source_page sp
JOIN ordered o ON o.id = sp.id
SET sp.page_order = o.rn
WHERE sp.page_order IS NULL OR sp.page_order <> o.rn;
