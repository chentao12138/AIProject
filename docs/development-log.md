# Development Log

> 架构决策以 `decisions.md` 为准，业务总定义以 `business-baseline.md` 为准。本文件只记录阶段事实。

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
