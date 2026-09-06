# Current Task

## Objective

AIProject 后端业务闭环：BUSINESS-004（SourceAsset Upload）→ BUSINESS-005（IngestionJob + ZIP Safety）→ BUSINESS-006（Content Ingestion TXT/MD）→ BUSINESS-007（KnowledgePoint Provenance）全部 COMPLETE（用户 runtime verified）。下一业务块：Question / Practice 基础（等用户 Git closeout 后按 development-plan 开始）。

## Current Phase

**BUSINESS-004 ~ BUSINESS-007 COMPLETE — AWAITING GIT CLOSEOUT**（2026-09-06，用户全部 runtime evidence 齐备）

## Completed

- **BUSINESS-004 RAW SourceAsset Upload（COMPLETE，user verified）**
  - V008 source_asset（FK×2 无 CASCADE / uk_storage_key / idx×2 / utf8mb4）
  - StorageService 抽象 + LocalStorageService（stream + SHA-256 + ATOMIC_MOVE + 双层 path traversal 防御：lexical + normalized containment）
  - authenticated multipart upload（RAW 字节保留；assetRole 服务端派生：zip→ORIGINAL_PACKAGE，allowlist→ORIGINAL_FILE）
  - sha256 / size / MIME 元数据；404 anti-probing；DB+FS 补偿删除（TransactionSynchronization）
  - shared TS client：uploadSourceAsset / listSourceAssets / getSourceAsset（MultipartUploadBody 零断言桥接；无全局 Content-Type）
- **BUSINESS-005 IngestionJob + ZIP Safety（COMPLETE，user verified）**
  - V009 ingestion_job（FK×3 无 CASCADE / idx×3 / 15 列含 asset_id / utf8mb4）
  - job 生命周期 PENDING→RUNNING→SUCCEEDED/FAILED + stage 全表 + retry（FAILED→PENDING retryCount++，非 FAILED→409）
  - ZipArchiveInspector 纯 Java 中央目录安全检查（zip-slip/rooted/drive/blank 名称、entry 数、单 entry 与总解压大小、压缩比炸弹、加密/不支持 method 拒绝；零抽取；V1 encrypted ZIP 永远拒绝——JDK 无 isEncrypted()，用 entry 数据流 open+close 探针）
  - create 同步 ZIP safety gate → FAILED(ZIP_SAFETY_VIOLATION, safe message 无 stack trace)
