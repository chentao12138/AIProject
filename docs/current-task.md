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

SPIKE-003 已完成：

- Flyway 版本化 migration 正式采用（新增 ADR-046，`Accepted`）。
- V001/V002 空库初始化。
- V001-only → V002 upgrade 保留旧数据 + V001 checksum 动态读取不变。
- Schema history 结构正确（V001 rank=1, V002 rank=2, success=true）。
- Repeated `migrate()` 在最新状态 `migrationsExecuted == 0`。
- Self-contained integration test（3 tests）可重复执行。
- Destructive clean 前 `SELECT DATABASE()` 精确匹配 guard。
- SPIKE-002（`DB_URL`）与 SPIKE-003（`FLYWAY_DB_URL`）环境变量族物理隔离。
- Full `mvnw.cmd clean test` 7/7 PASS。

**已知风险（生产部署前必须重新验证）**：Flyway 11.7.2 官方最高测试 MySQL 8.1，当前环境为 MySQL 8.4.x。本 SPIKE 范围内实际执行成功但**不能**宣称"官方支持"。Flyway 13.4.0 兼容实验（SPIKE-003-COMPAT-01）在当前 Spring Boot 3.5.0 依赖栈下失败（Jackson 3 API 缺失），已恢复 BOM 管理的 11.7.2。

SPIKE-004 已完成（Validation Complete）：

- Spring Security + Resource Server JWT 认证链路已验证。
- BCrypt PasswordEncoder 已验证。
- HS256 Access Token 签发 / 解码、无效签名 / 过期 token → `401` 已验证。
- Method Security + `@PreAuthorize` 已验证；认证后无授权 → `403`。
- JWT `sub` / `Authentication.getName()` + request `spaceId` → `SpikeSpaceAccess` → `SpikeSpaceMembershipRepository` → JdbcTemplate → 真实 MySQL 已贯通。
- SPIKE-only membership 三态：ACTIVE → allow，REVOKED → deny，missing → deny。
- 真实 JWT + HTTP + Method Security + Repository + MySQL E2E：`200 / 403 / 403`。
- SPIKE-004 focused regression 17/17 PASS。
- Full `mvnw.cmd clean test` 24/24 PASS，Failures=0，Errors=0，Skipped=0。

**明确 Deferred**：Opaque Refresh Token / hash、rotation / revoke、USER / ADMIN roles。

**仍非生产实现**：无正式 User、Login endpoint、LearningSpace / membership schema、production signing-key management、client token storage、正式 authorization model。`spike_space_membership`、`SpikeSpaceAccess`、`SpikeSpaceMembershipRepository`、`iss=aistudy-spike`、5-minute TTL、JVM 生命周期随机 HS256 key 均为 SPIKE-only。

## 当前唯一任务

**SPIKE-005：OpenAPI → TypeScript Client**

按 `docs/development-plan.md` 顺序。SPIKE-004 已完成认证 / Space Authorization 技术验证；本轮只验证 API contract 与 Desktop/Admin 复用同一生成 client/types，**不得开始核心业务代码**。

验证目标：

- `/v3/api-docs`。
- Desktop / Admin 消费同一生成 client/types。

**明确禁止（延续）：**

- 不开始核心业务实体。
- 不创建 LearningSpace / Source / KnowledgePoint 正式业务 schema。
- 不写正式业务 Java 代码（Spike 验证代码除外）。
- 不把 SPIKE-004 的 `spike_space_membership` / `SpikeSpaceAccess` / `SpikeSpaceMembershipRepository` 直接转为 production 实现。
- 不修改 ADR 或已 Accepted 的技术/业务决策，除非后续单独明确安排。
- 不 commit / push（用户手动执行）。

SPIKE-005 完成后继续按 `docs/development-plan.md` 执行后续 Spike。

## 下一阶段顺序

```text
SPIKE-003 Flyway / Versioned SQL          ← COMPLETE
SPIKE-004 Auth + Space Authorization       ← COMPLETE
SPIKE-005 OpenAPI → TypeScript Client        ← 当前
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
