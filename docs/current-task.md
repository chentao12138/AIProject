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

## Remaining for FEATURE FREEZE
- §13 coverage audit table (no PARTIAL/TODO in frozen scope)
- §14 full Maven test batches
- §15 runtime E2E / OCR / BYOK / Docker / OpenAPI generate
- §16 docs consistency
- Optional: Search FULLTEXT MATCH ranking; AI usage on all generation purposes

Git: user-owned commit.




## Git
User owns all git writes.
