# 开发计划

> 状态：**TECHNICAL SPIKES IN PROGRESS**
>
> SPIKE-001 / SPIKE-002 已完成并提交。SPIKE-003 待启动。后续按原顺序执行。

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
- `spaceId` 组合索引：未在 SPIKE-002 单独验证。LearningSpace / spaceId isolation / composite-index 将在 SPIKE-004 或 Platform Skeleton 阶段进行真实验证。

### SPIKE-003 Flyway / Versioned SQL

NEXT

验证：

- `V001/V002`。
- 空库初始化。
- upgrade。
- schema history。

如果验证通过并准备创建真实持久数据库：新增 ADR，正式采用 Flyway。

当前 Flyway 仍为 SPIKE Gate，未被 Accepted。

### SPIKE-004 Auth + Space Authorization

验证：

- Spring Security。
- JWT Access。
- Opaque Refresh hash + rotation/revoke。
- USER/ADMIN。
- 用户 A 不能访问用户 B 的 LearningSpace。

### SPIKE-005 OpenAPI → TypeScript Client

验证：

- `/v3/api-docs`。
- Desktop/Admin 消费同一生成 client/types。

### SPIKE-006 Electron Security + File Upload

验证：

- Electron + React + Vite Windows build。
- contextIsolation/nodeIntegration/sandbox。
- file dialog via preload/IPC。
- Desktop 将本地文件 bytes 上传到 Spring Boot。
- safeStorage demo。

### SPIKE-007 Admin Web

验证：

- React + Vite。
- `VITE_API_BASE_URL`。
- Admin auth。
- 调用同一 OpenAPI client。

### SPIKE-008 StorageService

验证：

- `D:\AIStudyData\resources`。
- storageKey。
- path traversal 防护。
- sha256。
- 可配置切换 Linux root。

### SPIKE-009 Source Ingestion / ZIP Safety

使用真实 `数据库系统工程师教程.zip`。

验证：

- 原始 ZIP 保存。
- zip-slip 防护。
- manifest。
- 图片识别。
- SourceDocument/Asset/Page 技术链路。

### SPIKE-010 OCR / Extraction

使用目录和第一章图片验证：

- 中文 OCR。
- 页码/标题。
- 1 / 1.1 / 1.1.1 层级识别可行性。
- OCR 与 AI Vision 的职责边界。
- 记录候选技术方案和误差。

### SPIKE-011 Page Ordering

针对 hash 文件名图片验证：

- filename evidence。
- printed page number。
- heading continuity。
- manual drag/reorder data model。
- confidence/issue。

目标不是证明“全自动 100% 排序”，而是证明自动建议 + 人工快速修正可用。

### SPIKE-012 AI Grounding / Citation

验证：

```text
spaceId
→ retrieve only current space ContentBlock
→ AI answer
→ citation back to page/block
```

使用 SenseNova 或当前可用 Provider，但业务接口不得绑定 Provider。

### SPIKE-013 MySQL Chinese Search

用真实 OCR/KnowledgePoint 中文文本验证 LIKE/FULLTEXT/ngram。

如果 P0 暂时不需要统一搜索，可在 Vertical Slice 后执行，但必须在 Search P1 前完成。

### SPIKE-014 Build

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
- LearningSpace
- OpenAPI generation
- StorageService
- DB migration baseline

验收：能登录、创建两个 LearningSpace，并证明服务端隔离。

## Phase 4：Vertical Slice A — Source → Knowledge

目标：让真实教材进入学习库。

顺序：

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