- **BUSINESS-006 Content Ingestion（COMPLETE，user verified）**
  - V010 source_page（extracted_text LONGTEXT，匹配 64MB 摄取上限）+ V011 content_block（normalized_text TEXT，60KB UTF-8 byte 有界块）
  - TXT/Markdown 确定性解析器（严格 UTF-8+BOM+CRLF 归一；ATX 标题/围栏含 ```java/列表/pipe 表格；setext 不解释；1-based locator；单行超限按 code point 边界行内拆分，不切 surrogate pair）
  - ContentExtractionService（有界读取 → 先解析后单事务落库，FAILED 零残留）；create 派发：TXT/MD 同步执行、ZIP gate、PDF/image→422 INGESTION_NOT_READY、重复→409
  - 内容读 API：GET pages / GET content-blocks?pageId=（owner-scoped JOIN）
- **BUSINESS-007 KnowledgePoint Provenance（COMPLETE，user verified）**
  - V012 knowledge_point_source（显式 space_id / uk 成对唯一 / idx×2 / FK×3 / utf8mb4）
  - POST/GET /spaces/{spaceId}/knowledge-points/{kpId}/sources：批量幂等 add（任一无效 id→404 零插入；返回 point 全量当前链接）
  - 同 space invariant 双层强制（两端 owner-scoped 读先行 + 读 JOIN）；relation_type/relevance_score V1 NULL；无 AI 调用
  - shared TS client：addKnowledgePointSources / listKnowledgePointSources

## Runtime Evidence（全部用户真实运行）

- BUSINESS-004 focused：Tests run 41 / 0 / 0 / 0 BUILD SUCCESS
- BUSINESS-004 full clean：Tests run 138 / 0 / 0 / 0 BUILD SUCCESS
- BUSINESS-005~007 focused：Tests run 123 / 0 / 0 / 0 BUILD SUCCESS
- Full clean（全部）：Tests run 267 / 0 / 0 / 0 BUILD SUCCESS
- Live OpenAPI：npm run api:generate PASS（generated 含 BUSINESS-004~007 全部 paths/schemas）
- Shared client：BUSINESS-004 multipart wrapper + BUSINESS-005/006/007 共 11 个新 wrapper 已实现
- Final TypeScript：npm run typecheck PASS（无报错）

## Important Test Infrastructure Decision

- 共享 flyway-it schema 集成测试使用统一 ResourceLock（串行，防并发 schema 冲突）
- FK-complete 测试 cleanup（依赖序：knowledge_point_source → content_block → source_page → ingestion_job → source_asset → source → learning_space，Knowledge 侧 knowledge_point/category 前置）
- 无 FOREIGN_KEY_CHECKS=0 / TRUNCATE / CASCADE / DROP FK；schema guard 仅允许 aistudy_flyway_test

## Key Design Decisions（窗口内沉淀）

- 状态/生命周期最小化（无 workflow engine / MQ / Redis / Quartz）；异步执行推迟（快速确定性步骤同步跑）
- 所有新读 owner-scoped SQL JOIN；404 anti-probing 统一；error_message safe ≤1000 无 stack trace
- ZIP 中央目录校验（零抽取）；伪造 size 残余风险随 extraction 推迟并文档化
- 文本严格 UTF-8（GBK 推迟）；ContentBlock 60KB UTF-8 bytes 有界（TEXT 列）；page 全文 LONGTEXT
- provenance 显式 space_id + 双层同空间强制；uk 成对唯一；幂等 add

## Files（最终状态）

- Migrations：V008（004）、V009（005）、V010+V011（006）、V012（007）—— 当前最新 schema
- 新生产 Java：storage×4、source.asset×7、ingestion.zip×5、ingestion.job×7、ingestion.extract×3、source.page×5、source.content×5、knowledge.source×6
- Tests：9 个新类 120 @Test + Flyway V001-V012 断言 + 旧兼容（11 个 test-profile context 全 10 mock）
- Shared client：packages/api-client/src/client.ts（全部业务 wrappers）；generated 为用户 api:generate 产物
- docs：current-task / development-log / development-plan

## Deferred（下一业务块，均 NOT STARTED）

- Question / Practice / Wrong-Question / Review / Exam / Mastery / StudyPlan
- SourceOutlineNode 表与目录提取；ZIP extraction / manifest / IngestionIssue / PARTIAL_FAILED / 异步 worker
- GBK/PDF/OCR/image ingestion；MD setext/inline/嵌套/表格结构化；originType SOURCE_DERIVED/AI_DERIVED 创建流程
- 正式 User/Auth/Refresh/Admin、AI provider、Search、Admin APIs、部署加固

## Known Risks / Notes

- Flyway 11.7.2 vs MySQL 8.4 WARN（既有，生产部署前重新验证）
- 工作树含用户侧 ELECTRON-CORS-001-B/C 前端配套改动（SpikeSecurityConfig +8 行等，非后端业务块，由用户管理）
- 全部 runtime evidence 来自用户真实运行（AI 不运行 Maven/npm）

## Next Actions

1. git diff --check
2. git status --short
3. git diff --stat
4. **用户 git add/commit/push（仅用户可执行；Hermes 禁止 git 写）**
5. 确认 clean baseline
6. 开始下一后端业务块（Question / Practice foundation）

## Resume Instructions

Do NOT reimplement BUSINESS-004~007（全部 COMPLETE，用户 focused+full+api:generate+typecheck 实证）。下一后端工作从 Git closeout 后开始，预期领域 = Question / Practice foundation。设计前必须重读 development-plan / data-model / api-guidelines / requirements 与当前真实 schema（V001-V012），并确认 Question/Practice 的 docs 定义与现有 space 隔离模式。禁止 git add/commit/push；禁止伪造 runtime PASS；Question/Practice/Exam 未开始前不要提前实现。
