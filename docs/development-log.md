# Development Log

> 架构决策以 `decisions.md` 为准，业务总定义以 `business-baseline.md` 为准。本文件只记录阶段事实。

## 2026-09-24 — 联调修复之后的两件事：IT 清理基建与摄取错误码

- IT 清理基建：23 个集成测试各自维护一份 `DELETE FROM ...` 阶梯，V031-V056 新增的表和外键没有任何一份阶梯提到，于是 `DELETE FROM learning_space` 报 "Cannot delete or update a parent row"，约 700 个 `DataIntegrityViolationException` 落在与本无问题的测试上。新增 `testsupport/OwnedSpaceReset`：从 `information_schema` 的外键图（`KEY_COLUMN_USAGE` 的 `REFERENCED_*` 列，按约束名 join `REFERENTIAL_CONSTRAINTS` 会因为约束名只在表内唯一而炸出笛卡尔积）推导 `learning_space` 的全部子孙，叶子优先删除；`source <-> extraction_revision` 互指，故单轮清理内临时关 FK 检查（整棵子树用同一谓词清空，不留孤儿）。`FlywayMigrationIntegrationTest` 补 `@AfterEach` 把 schema 恢复到 latest —— 它是唯一会 clean 共享库的类，之前把降级后的 schema 直接交给后面的类（`Unknown column 'claimed_at'` ×66）。效果：**660 tests 里 errors 243 → 3**。
- 生产缺陷：`ExamDiagnosisService` 用 `Long.parseLong` 解析分类标签，而"知识点没有分类"时标签是字符串 `UNCATEGORIZED` → **考试提交直接失败**。改为维度行显式携带 id 或 null，`item(...)` 参数类型收窄成 `Long`。`ExamDiagnosisIntegrationTest` 11/11 绿；该类的诊断条目数量断言按四个维度（KNOWLEDGE_POINT / QUESTION_TYPE / CATEGORY / DIFFICULTY）重写，并把 `UNCATEGORIZED` 钉成显式断言。
- 生产缺陷：摄取的错误码被整体压平。`IngestionJobService` 的作业 catch 硬编码 `INGESTION_FAILED`，从不读取 `IngestionParseException` / `PdfExtractionException` / `ImageExtractionException` 已有的 `errorCode()` / `safeMessage()`（这三类的 javadoc 明确写着"作业层会取它们落库"），而 `ZIP_SAFETY_VIOLATION` 在 `src/main` 里只出现在枚举与 javadoc、从未被抛出 —— zip 安全检查是结果式的（`ZipInspectionResult.violations()`），解压处把它转成裸 `IllegalStateException` 并顺带把违规明细（含条目名）塞进消息。修法：新增 `IngestionFailure` 接口（三个既有异常实现之）+ `ZipSafetyException`，解压处不再重新包装类型化失败，作业 catch 沿 cause 链找类型化失败并沿用其 code/safeMessage。验证：`createForCorruptZipFailsWithSafetyViolation`、`createForTraversalZipFailsWithSafetyViolation`、`failedJobErrorMessageContainsNoStackTrace` 由失败转通过（该类 7 failures → 3）。
- 未修（下一步）：按资产的提取失败抛出**空消息**异常（`asset 239 extraction failed: ` 后面什么都没有），且单个资产的失败只把作业置 `PARTIAL_FAILED` 而不留任何 errorCode —— 于是纯 TXT/MD 作业也终态错误、错误不可解释。当前摄取 IT 剩余 33 个失败全部源于此，属 `ingestion/extract` 与作业计数逻辑，与上面的错误码映射是两件事。
- 后续同轮已继续修掉（各条独立，按测量推进）：
  - `content_block` 从来没有 `source_asset_id` 列（资产的页归属在 `source_page.source_asset_id`），重抽取的清理语句因此抛 `BadSqlGrammarException`，被按资产的 catch 吞掉 —— **任何**作业（哪怕一个 txt）都终态 `PARTIAL_FAILED` 且无 errorCode。删除改为经 `source_page` JOIN；同时那行日志原先只打 `getMessage()`（这次恰是空串），改为记录完整异常。
  - 作业不绑定资产：端点 javadoc 与请求体都写明 `assetId`，service 签名却不接收，于是 `ingestion_job.asset_id` 恒为 NULL（API 对每个作业回 `"assetId": null`），worker 还会去重抽该 source 下所有无关资产。现在创建时校验归属、写入 assetId，worker 从作业自己的资产出发。
  - 全部资产失败时按 `FAILED` + 第一个类型化失败的 code/safeMessage 收尾（原来只有无声的 `PARTIAL_FAILED`）；成功/部分失败收尾补写 `finished_at`（此前完成态的作业 `finishedAt` 为 null）。
  - `TextMarkdownContentParser` 先做宽松解码（非法字节被替换成 U+FFFD）再把"已干净"的字节交给内部的严格 UTF-8 解码器 —— 因此 `ENCODING_ERROR` 永不可能触发；`aistudy.ingestion.text.max-document-bytes` 在 `application.yml` 与 runtime-configuration.md 里都有，却**没有任何 Java 代码绑定**，`DOCUMENT_TOO_LARGE` 同样永不触发。现在原始字节直通解析器，上限按仓库既有 `@Value(... DataSize)` 方式注入并在解析前校验。
  - `source_page_number` 按 `SourcePageResponse` 的契约（"PDF-internal page number (null for text assets)"）对 txt/docx 置 null，此前固定写 1。
  - `retry` 的控制器 javadoc 写着"非 FAILED → 409"，实现却返回未改动的作业（→200 的假成功）；改为真正 409。
  - `KnowledgePointProvenanceIntegrationTest` 的 `ingestTextSource` 仍在 create 响应上断言 `SUCCEEDED`（同步契约残留），改用 `AsyncIngestionJobs.createAndAwait(..., "NEEDS_REVIEW")`。
  - 测量：摄取 IT 从 33 failures + 1 error 降到 2 failures；Provenance 11 failures 归零。
