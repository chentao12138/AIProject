# Current Task

## Objective
Backend Batch C final frontend handoff for FE-002B.

## Current Phase
**BATCH C FRONTEND HANDOFF READY**

## Status Board

- Batch C functional verification: PASS
- Full Maven clean test: PASS (603 run, 0 failures, 0 errors, 2 skipped)
- Real backend boot (flyway-it): PASS
- Live `/v3/api-docs`: PASS (OpenAPI 3.1.0, 61 paths)
- Regenerated `packages/api-client` from live contract: PASS
- `packages/api-client` typecheck + typecheck:generated: PASS
- Stable backend + generated-client commit: done on `batch-c-frontend-handoff`

## Verified scope (already stable for FE)

- Storage / SourceAsset upload
- IngestionJob lifecycle + retry
- TXT / Markdown regression
- PDF ingestion (SourcePage + ContentBlock)
- PNG / JPEG ingestion (SourcePage only, no OCR)
- SourcePage / ContentBlock read APIs
- Ops / runtime / deployment foundation
- AI-001 ~ AI-004 implementation (runtime-verified in this gate via full suite)

## Frontend handoff contract (summary)

See `BACKEND BATCH C FINAL FRONTEND HANDOFF REPORT` in the release notes /
handoff conversation. Key FE pointers:

- Upload allowlist extensions: zip, pdf, jpg, jpeg, png, md, markdown, txt
- PDF MIME: `application/pdf` (fallback `application/octet-stream`)
- Image MIME: `image/png`, `image/jpeg`
- IngestionJob status: PENDING | RUNNING | SUCCEEDED | FAILED
- IngestionJob stage (V1): QUEUED | IMPORTING | PUBLISHED
- Retry only on FAILED (409 otherwise)
- Image pages: `pageType=IMAGE`, `extractedText=null`, no ContentBlocks
- OCR fields exposed but always null in V1

## Next Actions
1. FE-002B may start from this stable commit.
2. Do not regenerate the client from a dirty worktree.
3. Remaining limitations listed in the handoff report.
