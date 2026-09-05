# Current Task

## Objective

LONG-RUN-002 = BUSINESS-003 Knowledge Catalog Vertical Slice：
- KnowledgeCategory（分类树，parent same-space invariant）
- USER_CURATED KnowledgePoint（DRAFT → PUBLISHED 生命周期）
- OpenAPI Contract + Tests + Documentation Persistence

## Current Phase

**LONG-RUN-002 / BUSINESS-003 — COMPLETE — AWAITING GIT CLOSEOUT**
（用户 Maven 100/0/0/0 BUILD SUCCESS + api:generate PASS + final npm run typecheck PASS；等用户 git add/commit/push）

## Completed

- SPIKE-001~005 COMPLETE；BUSINESS-001 LearningSpace COMPLETE（user verified）；BUSINESS-002 Source COMPLETE（user verified）；Shared API Client COMPLETE（user verified）。用户已 commit：`906d3b4 feat: implement learning spaces and sources`。
- **BUSINESS-003（本轮，全部实现，未 runtime 验证）**：
  - V006 knowledge_category + V007 knowledge_point migrations
  - knowledge/category/* + knowledge/point/* 全栈（entity/mapper/service/controller/dto）
  - KnowledgeCatalogVerticalSliceIntegrationTest 30 tests + KnowledgeOpenApiContractTest 8 tests
  - FlywayMigrationIntegrationTest V005→V007；9 个旧 test/it context +@MockitoBean
  - **BUSINESS-003-RUNTIME-FIX-01（真实 runtime failure 修复，attempt #1 = 41/1/16 BUILD FAILURE）**：
    - A. integration cleanup 未处理 knowledge_category self-FK（fk_knowledge_category_parent）→ 新增 bottom-up leaf-delete helper（含残留检查，残留即 throw，不静默删 learning_space）
    - B. publish 首次时间未归一化 → `LocalDateTime.now().truncatedTo(ChronoUnit.MICROS)`（DATETIME(6) 精度，response/UPDATE 参数/DB round-trip 三处完全一致）
    - republishIsIdempotent 强化：response equality + DB equality 双维度（dbPublishedAt1==dbPublishedAt2、dbUpdatedAt1==dbUpdatedAt2、response==DB exact match）
  - **BUSINESS-003-SHARED-CLIENT-CLOSEOUT（用户 runtime verified + knowledge wrapper）**：
    - 用户 Maven full clean test：Tests run: 100, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
    - npm run api:generate PASS（openapi-typescript 7.13.0）→ generated api.d.ts/openapi.json 刷新自 live /v3/api-docs（含 knowledge paths/schemas）
    - npm run typecheck PASS（knowledge wrapper 加入前基线）+ final typecheck PASS（wrapper 后，用户真实运行无报错）
    - client.ts 新增 7 个 knowledge wrapper（create/list/get category + create/list/get point + publish），request 类型全部来自 generated components['schemas']；无 any / 无 @ts-ignore / 无手写 DTO / 无 generated 编辑

## In Progress

无。BUSINESS-003 全部完成（实现 + 测试 + runtime verified + shared client），等用户 Git closeout。

## Key Design Decisions

- originType=USER_CURATED、status=DRAFT 由服务器固定；客户端 DTO 无这些字段
- publish 真幂等：已 PUBLISHED 再 publish = NO-OP（不执行 UPDATE、不刷新 publishedAt/updatedAt，返回当前资源 200）（PRE-RUNTIME-REVIEW-FIX 修正）
- difficulty free-form VARCHAR(32)（docs 无枚举）；sortOrder 客户端可提交（task B1）
- 所有读 owner-scoped SQL（JOIN learning_space）——**含 list 查询**（PRE-RUNTIME-REVIEW-FIX 修正：category/point list SQL 均带 owner_subject 谓词）；point 读 deleted_at IS NULL
- 404 anti-probing（不存在/非本人/cross-space 统一 404）
- CSRF：/api/v1/spaces/** 已覆盖 knowledge 嵌套路由 → SecurityConfig 零改动
- 无 category name unique（MySQL NULL-parent 语义）

## Database Contract

- V006 knowledge_category：id, space_id FK→learning_space, parent_id FK→knowledge_category NULL, name VARCHAR(128) NOT NULL, description VARCHAR(512) NULL, sort_order INT DEFAULT 0, created_at, updated_at；idx (space_id, parent_id, sort_order, id)；FK×2 无 CASCADE
- V007 knowledge_point：id, space_id FK→learning_space, category_id FK→knowledge_category NULL, title VARCHAR(255) NOT NULL, summary VARCHAR(1000) NULL, content TEXT NOT NULL, origin_type VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL, difficulty VARCHAR(32) NULL, created_by_user_id VARCHAR(128) NULL, created_at, updated_at, published_at NULL, deleted_at NULL；idx (space_id, status, category_id) + (space_id, created_at, id)；FK×2 无 CASCADE

## API Contract

- POST /api/v1/spaces/{spaceId}/knowledge-categories → 201（name @NotBlank, description, parentId, sortOrder）
- GET .../knowledge-categories → 200 typed List（sort_order ASC）
- GET .../knowledge-categories/{categoryId} → 200/404
- POST .../knowledge-points → 201（title @NotBlank, summary, content @NotBlank, categoryId, difficulty）
- GET .../knowledge-points → 200 typed List（non-deleted, newest first）
- GET .../knowledge-points/{knowledgePointId} → 200/404
- POST .../knowledge-points/{knowledgePointId}/publish → 200（无 body；DRAFT→PUBLISHED）
- 全部 bearerAuth + application/json + typed records

## Security / Space Isolation

- parent same-space invariant：category create 时 parentId 必须同 space+owner（scoped SQL）→ 404
- category same-space invariant：point create 时 categoryId 必须同 space+owner → 404
- publish：scoped get（同事务）→ 显式 UPDATE WHERE id+space_id+deleted_at IS NULL
- 无 unscoped selectById 业务读；无 csrf.disable()；无全局 permitAll

## Files Added / Modified

- 新增：V006__create_knowledge_category.sql、V007__create_knowledge_point.sql
- 新增：knowledge/category/{entity,mapper,service,controller,dto}/*（5 production + 2 dto）
- 新增：knowledge/point/{entity,mapper,service,controller,dto}/*（5 production + 2 dto）
- 新增 test：knowledge/KnowledgeCatalogVerticalSliceIntegrationTest.java、knowledge/KnowledgeOpenApiContractTest.java
- 修改：FlywayMigrationIntegrationTest（V005→V007）；9 个旧 test +@MockitoBean KnowledgeCategoryMapper/KnowledgePointMapper
- docs：current-task.md、development-log.md（+237 行）、development-plan.md

## Tests Added / Modified

- KnowledgeCatalogVerticalSliceIntegrationTest：30（12 category + 18 point；flyway-it 真实 MySQL；FK-aware cleanup：point→category→source→learning_space）
- KnowledgeOpenApiContractTest：8（paths/schemas/无 server-controlled 字段/array/$ref/bearerAuth）
- FlywayMigrationIntegrationTest：V007 断言（fresh=7/upgrade=6/second=0；两表 columns/indexes/FKs/charset）
- 9 个旧 context 兼容（不扩大 @MapperScan，不接 aistudy_spike）

## Runtime Evidence

- BUSINESS-003 focused attempt #1（用户真实运行）：**Tests run: 41, Failures: 1, Errors: 16, BUILD FAILURE**
  - KnowledgeOpenApiContractTest 8/8 PASS；FlywayMigrationIntegrationTest 3/3 PASS
  - V006/V007 migrations 本身真实运行成功
  - 1 failure = republish timestamp equality（response nanosecond .6338487 vs DB DATETIME(6) microsecond .633849）
  - 16 errors = cleanup `DELETE FROM knowledge_category WHERE space_id IN (...)` 触犯 fk_knowledge_category_parent（self-FK，parent 被 child 引用）
  - RUNTIME-FIX-01 后修复
- BUSINESS-003 attempt #2（用户真实运行，RUNTIME-FIX-01 后）：**Maven full clean test — Tests run: 100, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**
- Shared contract（用户真实运行）：`npm run api:generate` PASS（openapi-typescript 7.13.0）→ generated api.d.ts / openapi.json 刷新自 live /v3/api-docs（含 knowledge paths/schemas）
- TypeScript（用户真实运行）：`npm run typecheck` PASS（wrapper 前基线）+ final `npm run typecheck` PASS（wrapper 后，无报错）
- 历史：BUSINESS-001/002 + Shared client user verified

## Static Evidence

- git diff --check clean；55 java 文件 token 级语法+注释 hazard 全扫 ALL SOUND
- 危险项扫描全过：无 Map response、无 request DTO server-controlled 字段、无 selectById 业务读、无 csrf.disable()、无 H2、无 Redis/MQ/ES、无 AI provider、无 fake provenance

## Deferred

- KnowledgePointSource / SOURCE_DERIVED / AI_DERIVED / ADMIN_CURATED（依赖 ContentBlock/ingestion + Admin API）
- point/category update/delete/archive/move；分页；ProblemDetail；正式 User/Login/Refresh/USER-ADMIN
- shared client knowledge wrappers：本轮已实现（BUSINESS-003-SHARED-CLIENT-CLOSEOUT）；剩余 = 用户最终 typecheck + Git closeout
- BUSINESS-004 Question（未开始；范围待定，KnowledgePointSource 依赖 ContentBlock）

## Known Risks

- Flyway 11.7.2 vs MySQL 8.4 WARN（既有）
- V006/V007 FK 依赖 V004/V005 顺序（Flyway 版本序保证）
- generated api.d.ts 已含 knowledge paths/schemas（用户 regenerate 产物，不手改）；knowledge wrapper 后最终 typecheck 待用户确认
- 无已知 runtime 阻塞（用户 Maven full clean test 100/0/0/0 BUILD SUCCESS）

## Next Actions

1. git diff --check
2. git status --short
3. git diff --stat
4. user git add
5. git diff --cached --check
6. user commit
7. user push
8. clean baseline before next business task（BUSINESS-004 范围待定，不擅自开始）

## Resume Instructions

如果未来上下文丢失，先读取本文件（current-task.md）和 development-log.md，**不要重新实现 BUSINESS-003**（V006/V007 migrations、knowledge 包全栈、KnowledgeCatalogVerticalSliceIntegrationTest、KnowledgeOpenApiContractTest、RUNTIME-FIX-01 bottom-up cleanup + microsecond publish、client.ts knowledge wrapper 7 方法均已完成并通过用户验证；generated api.d.ts/openapi.json 是 api:generate 产物不手改）。当前 = BUSINESS-003 COMPLETE，等用户 Git closeout（见 Next Actions）。不要开始 BUSINESS-004。