- 剩余 2 个摄取失败，都需要产品口径而不是改代码：`duplicateCreateOnSucceededAssetReturns409`（service 里根本没有去重规则，且作业生命周期里没有 `SUCCEEDED` 这个状态，测试前提要先定）；`imageRetryOnFailedThenSucceedDoesNotDuplicate`（断言 create 同步返回 `FAILED`，注释还引用了 AI 作业才有的 PENDING/RUNNING/SUCCEEDED —— 该文件正被另一位编辑者改写，不动）。
- 协作：本轮期间另一个编辑者在 12:50 独立改写了 4 个摄取 IT 与 `testsupport/AsyncIngestionJobs`（把同步断言改成轮询终态，并把终态从测试原先写的 `SUCCEEDED` 纠正为生命周期里的 `NEEDS_REVIEW`）。这些改动不属于本次提交。

## 2026-09-24 — 前端联调清单转来的后端缺陷修复

前端在 `local` / space 28 实测出 3 个 500 和 4 个契约/格式问题，逐个复现后修复。

- `GET /spaces/{id}/sources` 500：`SourceMapper.selectBySpaceId` 的 SQL 带 `ls.owner_subject = #{ownerSubject}`，方法却只绑了 `@Param("spaceId")` → MyBatis 运行期 `BindingException`。补上 `@Param("ownerSubject")` 并由 `SourceService.listMine` 传入；同文件里与之字节级重复、无调用方的 `selectByOwnerIncludingArchived` 删除（它就是这次误绑的来源）。
- `POST /spaces/{id}/exams` 500：真实字段是 `questions[].score` 与 `timeLimitMinutes`（前端传了 `points` / `durationMinutes`，Jackson 静默忽略未知字段），而 `score` 只有 `@Min(1)` 没有 `@NotNull` → `ExamService:83` 的 `totalScore += input.score()` 拆箱 NPE。补 `@NotNull`，现在是 400 VALIDATION_ERROR 且 `errors[].field = questions[0].score`。
- `PUT /settings/ai` 500：两层。表层是运行实例没配 `AISTUDY_AI_SECRET_KEY`；根层是 `parseMasterKey` 只校验 "≥32 字节"，48 字节的 key 被放行后才在 `Cipher.init` 里以 `Invalid AES key length` 炸掉。改为必须正好 32 字节（AES-256）并在启动期失败；缺 key 时改抛 503 + `AI_NOT_CONFIGURED` 的 ProblemDetail，而不是匿名 500。
- 错误响应统一：`ApiExceptionHandler` 原先只列举了部分异常类型，其余（`BindingException`、NPE、不可解析的请求体）落到 servlet 容器的默认错误页，前端拿不到 `code` / `requestId`。补齐 400（不可解析请求体、参数类型不符）、404（未知路由）、405，并加 `Exception` 兜底 → 500 ProblemDetail，堆栈只进日志。
- 修上述兜底时踩到二次回归并已修：advice 的解析顺序先于 Spring 的 `ResponseStatusExceptionResolver`，裸 `@ExceptionHandler(Exception.class)` 会把 `LastAdminProtectionException` 类上的 `@ResponseStatus(CONFLICT)` 压成 500（`AdminSecurityIntegrationTest` 抓到）。兜底现在读取并沿用该注解。
- 同一类缺陷静态扫出的另外两处已一并修：`KnowledgePointRelationMapper.selectByIdAndSpace` 同样漏绑 `ownerSubject`；`AIGenerationJobMapper.retryFailed` 的 SQL 用了未绑定的 `#{status}` / `#{progress}`，按服务层语义写回 `'PENDING'` / `0`。
- `ReviewStateMapper.upsert` 用 `<if test='_index != null and _index > 0'>` 区分 INSERT/UPDATE —— `_index` 只在 `<foreach>` 内有定义，直接调用时恒为 null，于是永远走 INSERT 分支，第二次复习同一目标会撞 `uq_review_state_target`，且 `WHERE id = #{id}` 是永远走不到的死分支。改为按显式绑定的 `stateId` 判定，三处调用方相应传入。
- 防回归：`PersistenceSqlGuardTest` 增加第三条规则 —— 注解 SQL 里的每个 `#{placeholder}` 必须被该方法自己的 `@Param`（或 `<foreach>` 别名）绑定。规则在全仓扫出了上述真实缺陷，去掉即报错。`ApiExceptionHandlerTest` 补 4 例（兜底 500 仍是 ProblemDetail、不可解析请求体 400、未知路由 404、`@ResponseStatus` 不被兜底压平）。
- 契约答疑（无代码改动）：`StudyCoachRequest` 的字段就是 `question`，`packages/api-client` 的 `studyCoach(spaceId, {question?})` 与之是一致的；练习会话 `/finish` 后 `status = SUBMITTED` 是设计终态（`CREATED → IN_PROGRESS → SUBMITTED`，没有 DONE），"已完成"视图请判 `status === 'SUBMITTED'`（`finishedAt` 同一次转移写入）。OpenAPI 未入库，由 springdoc 在 `/v3/api-docs` 运行期生成。
- 套件状态（确定性，两次同码复跑结果逐条一致）：660 tests / 17 failures / 243 errors。错误量里 1202 条 `foreign key constraint fails`、720 个 `DataIntegrityViolationException`，全部来自同一测试基建缺陷：约 20 个 IT 各自复制了一段 `DELETE FROM learning_space ...` 清理逻辑，未按 V031+ 新增外键的依赖顺序删除；另有 66 条 `Unknown column 'claimed_at'`，来自 `FlywayMigrationIntegrationTest` 在同一 schema 上中途 `clean()` / 降版本。本轮修复让若干测试走得更快、写入更多行，因而把该类清理失败的计数推高（216 → 243 errors，同时 30 → 17 failures）。生产代码路径不是原因。

