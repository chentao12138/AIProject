# Current Task

## Objective
Final Backend Feature Freeze — remaining backend gaps then freeze.

## Current Phase
**IN PROGRESS — Feature code (§5 Ingestion/OCR/Versioning now partially landed)**

## Latest round (§5)

### Schema V053
- `source.current_extraction_revision_id`
- `source_page/content_block/source_outline_node.extraction_revision_id`
- `ingestion_job.claimed_by/claimed_at/last_stage_status`
- `ingestion_issue.source_id/extraction_revision_id/safe_message`

### Ingestion job
- Atomic DB claim `QUEUED → IMPORTING` (single worker)
- Startup recovery runner requeues stale PROCESSING + drains QUEUED
- Stage-resume retry via `requeueForStageRetry` + `last_stage_status`
- No Thread.sleep progress faking
- Revision created BEFORE extract; pages/blocks stamped; source current revision updated
- Safe error message bounded

### OCR
- Image pipeline wired to `OcrEngine` (Paddle process adapter)
- PNG/JPEG/WebP magic; WebP without ImageIO reader still accepted via RIFF/WEBP magic
- OCR text → SourcePage + ContentBlock; low confidence flag
- PDF text-first + OCR fallback for sparse pages (PDFRenderer 150dpi)
- IngestionIssue producers: OCR_LOW_CONFIDENCE, EXTRACTION_PARTIAL_FAILURE

### Contract fixes
- RAW download: `GET .../assets/{assetId}/content` streams StorageService (no storageKey)
- Version compare rewritten to `selectByRevision` (not assetId-as-revisionId)
- SourceAsset allowlist webp/docx (prior round)

### Compile gate
`.\mvnw.cmd clean test-compile` — **BUILD SUCCESS** after this round.

## Latest round (§5–§12)

### Admin / SystemConfig
- `AdminSourceIngestionController`: list/detail sources, revisions, jobs, issues, retry, page reorder, block edit, RAW (ROLE_ADMIN)
- SystemConfig typed registry: server-owned type/range/restart; PUT value-only; no secret editor
- `GET /api/v1/admin/system-config/registry`

### api-client wrappers
- listConversationMessages / explainPractice / explainExam / studyCoach
- AI generation-jobs create/list/get/retry
- downloadSourceAssetRaw
- deleteFolderSyncSnapshot
- admin system-config registry/upsert
- admin sources list + job retry

### Compile gates
```
server mvn clean test-compile  → BUILD SUCCESS
api-client npm run typecheck    → PASS
```

## ARCH-HARDENING round (2026-09-20/21)

架构评审后落地的收口（决策见 ADR-047 ~ ADR-050）：

- **越权修复**：`/api/v1/spaces/{spaceId}/statistics/question-type-performance`
  与 `mastery-movement` 之前不带 principal / 忽略路径 spaceId，SQL 只按 space_id
  过滤；现按 `user_subject + space_id` 过滤并走 `getMine` gate。
- **隔离地板**：新增 `space/scope/SpaceScopeInterceptor`（+ `SpaceScopeWebConfig`），
  `/api/v1/spaces/**` 的 `{spaceId}` 在进入 handler 前统一校验，失败 404
  `SPACE_NOT_FOUND`。6 个单测覆盖 owner/越权/匿名/非空间路径/query 伪造。
- **错误契约**：新增 `common/problem`（`ApiException` / `ApiErrorCodes` /
  `ApiExceptionHandler`），§12 的 ProblemDetail + 稳定 code 首次真实存在；
  `IllegalStateException` 不再回显内部消息。4 个 MockMvc 单测。
- **边界棘轮**：新增 `architecture/ModuleBoundaryTest`（零新依赖，扫源码 import）：
  Controller→Mapper 名单（9，只准减少）、Controller 用 `JdbcTemplate`（0，硬性）、
  跨模块 Mapper import ≤85、模块环基线 = 1 个 12 模块大团。
- **摄取长任务**：专用有界 `ingestionWorkerExecutor` + `aistudy.ingestion.worker.*`
  配置；`afterCommit` 才入队；`IngestionRevisionPublisher` 把"revision 可见"四步
  收进一个事务；终态与 error 诊断同条 UPDATE（`markTerminalFailure`）；租约心跳
  `refreshClaim`；回收从"仅启动"改为周期 tick（test/flyway-it 关闭）；DOCX/TXT
  重抽先删未定稿页/块；删除 `resetForRetry` / `countActiveOrSucceeded` 死词表 SQL；
  修正 `IngestionJob` 实体里过期的 PENDING/RUNNING/SUCCEEDED 生命周期注释。
- **admin 裸 SQL 下沉**：`AdminSourceIngestionController` 的 12 处 `JdbcTemplate`
  （含 `UPDATE ingestion_job` / `UPDATE content_block`）移入
  `admin/source/service/AdminSourceIngestionService` + 各属主模块新增 mapper 方法；
  admin retry 现在真正重新派发（原来只把状态改回 QUEUED，要等重启才跑）。
