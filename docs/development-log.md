# Development Log

> 架构决策以 `decisions.md` 为准，业务总定义以 `business-baseline.md` 为准。本文件只记录阶段事实。

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