## 2026-09-24 — 本地后端拉起时修掉的两个迁移 / SQL 缺陷

- 缺陷 1（迁移链）：`V050__create_question_source.sql` 用 plain `CREATE TABLE question_source`，而 `V013__create_question_domain.sql` 已经建过同结构同索引的同名表。任何从空库开始的 `migrate()` 都在 V050 中止（MySQL Error 1050），连带后果是 `aistudy_flyway_test` 长期停在半迁移状态、V051+ 的列和表从未在 IT 库里真正存在过。改为 `CREATE TABLE IF NOT EXISTS` 并保留 V050 版本号；V031–V056 目前仍是未提交的工作区文件，没有任何已应用库带着旧 checksum，所以不需要 `flyway repair`。
- 缺陷 2（MyBatis）：`ExamAttemptMapper.selectExpiredInProgress` 在 plain `@Select` 里写了 `&lt;`。MyBatis 只在 `<script>` 语句内解码 XML 实体，`&lt;` 会被原样发给 MySQL 并按 `& , l , t` 解析，于是 `ExamAutoSubmitScheduler` 每 30s 抛一次 `SQLSyntaxErrorException`。改为直接写 `<`。仓内其余 `&lt;`/`&gt;` 都在 `<script>` 语句里（`ExamStatisticsMapper` 全部、`PracticeSessionMapper`、`ReviewTaskMapper`），合法保留。
- 防回归：新增 `PersistenceSqlGuardTest`（`architecture` 包，纯源码/迁移脚本文本扫描，无 DB、无 Spring context），两条规则 —— 非 `<script>` 注解 SQL 不得含 XML 实体；迁移链中被更早版本建过的表必须写成 `IF NOT EXISTS`。两条规则都用"临时还原缺陷再跑"的方式验证过会 fail，且报错带文件名和语句原文。
- 验证：空库下 `local` profile 启动 → `Successfully applied 55 migrations ... now at version v056`；`/actuator/health` = 200；`dev` 账号 `POST /api/v1/auth/login` = 200；auto-submit 扫描 SQL 正常执行（`<== Total: 0`，无异常）。
- 未修（本次修复新暴露，不是本次改动引入）：`./mvnw test` = 654 tests / 30 failures / 216 errors。原因是 IT 共用同一个 `aistudy_flyway_test`：`FlywayMigrationIntegrationTest` 中途 `clean()` / 降到 V001 会让后续 IT 看到缺表缺列（`Unknown column 'claimed_at'` ×57）；测试 cleanup 的 `DELETE` 顺序没满足 V031+ 新增的 FK（`DataIntegrityViolationException` ×429，例如 `extraction_revision` → `source`）。V050 冲突此前使链路走不到这些迁移，所以这些问题一直不可见。

