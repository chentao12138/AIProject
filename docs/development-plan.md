# 开发计划

> 状态：**PLATFORM SKELETON STARTING**
>
> SPIKE-001 ~ SPIKE-005 已完成当前所需技术验证。SPIKE-006 ~ SPIKE-014 不再作为业务开发前置门槛，改为在相关能力真正进入实现时按需验证。下一阶段开始正式 Platform Skeleton，并从 LearningSpace vertical slice 开始。

## Phase 0：Baseline Final Review

✅ COMPLETE (2026-09-04)

- Technical + Business docs 无冲突。
- Hermes 只读理解文档，未经用户明确授权不 commit/push。

## Phase 1：Git Baseline

✅ COMPLETE (2026-09-04)

- Commit: `chore: establish project baseline` (`48cc876`)。
- 推送至 GitHub Private Repository (`main`)。

## Phase 2：Technical Spikes

每个 Spike 必须小、可丢弃、明确记录结果，不偷偷演变成正式业务代码。

### SPIKE-001 Spring Boot + Java 21 + Maven Wrapper

✅ COMPLETE (2026-09-04)

已验证：

- Spring Boot 3.5.0。
- Java 21 (Temurin 21.0.11)。
- 单 Maven Project。
- Maven Wrapper 固定 3.9.6。
- Windows `mvnw.cmd test` / `package` 成功。
- Executable JAR + `/health` endpoint 验证。

### SPIKE-002 MySQL + MyBatis-Plus

✅ COMPLETE (2026-09-04)

已验证：

- Spring Boot 3.5.0 + MyBatis-Plus 3.5.11 (`mybatis-plus-spring-boot3-starter`) 兼容。
- MySQL 8.4.10 (Docker)。
- 真实 `BaseMapper<SpikeRecord>` CRUD round-trip (insert / select / delete)。
- utf8mb4 / utf8mb4_unicode_ci 中文 round-trip + HEX 字节级断言。
- Packaged JAR 在 `--spring.profiles.active=it` 下真实连接 MySQL。
- `SpikeDatabaseStartupVerifier` (`@Profile("it")` ApplicationRunner) 打印 `SPIKE_DB_VERIFY_OK`。
- 3 个 profile 隔离：default (无 DB) / test (排除 DataSource) / it (真实 MySQL)。
- 验证表 `spike_record` 明显非正式业务表。

**事实备注 — SPIKE-002 实际验证范围**：

本次实际验证范围为 MyBatis-Plus / MySQL connectivity、CRUD、utf8mb4 中文 round-trip、packaged JAR 真实启动。

**原计划中未覆盖的项目**：

- Transaction：未在 SPIKE-002 单独验证。将在后续实际 Service / use-case 中验证。
- `spaceId` 组合索引：未在 SPIKE-002 单独验证。SPIKE-004 仅验证了 SPIKE-only membership authorization 链路；正式 LearningSpace / spaceId isolation / composite-index 留到 Platform Skeleton / 正式业务 schema 阶段验证。

### SPIKE-003 Flyway / Versioned SQL

✅ COMPLETE (2026-09-05)

已验证：

- Spring Boot 3.5.0 BOM 管理的 Flyway 11.7.2（不显式 pin）。
- V001/V002 空库初始化。
- V001-only 数据库升级到 V002。
- V001 升级前旧数据在 V002 应用后保留。
- V001 checksum 在 V002 应用前后保持不变（动态读取，不硬编码）。
- Schema history 结构正确（V001 rank=1, V002 rank=2, success=true）。
- Repeated `migrate()` 在最新状态不重复执行 migration（`migrationsExecuted == 0`）。
- Self-contained integration test（3 tests）在专用 schema `aistudy_flyway_test` 上可重复执行。
- Destructive clean 前 schema exact-match guard（`SELECT DATABASE()` 精确等于 `aistudy_flyway_test`）。
- SPIKE-002（`DB_URL`）与 SPIKE-003（`FLYWAY_DB_URL`）环境变量族物理隔离。
- Full `mvnw.cmd clean test` 7/7 PASS。

新增 ADR：ADR-046（`Accepted`），正式采用 Flyway 作为 migration executor。

**事实备注 — SPIKE-003 实际验证范围**：

本次实际验证范围为 Flyway versioned migration 基础能力、schema history、checksum、data preservation、idempotent migrate、self-contained 测试、destructive clean guard、DB env 隔离。

**已知兼容性风险（生产部署前必须重新验证）**：

- Flyway 11.7.2 官方最高测试 MySQL 版本为 8.1，当前环境为 MySQL 8.4.x。Flyway 每次启动会输出 compatibility warning："MySQL 8.4 is newer than this version of Flyway and support has not been tested."
- 本次 SPIKE 范围内实际执行成功，但**不能**因此宣称"Flyway 11.7.2 官方支持 MySQL 8.4"。
- 生产部署前必须重新验证 Spring Boot / Flyway / MySQL 版本组合，并记录当时的 compatibility 状态。

