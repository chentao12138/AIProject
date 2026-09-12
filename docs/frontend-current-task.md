# Frontend Current Task

## Objective

LONG-RUN-FE-002A — SOURCE ASSET UPLOAD + INGESTION WORKBENCH + TXT/MARKDOWN CONTENT

## Current Phase

Core implementation DONE (contract audit → multipart gate → workbench → upload →
ingestion polling → TXT/MD content → provenance). Validation suite green.
Awaiting final docs closeout + user review/commit.

## Baseline (post-merge)

- Branch: feat/fe-001 @ d93b393 "Merge branch 'main' into feat/fe-001"
- Pre-merge FE-001.5 checkpoint: e099ba1
- main brought BUSINESS-004~007: SourceAsset, IngestionJob, TXT/MD, provenance,
  stable packages/api-client (MultipartUploadBody FormData subclass, no global
  Content-Type)
- Windows Node v24.11.1 / npm 11.6.2 / win32

## Stable Contract Matrix (PHASE 2 audit)

### SourceAsset
| method | HTTP | notes |
|---|---|---|
| uploadSourceAsset(spaceId, sourceId, file) | POST .../assets multipart part `file` | FormData subclass; browser boundary |
| listSourceAssets(spaceId, sourceId) | GET .../assets | newest first |
| getSourceAsset(spaceId, sourceId, assetId) | GET .../assets/{assetId} | |

SourceAssetResponse: id, spaceId, sourceId, assetRole, originalName, mimeType,
sizeBytes, sha256, createdAt. storageKey NOT exposed.

### IngestionJob
| method | HTTP | notes |
|---|---|---|
| createIngestionJob(spaceId, sourceId, assetId) | POST .../ingestion-jobs body {assetId} | 201 / 409 duplicate / 422 not ready |
| listIngestionJobs(spaceId, sourceId) | GET .../ingestion-jobs | newest first |
| getIngestionJob(spaceId, jobId) | GET .../ingestion-jobs/{jobId} | space-scoped |
| retryIngestionJob(spaceId, jobId) | POST .../retry | FAILED→PENDING only; else 409 |

Status (exact): PENDING \| RUNNING \| SUCCEEDED \| FAILED
Stage: QUEUED \| IMPORTING \| EXTRACTING \| STRUCTURING \| AI_PROCESSING \| NEEDS_REVIEW \| PUBLISHED
Non-terminal = PENDING/RUNNING. Terminal success = SUCCEEDED. Terminal failure = FAILED.
No substring matching. Unknown → neutral, not polled.

### Content
| method | HTTP |
|---|---|
| listSourcePages(spaceId, sourceId) | GET .../pages (pageOrder ASC) |
| listContentBlocks(spaceId, sourceId, pageId?) | GET .../content-blocks |
| addKnowledgePointSources(spaceId, kpId, contentBlockIds) | POST .../sources |
| listKnowledgePointSources(spaceId, kpId) | GET .../sources |

SourcePageResponse: id, spaceId, sourceId, sourceAssetId, sourcePageNumber,
pageOrder, printedPageNumber, pageType, extractedText, …
ContentBlockResponse: id, spaceId, sourceId, sourcePageId, blockType, sortOrder,
normalizedText, structuredDataJson, locatorJson, …
KnowledgePointSourceResponse: id, spaceId, knowledgePointId, contentBlockId,
relationType, relevanceScore, createdAt

## Architecture Decisions (FE-002A)

1. **Multipart gate PASS**: stable client uses `MultipartUploadBody extends FormData`,
   no global Content-Type. Browser generates boundary. No axios/direct fetch.
2. **File selection**: standard `<input type="file" accept=".txt,.md,…">`. No IPC,
   no fs, no raw path. sandbox/preload unchanged.
3. **Upload progress**: stage-state only (Ready / Uploading… / Uploaded /
   Waiting for ingestion… / Ingesting… / Succeeded / Failed). Never fake %.
   Backend progressPercent shown only when present and non-terminal.
4. **Polling**: TanStack refetchInterval 2s, only while isIngestionPollable.
   Stops on terminal / 401 / 403 / 404 / unmount / invalid id.
5. **Retry**: contract-backed retryIngestionJob only for FAILED.
6. **Content viewer**: plain `<pre>` readable text. NO dangerouslySetInnerHTML.
   No Markdown renderer dependency. XSS test proves `<script>` stays text.
7. **File-type policy**: TXT/Markdown only. PDF/Image deferred to Batch C.
8. **Provenance**: BUSINESS-007 links listed on KnowledgePoint detail; no
   invented routes for content blocks.

## Route

`/spaces/:spaceId/sources/:sourceId` → SourceWorkbenchPage under SpaceScopeGuard.

## Final Status (FE-002A-CLOSEOUT-01)

FE-002A IMPLEMENTED
WINDOWS LINT/TYPECHECK/TEST/COVERAGE/BUILD VERIFIED
PRODUCTION app://aistudy SMOKE VERIFIED
REAL TXT/MARKDOWN INGESTION — BLOCKED: local MySQL :3306 not running
(E2eBackendHarness requires FLYWAY_DB_URL + live MySQL; environment
blocker, not a frontend code defect). Failure-path behavior covered by
302 unit/integration tests including 401/403/404/5xx/network on upload,
source get, pages, blocks, and polling-stop semantics.
AWAITING USER REVIEW AND COMMIT
（不写 COMPLETE；不开始 FE-002B / PDF / Image / Auth）

- 变更范围：desktop/** + docs/frontend-*；server/** 与 packages/api-client/** 零触碰
- 测试：25 files / **302 tests**（baseline 230 → 302，≥300 gate MET）
- Coverage：Statements **87.36%** (2191/2508) / Branches **85.02%** (596/701) /
  Functions **84.86%** (157/185) / Lines **87.36%**
- Build：renderer index-Bi4920Gu.js 553.56 kB + 14.48 kB css
- Production smoke：Electron app://aistudy 存活；out/main 含 APP_ORIGIN +
  DEFAULT_API_BASE_URL + resolveApiBaseUrl + contextIsolation=true
- Electron 四件套不变；preload zero surface；无新 IPC
- BACKEND CONTRACT REQUEST：无
- docs/FE-002A-AUTONOMOUS-5H.md 已删除

## Allowed Write Scope

- desktop/**
- docs/frontend-current-task.md / docs/frontend-development-log.md
- 禁止：server/**、packages/api-client/**、git add/commit/push/merge

## Deferred

- FE-002B PDF/Image（等 Backend Batch C）
- 正式 Login / refresh / safeStorage
- electron-builder / Playwright / packaging
