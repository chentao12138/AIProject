# Current Task

> 状态：**TECHNICAL SPIKES IN PROGRESS**

## 已完成

Git Baseline 已建立并推送至 GitHub Private Repository。

SPIKE-001 已完成：

- Spring Boot 3.5.x。
- Java 21 (Temurin)。
- Maven Wrapper 3.9.6，保留 Windows 全局 Maven 3.6.2。
- 单 Maven Project Modular Monolith。
- `mvnw.cmd test` / `mvnw.cmd package` 成功。
- 生成 executable JAR (`target/server-0.1.0-SPIKE.jar`)。
- `/health` endpoint 验证 HTTP 200。

SPIKE-002 已完成：

- MyBatis-Plus 3.5.11。
- MySQL 8.4 (Docker)。
- 真实 `BaseMapper<SpikeRecord>` CRUD (insert / select / delete round-trip)。
- utf8mb4 / utf8mb4_unicode_ci 中文 round-trip 通过。
- 真实 integration test (`@ActiveProfiles("it")`)。
- packaged JAR 在 `--spring.profiles.active=it` 下真实连接 MySQL。
- `SpikeDatabaseStartupVerifier` 打印 `SPIKE_DB_VERIFY_OK`。

## 当前唯一任务

**SPIKE-003：Flyway / Versioned SQL**

只验证 migration 技术：

- `V001 / V002`。
- 空库初始化。
- upgrade。
- schema history。

**明确禁止：**

- 不开始核心业务实体。
- 不创建 LearningSpace / Source / KnowledgePoint 正式业务 schema。
- 不写业务 Java 代码。
- 不引入 Flyway 为正式依赖（Flyway 当前仍是 SPIKE-003 Gate）。
- 不修改 ADR（Flyway 未被 Accepted，等 SPIKE-003 通过后由用户决定是否新增 ADR）。

SPIKE-003 通过后按 `docs/development-plan.md` 继续后续 Spike。

## 下一阶段顺序

```text
SPIKE-003 Flyway / Versioned SQL          ← 当前
SPIKE-004 Auth + Space Authorization
SPIKE-005 OpenAPI → TypeScript Client
SPIKE-006 Electron Security + File Upload
SPIKE-007 Admin Web
SPIKE-008 StorageService
SPIKE-009 Source Ingestion / ZIP Safety
SPIKE-010 OCR / Extraction
SPIKE-011 Page Ordering
SPIKE-012 AI Grounding / Citation
SPIKE-013 MySQL Chinese Search
SPIKE-014 Build
→ Platform Skeleton
→ Vertical Slice A (Source → Knowledge)
→ Vertical Slice B (Question → Practice → Wrong)
→ Review + Mastery + StudyPlan
→ Exam
→ AI Tutor + Admin Governance
→ Statistics / Search / Polish
→ Server Deployment
→ Android
```

## 当前禁止

- 不开始核心业务实体 / 正式业务 schema。
- 不 commit / push（用户手动执行）。
- 不修改 ADR 或已 Accepted 的技术/业务决策。
- 不重新设计产品 / 业务 / 架构。
- 不引入 Redis / MQ / 微服务 / 新搜索引擎。
