# Current Task

## Objective

LONG-RUN-001 三段连续开发：
- PHASE A: BUSINESS-001 LearningSpace closeout（✅ COMPLETE）
- PHASE B: BUSINESS-002 Source Vertical Slice（✅ COMPLETE）
- PHASE C: Shared OpenAPI Types Foundation（✅ COMPLETE）
- PHASE D: 整体静态回归与文档收口（✅ COMPLETE）

## Current Phase

**LONG-RUN-001 COMPLETE — Awaiting Git Closeout**
（全部实现 + 用户 runtime verification 完成；等用户 git add/commit/push；之后才允许开始 BUSINESS-003）

## Completed

- SPIKE-001~005：全部 COMPLETE（含 OpenAPI Contract 验证，31/31 PASS 历史记录）。
- BUSINESS-001 LearningSpace Vertical Slice：**COMPLETE — USER RUNTIME VERIFIED**。
  - `POST /api/v1/spaces` / `GET /api/v1/spaces` / `GET /api/v1/spaces/{spaceId}`。
  - V004 `learning_space`；SQL owner boundary；typed DTO；bearerAuth；application/json。
  - Focused 16/16 PASS；FIX-01（OpenAPI list schema 断言 + path-scoped CSRF ignore）、FIX-02（SpikeRecordMapperIntegrationTest @MockitoBean）后 user-reported clean test BUILD SUCCESS。
- BUSINESS-002 Source Vertical Slice：**COMPLETE — USER RUNTIME VERIFIED**。
  - `POST /api/v1/spaces/{spaceId}/sources` / `GET .../sources` / `GET .../sources/{sourceId}`。
  - V005 `source`（FK → learning_space）；JOIN 防 IDOR；CSRF path-scoped；source_type 用 docs 定义值；status REGISTERED。
  - Focused Maven verification PASS + full clean Maven verification PASS（用户确认）。
- Shared OpenAPI Types Foundation（packages/api-client）：**COMPLETE — USER RUNTIME VERIFIED**。
  - `npm run api:generate` PASS + `npm run typecheck` PASS（用户确认）。

## In Progress

无。LONG-RUN-001 全部阶段完成。当前等待 Git closeout（用户手动 commit）。

## Key Design Decisions