**Flyway 13.4.0 兼容实验（SPIKE-003-COMPAT-01）失败**：

临时将 `flyway-core` + `flyway-mysql` pin 到 `13.4.0` 时，Java 测试代码零修改可编译，但运行时抛出 `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`（Flyway 13.4.0 使用 Jackson 3，Spring Boot 3.5.0 依赖栈仍为 Jackson 2）。当前依赖栈下无法零修改升级到 Flyway 13.x；未来如需升级需同步升级 Jackson 到 3.x 或引入兼容桥接，属于跨版本栈升级，需独立 Spike。

实验后 pom.xml 已恢复 BOM 管理，`mvnw.cmd clean test` 7/7 PASS。

### SPIKE-004 Auth + Space Authorization

✅ COMPLETE — Validation Complete (2026-09-05)

> 本 SPIKE 已完成技术可行性验证，但**不等于 Production Auth Implementation Complete**。SPIKE-only 代码与 schema 不得直接视为正式业务实现。

**PROVEN IN SPIKE：**

- Spring Security 基线：`/health` 匿名 `200`，其它请求要求认证。
- BCrypt `PasswordEncoder` 可正确 encode / matches。
- HS256 JWT Access Token 签发与解码；无效签名、过期 token 均返回 `401`。
- Spring Security Resource Server Bearer JWT 认证链路。
- `@EnableMethodSecurity` + `@PreAuthorize`；已认证但无授权返回 `403`。
- JWT `sub` → `Authentication.getName()` + request `spaceId` → `SpikeSpaceAccess` → `SpikeSpaceMembershipRepository` → `JdbcTemplate` → 真实 MySQL。
- SPIKE-only `spike_space_membership` 三态语义：`ACTIVE` → allow，`REVOKED` → deny，missing → deny。
- 真实 JWT + HTTP + Method Security + Repository + MySQL 端到端：`200 / 403 / 403`。
- Flyway V003 可创建 SPIKE-only membership table。
- Focused regression：**17/17 PASS**。
- Full `mvnw.cmd clean test`：**24/24 PASS**，Failures=0，Errors=0，Skipped=0。

**DEFERRED TO PRODUCTION IMPLEMENTATION：**

- Opaque Refresh Token、服务端 hash、rotation、revoke。
- USER / ADMIN role model 与 authority mapping。
- 正式 User / Login endpoint。
- 正式 LearningSpace / membership schema、外键、角色与审计模型。
- Production signing key management / rotation / `kid` / JWKS 策略。
- Electron / Admin Web token storage。
- 统一 401/403 API error contract、session / CSRF 策略。

**SPIKE-only，不得当作生产方案：**

- `spike_space_membership`。
- `SpikeSpaceAccess`。
- `SpikeSpaceMembershipRepository`。
- `iss = "aistudy-spike"`。
- 5-minute TTL。
- Spring Context / JVM 生命周期内随机生成的 HS256 `SecretKey`。

**异常语义边界：**

`SpikeSpaceAccess` 不捕获 Repository / DB 异常；异常会向上传播，因此不会在数据库故障时静默授权。但本 SPIKE **没有**定义或验证“DB failure → 403”的正式授权失败契约。

### SPIKE-005 OpenAPI → TypeScript Client

✅ VALIDATION COMPLETE — Scope Adjusted (2026-09-05)

**已验证：**

- `springdoc-openapi` 接入并与当前 Spring Boot 3.5.0 / Java 21 工程编译、运行兼容。
- `GET /v3/api-docs` 真实返回 HTTP `200`、`Content-Type: application/json`、OpenAPI `3.1.0`。
- Contract 包含现有 `/health` 与 `/api/v1/spike/**` paths。
- OpenAPI `bearerAuth`：`type=http`、`scheme=bearer`、`bearerFormat=JWT`；受保护 endpoint 声明 bearerAuth，`/health` 不声明 bearerAuth。
- SPIKE response 已从通用 `Map` 改为 typed Java record；OpenAPI 生成明确 schema：`SpikeHealthResponse`、`SpikeStatusResponse`、`SpikeSpaceAuthorizationResponse`。
- response media type 明确为 `application/json`。
- Focused regression：17/17 PASS。
- Full `mvnw.cmd clean test`：**31/31 PASS**，Failures=0，Errors=0，Skipped=0。

**DEFERRED：**

- TypeScript client/types 实际生成。
- Desktop / Admin 共享 `api-client` package。
- generator 选型与 generated-code check-in 策略。