- **测试基础设施**：`test` profile 之前**整套全上下文测试都起不来**（`provenance`
  的 `SourceReferenceService` 构造注入 `JdbcTemplate`，而该 profile 排除了
  DataSource；`AiStudyApplicationTests` 的手写 mock 清单也漏了 17 个新 mapper）。
  改为 `testsupport/MapperMockRegistrar` + `TestProfileMapperMockAutoConfiguration`
  （仅 `test` profile）自动按 `@Mapper` 注解供给 mock。DB-free 子集：
  121 error → 131 pass / 4 fail。
- **文档**：architecture.md §2/§4/§5.2.1/§6.2/§10/§16.1、api-guidelines.md §12.1、
  operations.md（stale job 一节改写）、runtime-configuration.md（worker 旋钮）、
  decisions.md（ADR-047~050）。

### 本轮遗留（需后续处理）

1. 4 个 OpenAPI 契约测试失败，属**未提交批次自带的漂移**：
   `SourcePageController` 直接返回实体 `SourcePage`（违反 api-guidelines §14
   "Entity 不直接作为 API DTO"，测试期望 `SourcePageResponse`）；
   `CreateExamRequest.durationMinutes`、`MasteryResponse.practiceEvidenceCount`
   字段从 schema 上消失。
2. `V031`~`V056` 共 25 个 migration 与 `admin/`、AI job 等 101 个源文件**仍未纳入
   git**（另 110 个已修改未提交）：ADR-025/046 的"版本化 SQL 为唯一事实"当前不成立，
   干净克隆建不出可用库，Flyway checksum 因机器而异。
3. 33 个 `flyway-it` 集成测试需要真实 MySQL，本机 Docker 未运行 → **未验证**。
   拦截器与 IAE→400 的状态码变化只在该子集里才可能被观察到。
4. ZIP 重抽会重复插入 `source_asset` 行（缺唯一键 + upsert），未修。
5. `spike/` 仍在生产上下文并持有 `/health`、`/v3/api-docs` 的 `@Order(1)` 安全链，
   空间鉴权组件 `SpikeSpaceAccess` 依赖 spike 表；收紧 `/v3/api-docs` 会连带
   `api:generate`（需本地起服务匿名取 spec），属于需要产品/工作流决策的改动，未动。
6. Mastery 事务内聚合 + SELECT-then-INSERT 无 upsert/乐观锁；DERIVED 生产侧不写
   provenance 关联；生命周期状态无统一策略；Search 12 处重复谓词与 ngram 索引未使用。

## Round 2026-10-13/14 — gap audit + frontend contract work (backend)

Input: `docs/AIStudy_2026-10-13_Final_Backend_Gap_Audit.md` + `desktop/BACKEND-CONTRACT-REQUESTS.md` (C01–C12).
Work was built in parallel lanes (search / source lifecycle / asset-folder-RAW / OCR / AI jobs) and integrated into `main`.

### Done AND executed against real MySQL (`aistudy_flyway_test`)
- **F-01/F-02/F-03 + C-Search**: valid UNION assembly (every branch suffixes the separator), publish visibility gate
  (`source.status='PUBLISHED'` + current revision, NULL pointer = legacy escape; KP/Question `status='PUBLISHED'`),
  ngram FULLTEXT wired in. `SearchResult.type` now includes `NOTE` and carries `noteId`.
  Root cause of the Chinese-search bug: InnoDB natural-language relevance measured **1.9e-8** on a small corpus, so the
  `ROUND(relevance * 5)` boost was always 0 and `score > 0` discarded the only kind of hit Chinese search can produce.
  Fixed by giving a FULLTEXT-only match its own lowest tier per branch and using relevance only as `ft_relevance`
  ordering key (scale-free). 34/34 search ITs + 20/20 SQL guards.
- **Ingestion async contract**: `create()` had no duplicate guard after the dead SUCCEEDED vocabulary was deleted.
  A job for an asset that is still in flight now returns 409 `INGESTION_ALREADY_RUNNING`; re-ingesting a terminal
  asset stays legal and produces a NEW revision (old revision rows remain addressable). The two tests named in audit §C
  now await the worker instead of expecting a terminal status on the create response. 38/38 ingestion ITs.
- **C06**: `GET .../practice-sessions/{sessionId}/result` backed by a stored summary (V066),
  `PRACTICE_SESSION_NOT_SUBMITTED` instead of a fabricated zero. 26/26 practice ITs.
- **Flyway**: V001→V066 applies on a clean schema; the C02/C03/C04 branch's migration collided on V058 and was
  renumbered V065. 5/5 migration ITs.
- Merged and compiling/verified: F-04/F-05/F-25/F-26 (source review state machine, admin manual body, block write
  guards), F-12/F-13/F-14/F-15 (ZIP idempotency, duplicate policy, folder sync chain, RAW Range/206/416),
  F-08/F-09/F-28/F-31/F-10 (WebP sizing, OCR process fences, OCR sidecar + Docker, page-order invariant),
  F-21/F-22/F-29 (AI job space scope, honest counts/type allow-list, claim-token lease),
  F-16/F-17 (typed 201 for saved variant + inherited-answer gate), C01/C02/C03/C04/C05.