## 2026-09-12 — AI-001 ~ AI-004 Implementation Complete

AI backend first slice implemented. **VERIFICATION DEFERRED** (no Maven/tests this block).

- AI-001 provider foundation: `AiProvider` port, domain chat types, OpenAI-compatible HTTP adapter, typed `aistudy.ai.*` properties (disabled by default), stable `AiErrorCode` / `AiProviderException`.
- AI-002 persistence: Flyway `V026` `ai_conversation`, `V027` `ai_message`; space+user scoped; USER/ASSISTANT roles only.
- AI-003 learning context: `AiLearningContextService` via `SearchService` only; `AiTutorPromptBuilder` with untrusted context delimiting.
- AI-004 REST: conversation CRUD-ish (create/list/get/archive) + message list/send under `/api/v1/spaces/{spaceId}/ai/...`.
- Network calls never wrapped in DB transactions.
- Metrics: `aistudy.ai.requests` / `aistudy.ai.failures` with closed tag sets.
- Docs: `docs/ai-architecture.md`, runtime env vars, `current-task.md`.

## 2026-09-09 — BUSINESS-025 + BUSINESS-026 Implementation Complete

Observability, operations, and deployment hardening complete.

- Actuator dependency added via Spring Boot BOM; `management.endpoints.web.exposure.include=health,info,prometheus`.
- Health probes enabled: `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`.
- Actuator security chain `@Order(0)` added using `EndpointRequest.toAnyEndpoint()`; health anonymous, admin protected, authJwtDecoder.
- Storage health indicator added with bounded non-sensitive metadata (`backend=local`); no physical path exposed.
- Request correlation id filter implemented with strict `[A-Za-z0-9._-]{1,64}` validation, MDC lifecycle, and `X-Request-Id` response header.
- Logging pattern updated to include `requestId=%X{requestId:-}`.
- Ingestion metrics added with bounded tags (`type`, `outcome`, `error_code`).
- Storage reconciliation service added with conservative `max-scan-entries` and `min-age` bounds; default mode is dry-run.
- Admin reconciliation endpoint added under `/api/v1/admin/operations/storage/reconcile`.
- Operations runtime variables documented in `docs/runtime-configuration.md`.