Scope 调整原因：不再继续使用 `Spike*` endpoint 为前端生成链路增加额外前置验证；上述 TypeScript 生成与共享 package 将在第一个真实业务 API（LearningSpace）出现后直接基于真实 Contract 落地。此调整必须保留为明确 Deferred，不能视为已验证完成。

**后续 Spike 策略调整：** SPIKE-006 ~ SPIKE-014 保留为技术检查清单，但不再串行阻塞 Platform Skeleton / 核心业务启动；仅在对应能力进入真实实现、且存在明确技术不确定性时执行。

### SPIKE-006 Electron Security + File Upload — DEFERRED / JUST-IN-TIME

验证：

- Electron + React + Vite Windows build。
- contextIsolation/nodeIntegration/sandbox。
- file dialog via preload/IPC。
- Desktop 将本地文件 bytes 上传到 Spring Boot。
- safeStorage demo。

### SPIKE-007 Admin Web — DEFERRED / JUST-IN-TIME

验证：

- React + Vite。
- `VITE_API_BASE_URL`。
- Admin auth。
- 调用同一 OpenAPI client。

### SPIKE-008 StorageService — DEFERRED / JUST-IN-TIME

验证：

- `D:\AIStudyData\resources`。
- storageKey。
- path traversal 防护。
- sha256。
- 可配置切换 Linux root。

### SPIKE-009 Source Ingestion / ZIP Safety — DEFERRED / JUST-IN-TIME

使用真实 `数据库系统工程师教程.zip`。

验证：

- 原始 ZIP 保存。
- zip-slip 防护。
- manifest。
- 图片识别。
- SourceDocument/Asset/Page 技术链路。

### SPIKE-010 OCR / Extraction — DEFERRED / JUST-IN-TIME

使用目录和第一章图片验证：

- 中文 OCR。
- 页码/标题。
- 1 / 1.1 / 1.1.1 层级识别可行性。
- OCR 与 AI Vision 的职责边界。
- 记录候选技术方案和误差。

### SPIKE-011 Page Ordering — DEFERRED / JUST-IN-TIME

针对 hash 文件名图片验证：

- filename evidence。
- printed page number。
- heading continuity。
- manual drag/reorder data model。
- confidence/issue。

目标不是证明“全自动 100% 排序”，而是证明自动建议 + 人工快速修正可用。

### SPIKE-012 AI Grounding / Citation — DEFERRED / JUST-IN-TIME

验证：

```text
spaceId
→ retrieve only current space ContentBlock
→ AI answer
→ citation back to page/block
```

使用 SenseNova 或当前可用 Provider，但业务接口不得绑定 Provider。

### SPIKE-013 MySQL Chinese Search — DEFERRED / JUST-IN-TIME

用真实 OCR/KnowledgePoint 中文文本验证 LIKE/FULLTEXT/ngram。

如果 P0 暂时不需要统一搜索，可在 Vertical Slice 后执行，但必须在 Search P1 前完成。

### SPIKE-014 Build — DEFERRED / JUST-IN-TIME

验证：

- Spring Boot jar
- Admin Web build
- Electron Windows package
- Windows/WSL node_modules 不混用

## Phase 3：Platform Skeleton

正式创建：

```text
server/
desktop/
admin-web/
```

基础设施：

- common error/ProblemDetail
- requestId/logging
- auth/user/role
- LearningSpace ✅（BUSINESS-001 Complete，见下）
- OpenAPI generation ✅（SPIKE-005 技术验证 + 真实业务 Contract 已进入 /v3/api-docs）
- StorageService
- DB migration baseline

验收：能登录、创建两个 LearningSpace，并证明服务端隔离。

### BUSINESS-001 LearningSpace Vertical Slice — ✅ COMPLETE（User Runtime Verified）

- `POST /api/v1/spaces` / `GET /api/v1/spaces` / `GET /api/v1/spaces/{spaceId}`。
- V004 `learning_space` 表；owner boundary 落实在 SQL（`id + owner_subject`）。
- Focused 16/16 PASS；Full clean test user-reported BUILD SUCCESS（FIX-02 后）。
- 详情见 development-log.md。

### BUSINESS-002 Source Vertical Slice — ✅ COMPLETE（User Runtime Verified）

- `POST /api/v1/spaces/{spaceId}/sources` / `GET .../sources` / `GET .../sources/{sourceId}`。
- V005 `source` 表（FK → learning_space）；JOIN 防 IDOR；CSRF path-scoped。
- Focused Maven verification PASS + full clean Maven verification PASS（用户确认）。
- 详情见 development-log.md。

### Shared API Client Foundation — ✅ COMPLETE（User Runtime Verified）