- **Contract surface regenerated from the live service**: `packages/api-client` 67 → **195 paths**, `npm run typecheck`
  passes. Handed to the frontend session with the answers it asked for (answers-list `explanation` is hardcoded null —
  a defect, tracked; message paging is `created_at ASC` so page 0 is the OLDEST message).

### Known-red, NOT caused by this round
- `ModuleBoundaryTest.crossModuleMapperImportsMustNotGrow`: measured **91** vs cap **85**. At `f685ef8` (before any
  merge in this round) it already measured **90**, so the ratchet was broken by earlier commits, not here. The guard now
  prints its offender list. Attribution of the 6 over cap:
  - **5** from the Notes feature (`note.service.NoteService` → `knowledge.point`, `question`, `source`, `source.content`,
    `source.page` mappers) — commit `e829e76`.
  - **1** from this round's merge (`admin.source.service.SourceExtractionGatewayService` / ingestion gateway seam).
  - This round **removed** the ones its own new code would have added: `ai.knowledge` → `knowledge.source.mapper`,
    `ai.question` → `knowledge.point`/`question` mappers (now via `KnowledgePointSourceService.linkDerivedSource` and
    `QuestionService.createDerived`), and `ingestion.job` → `source.asset.mapper` (now via `SourceAssetService`).
  Fixing the remaining 6 means routing those reads/writes through the owning services. Do NOT raise the cap to 91.

### Verified state of this round's second half (real MySQL, `aistudy_flyway_test`)
- Full `mvn -o test`: **1000 tests, 0 errors, 1 failure** (only the boundary item above), 2 skipped.
- Frontend contracts now live in the regenerated `packages/api-client` (**197 paths**, `npm run typecheck` green):
  **C-Paging** (`page`/`size` + `QuestionPageResponse`, SQL-side paging), **C06**
  (`GET .../practice-sessions/{id}/result`, stored summary in V066, `PRACTICE_SESSION_NOT_SUBMITTED`),
  **C10** (`ExamAnswerView.answer`/`submitted`, `AttemptQuestionView.answer`/`answered`),
  **C11** (`GET /spaces/{spaceId}/exam-attempts` paged, `score` null until a result exists),
  **C12** (14 exam conflict sites now throw `ApiException` with stable codes instead of writing the code inside English).
- **F-19/F-20/F-23**: the batch generators validate the whole provider payload before any write, inside one
  transaction, write provenance through the owning module, and book every provider call (success and failure) in
  `ai_usage_record`; `AiGenerationProvenanceTest` (7 tests) pins it.
- Also fixed on the way: `AiGenerationJobWorker.java` was on disk as `AiGenerationJobWorker` with class
  `AIGenerationJobWorker`, which broke incremental compilation.

### Environment recipe that cost two false reds this round
- `DB_URL` and `FLYWAY_DB_URL` point at **different schemas for different purposes**: the `it`-profile spike tests need
  `aistudy_spike` (its `spike_record` table is created by `deploy/local/mysql/init`, not by Flyway), while every
  `flyway-it` test and the locally running app need `aistudy_flyway_test`. Pointing `DB_URL` at the Flyway schema makes
  `SpikeRecordMapperIntegrationTest` fail with `Table ... spike_record does not exist`; pointing it at the spike schema
  for a *run* makes Flyway refuse to start (`no schema history table`).
- Never source `AISTUDY_BOOTSTRAP_ADMIN_ENABLED` into a test run: `BootstrapAdminRunner` is a `@Component` gated on it,
  and with the `test` profile's mocked mappers it fails the context ("Bootstrap admin account was created but could not
  be loaded"), which shows up as ~129 "ApplicationContext failure threshold exceeded" errors, not as one real bug.

### Not done yet
- F-18 (AI content structuring still overwrites live published ContentBlocks; it must land in a draft revision),
  F-24 (blueprint distribution is only a candidate filter then `subList`), F-06/F-07 (ORDER/OUTLINE low-confidence
  issue producers, automatic outline suggestion), F-11 (real revision-to-revision diff), F-30 (error-code sweep
  outside exam: practice/asset/folder conflicts still surface generic `CONFLICT`).
- Frontend: C09 (typed ORDERING/MATCHING pairs, `questionType`/`status` as real enums — `orderingAnswer: number[]`
  and `matchingAnswer: string(JSON)` already ship and grade by array order), the `explanation` hardcoded null on
  `GET .../practice-sessions/{id}/answers`, and practice-module conflict codes.
- The variant fixes F-16/F-17 have no dedicated test yet; C06's open-session 409 path is untested.
- Gates not executed: T-16 real textbook E2E, T-17 OCR on Windows+Docker, T-18 real StepFun/BYOK, T-21 container E2E,
  T-22 (tracked worktree is clean, nothing is pushed).
- `login.json` / `spec-live.json` sit untracked in the repo root from someone's probe — not created by this round,
  left in place; check them before any commit that includes the repo root.





## Git
User owns all git writes.