Deployment hardening:

- Multi-stage `Dockerfile` added: builder uses `eclipse-temurin:21-jdk` with Maven Wrapper; runtime uses `eclipse-temurin:21-jre` and runs as non-root `aistudy:1001`.
- `.dockerignore` added: excludes secrets, IDE metadata, build outputs, local data, and frontend artifacts while keeping `.mvn/`, `mvnw`, and `mvnw.cmd`.
- `application-prod.yml` hardened:
  - `server.shutdown=graceful`
  - `spring.lifecycle.timeout-per-shutdown-phase=30s`
  - `server.forward-headers-strategy=framework`
- `application.yml` management health group extended with explicit liveness/readiness group blocks.
- First-admin bootstrap added:
  - `BootstrapAdminProperties` (`aistudy.bootstrap.admin.*`) registered in `ApplicationPropertiesConfig`.
  - `BootstrapAdminRunner` creates the first ADMIN only when explicitly enabled; handles concurrent bootstrap safely and never logs plaintext passwords.
- `ForwardedHeaderConfig` registers `ForwardedHeaderFilter` so the app honours `X-Forwarded-*` headers only when deployed behind a trusted proxy.
- `ActuatorSecurityConfig` now uses `EndpointRequest.toAnyEndpoint()` with an explicitly constructed `JwtAuthenticationConverter`.
- `AuthSecurityConfig` exposes `@Bean(name = "authJwtAuthenticationConverter")` so downstream security configs can inject a shared converter.
- `deploy/docker-compose.yml` added with backend + MySQL 8.4 topology, named persistent volumes, health-aware dependency, and no hardcoded secrets.
- `deploy/.env.example` added as the secrets template.
- `.gitignore` sanity check completed: local env, secrets, build outputs, local data, and temporary artifacts are ignored; Maven wrapper files remain tracked.
- Source hygiene scan completed: no actionable TODO/FIXME defects; `PdfExtractionException.safeMessage()` and `ImageExtractionException.safeMessage()` now delegate to `super.getMessage()` explicitly; ZIP probe stream path reviewed; no hardcoded JWT secret, hardcoded password literals, absolute Windows paths, or `System.out` usage found in production code.
- Security chain audit passed: Actuator `@Order(0)` -> SPIKE `@Order(1)` -> Production `@Order(2)` remains intact.
- Docs updated:
  - `docs/runtime-configuration.md` documents `AISTUDY_BOOTSTRAP_ADMIN_*` variables and storage persistence posture.
  - `docs/operations.md` adds reconciliation deployment policy, readiness/liveness contract, graceful shutdown behavior, and first-admin bootstrap section.
  - `docs/deployment.md` created with architecture, env vars, bootstrap, Flyway, storage, ports, reverse proxy/TLS, CORS, health, actuator exposure, shutdown, logging, reconciliation, backup, upgrade, and verification guidance.
- Plan/task docs updated to mark BUSINESS-025 + BUSINESS-026 implementation complete.
- No Maven/tests/docker builds run; verification deferred.

## 2026-09 — Technical Baseline

完成从早期 Electron + SQLite 单机方案到正式 Client-Server 架构的收敛：

- Spring Boot 成为统一 Backend。
- MySQL + MyBatis-Plus。
- 单 Maven Project Modular Monolith。
- Electron 是客户端。
- Admin Web 独立工程。
- Future Android 复用 API。
- JWT Access + Opaque Refresh。
- StorageService + storageKey。
- OpenAPI Contract。
- Maven Wrapper 3.9.x，保留 Windows 全局 Maven 3.6.2。
- 不自研 migration engine。