- owner boundary 一律在 SQL：LearningSpace `id + owner_subject`；Source get 用 `JOIN learning_space ... WHERE s.id=? AND s.space_id=? AND ls.owner_subject=?`。
- Source 不存冗余 owner_subject；ownership 来自 LearningSpace。
- 404 统一表达不存在/非本人（防探测）。
- CSRF：仅 Bearer API 路径 ignore（/api/v1/spaces、/api/v1/spaces/**、显式 source 路径）；无全局 disable；无全局 /api/v1/** ignore。
- source_type 用 data-model.md §5.1 定义值（DESKTOP_UPLOAD/DESKTOP_FOLDER_IMPORT/ADMIN_UPLOAD/ADMIN_MANUAL）；status 用 REGISTERED（无 processing pipeline）。
- 列表 typed List，分页 defer。
- Shared client：openapi-typescript + openapi-fetch，token 由调用方注入 getAccessToken()（不碰 localStorage/cookie）。
- packages/api-client 位于 repo root；Windows Node 生命周期（technology-selection.md）。

## Files Added / Modified

（BUSINESS-001 未提交基线 + LONG-RUN-001 增量；全部未 commit）

- 生产：`server/src/main/resources/db/migration/V005__create_source.sql`；`server/src/main/java/com/aistudy/server/source/{entity, mapper, service, controller, dto}/*`
- 生产（BUSINESS-001）：`server/src/main/java/com/aistudy/server/space/*`；`V004__create_learning_space.sql`
- Security：`SpikeSecurityConfig.java`（CSRF ignore 扩展）
- 测试：`SourceVerticalSliceIntegrationTest`、`SourceOpenApiContractTest`、`LearningSpaceVerticalSliceIntegrationTest`、`LearningSpaceOpenApiContractTest`、`FlywayMigrationIntegrationTest`（V005 更新）、旧 test @MockitoBean 兼容（AiStudyApplicationTests/SpikeHealthControllerTest/SpikeJwtTokenServiceTest/SpikePasswordEncoderTest/SpikeSecurityBoundaryTest/SpikeOpenApiContractTest/SpikeRecordMapperIntegrationTest）
- 共享包：`packages/api-client/{package.json, tsconfig.json, src/*, scripts/*}`
- docs：development-log.md（追加）、development-plan.md、current-task.md

## Runtime Evidence

- BUSINESS-001：Focused 16/16 PASS；clean test user-reported BUILD SUCCESS（用户提供）。
- SPIKE-005：31/31 PASS（历史）。
- BUSINESS-002：Focused Maven verification PASS + full clean Maven verification PASS（用户确认；未提供精确 test count，不编造数字）。
- Shared client：`npm run api:generate` PASS（openapi-typescript 7.13.0 真实生成 api.d.ts + openapi.json）+ `npm run typecheck` PASS（用户确认）。
- 备注：server 停止后再次 api:generate 的 ECONNREFUSED 是预期环境状态（服务未运行），非代码 defect。

## Static Evidence

- git diff --check 干净。
- 全部新 Java 文件 token 级语法检查通过（字符串/括号/注释配对）。
- 生产代码无 Map response、无 request DTO 含 ownerSubject、无 csrf.disable()、无全局 permitAll、无 H2。
- Owner-scoped SQL / JOIN 防 IDOR 通过 grep 验证。
- node_modules 被 .gitignore 覆盖；generated api.d.ts / openapi.json / package-lock.json 保留（不删除）。

## Deferred

- Source upload / multipart / IngestionJob / SourcePage / OCR / AI / KnowledgePoint extraction。
- SourceAsset 表（storageKey 元数据属 SourceAsset per data-model §5.2）。
- LearningSpace 分页、ProblemDetail 错误码、正式 User/Login/Refresh/USER-ADMIN role。
- StorageService（ADR-033 已定接口，未实现）。
- shared client 的 runtime 生成/install/typecheck 证据（需用户运行 npm + Spring Boot）。

## Known Risks

- Flyway 11.7.2 vs MySQL 8.4 WARN（既有，生产前重验）。
- HS256 JVM 随机 key（SPIKE 级，production key management defer）。
- 新 migration V005 含 FK → Flyway 顺序依赖 V004；flyway_it 环境重复 migrate 幂等。
- generated api.d.ts / openapi.json 未 commit 决策待定（generated-code check-in 策略 Deferred）。
- 全部变更未 commit（等用户 Git closeout）。

## Next Actions

1. `git diff --check`（已执行，clean）
2. `git status --short`（已执行，见下）
3. `git diff --stat`（已执行，见下）
4. 用户 review 全部变更
5. 用户 `git add` / `git commit` / `git push`（手动执行）
6. **仅当 Git baseline clean 后才开始 BUSINESS-003 KnowledgePoint Vertical Slice**

## User Verification Commands（已完成，历史记录）

```text
# 1) focused BUSINESS-002 tests（用户已运行，PASS）
.\mvnw.cmd -Dtest="SourceVerticalSliceIntegrationTest,SourceOpenApiContractTest,FlywayMigrationIntegrationTest" test

# 2) full clean test（用户已运行，PASS）
.\mvnw.cmd clean test

# 3) shared client（用户已运行，PASS）
cd packages\api-client
npm run api:generate
npm run typecheck
```

## Resume Instructions

如果上下文压缩：重新读取本文件、development-log.md 最后 150-200 行、git status --short。**不要重新实现 BUSINESS-001/002**（已 COMPLETE + user runtime verified）。当前状态 = 等待用户 Git closeout；用户 commit 后开始 BUSINESS-003 KnowledgePoint（未开始，不得擅自启动）。不要运行 Maven / git 写操作。