- `packages/api-client`：openapi-typescript + openapi-fetch + TokenProvider 注入。
- `npm run api:generate` PASS + `npm run typecheck` PASS（用户确认）。
- 详情见 development-log.md。

### BUSINESS-003 Knowledge Catalog Vertical Slice — ✅ COMPLETE（User Runtime Verified）

- KnowledgeCategory（POST/GET 列表/GET 详情）+ USER_CURATED KnowledgePoint（POST/GET 列表/GET 详情/POST publish）。
- V006 knowledge_category + V007 knowledge_point（FK 链 learning_space → category → point；owner boundary 全 SQL）。
- DRAFT → PUBLISHED 生命周期（真幂等，NO-OP 不刷新时间戳）；parent/category 同 space invariant；soft-delete 读规则（deleted_at IS NULL）。
- tests：KnowledgeCatalogVerticalSliceIntegrationTest 30 + KnowledgeOpenApiContractTest 8 + Flyway V007。
- 真实 runtime：attempt #1 41/1/16 BUILD FAILURE（self-FK cleanup + DATETIME(6) precision 两个 root cause）→ RUNTIME-FIX-01 修复 → attempt #2 **100/0/0/0 BUILD SUCCESS**（用户确认）。
- Shared contract：`npm run api:generate` PASS（openapi-typescript 7.13.0，generated 含 knowledge paths）+ final `npm run typecheck` PASS（wrapper 后，无报错）。
- Shared client：knowledge wrapper 7 方法已实现（client.ts，类型来自 generated）。
- 剩余：用户 Git closeout。详情见 development-log.md。

**BUSINESS-004：NOT STARTED**（next task to be decided after BUSINESS-003 closeout；KnowledgePointSource 依赖 ContentBlock，当前 content ingestion 未实现，不擅自开始）。

## Phase 4：Vertical Slice A — Source → Knowledge

目标：让真实教材进入学习库。

顺序：

0. ✅ Source metadata vertical slice（BUSINESS-002：LearningSpace → Source metadata，无 upload/ingest）
1. SourceDocument / SourceAsset。
2. upload。
3. IngestionJob。
4. SourcePage。
5. page ordering/manual review。
6. extraction/OCR。
7. SourceOutlineNode / ContentBlock。
8. Admin/Desktop review。
9. publish。
10. KnowledgePoint + Source provenance。

验收数据：`数据库系统工程师教程.zip`。

## Phase 5：Vertical Slice B — Question → Practice → Wrong

1. Question/Option。
2. KnowledgePoint relation。
3. QuestionSource。
4. Admin question management。
5. PracticeSession/Question/Answer。
6. WrongQuestion。
7. AI explanation/variant draft。

验收：针对第一章完成真实 10 题练习。

## Phase 6：Review + Mastery + StudyPlan

1. ReviewTask/ReviewRecord。
2. Mastery current state。
3. simple explainable evidence policy。
4. StudyPlan/StudyTask。
5. 今日任务 Desktop UI。

当前不实现复杂 SM-2。

## Phase 7：Exam

1. Exam/ExamPaper。
2. ExamQuestion snapshot。
3. ExamAttempt/Answer。
4. timer/submit。
5. objective scoring。
6. ExamResult。
7. structured ExamDiagnosis。
8. feedback to Mastery/Review/Plan。
9. Admin exam management。

验收：完成一次“第1章测试”，历史试卷可还原。

## Phase 8：AI Tutor + Admin Governance

在前面已有 Source/Knowledge/Question 基础上完善：

- source-grounded AI tutor
- citation UI
- content generation review
- AI usage/error record
- Admin ingestion/job dashboard

AI 不得绕过 LearningSpace scope。

## Phase 9：Statistics / Search / Polish

- LearningSpace dashboard
- mastery distribution
- practice/wrong/review metrics
- exam history
- search
- UX polish
- backup/export basics

## Phase 10：Server Deployment

- Linux
- MySQL
- Nginx
- HTTPS
- Spring Boot
- Admin static build
- Storage migration
- backup/restore drill
- Desktop API base switch

## Phase 11：Android

PC + Admin + Server API 稳定后再新增 Android 技术 ADR。

## 任务模板

正式 TASK 必须包含：

```text
Goal
Scope
Non-goals
Affected modules/files
Data model/migration
API contract
Security/space isolation
Acceptance criteria
Tests
Docs impact
```

## 开发纪律

1. Hermes 修改前 `git status` / `git diff`。
2. 未经用户授权不 commit/push。
3. 每次 DB 结构变化使用新 migration。
4. 自动测试不碰真实数据库。
5. 新增跨表关系必须检查 space invariant。
6. AI 生成数据默认不直接 published。
7. 不为了一个 Task 私自引入 Redis/MQ/微服务/新搜索引擎。
8. 每个阶段完成后更新 development-log。
