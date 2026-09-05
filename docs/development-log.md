# Development Log

> 架构决策以 `decisions.md` 为准，业务总定义以 `business-baseline.md` 为准。本文件只记录阶段事实。

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

## 2026-09 — BUSINESS-ALIGNMENT Complete

用户确认产品核心为个人 AI 学习工作台。

正式加入：

- LearningSpace 一级数据隔离。
- Source 真实资料作为数据源。
- Desktop 文件/文件夹/ZIP/PDF/图片导入。
- Admin Web 手工录入和内容治理。
- RAW → EXTRACTED → DERIVED 分层。
- OCR/结构化/Review/Publish。
- Source provenance / 查看原文。
- KnowledgePoint 核心学习单元。
- Note。
- Question Bank。
- Practice。
- WrongQuestion。
- Review。
- Exam 作为独立一级核心模块。
- ExamResult / ExamDiagnosis。
- Mastery。
- StudyPlan。
- AI Tutor + space-scoped retrieval/citation。

Practice 定义为训练；Exam 定义为测量。

考试结果必须反馈到 Mastery / Review / StudyPlan。

Admin Web 正式成为内容生产、审核、题库、考试和任务治理后台。

## 第一套真实数据源

确定 `数据库系统工程师教程.zip` 作为第一套真实摄取验证样本。

样本包含：

- 目录图片。
- 第一章教材图片。
- 数字文件名和 hash 风格文件名混合。

因此正式需求包含：

- page ordering suggestion。
- low-confidence review。
- manual drag/reorder。
- OCR。
- TOC/outline extraction。
- KnowledgePoint provenance。

## 2026-09-04 — SPIKE-001 Complete

技术栈基线验证。已提交到 GitHub Private Repository。

### Code-defined evidence

- `server/pom.xml` 声明 Spring Boot 3.5.0 (parent) + Java 21。
- `server/pom.xml` 声明单 Maven Project (`groupId=com.aistudy / artifactId=server`)。
- `server/.mvn/wrapper/maven-wrapper.properties` 固定 Maven Wrapper 3.9.6。
- Windows 全局 Maven 3.6.2 未被修改（环境约定）。
- `server/src/main/java/com/aistudy/server/spike/SpikeHealthController.java` 定义 `/health` endpoint。
- 源码定义 3 个测试方法：`AiStudyApplicationTests`（context loads）+ `SpikeHealthControllerTest`（`/health` smoke）。

### Runtime-verified evidence

- `mvnw.cmd test`：Surefire 报告 Tests run: 3, Failures: 0, Errors: 0。
- `mvnw.cmd package`：生成 executable Spring Boot JAR (`target/server-0.1.0-SPIKE.jar`)。
- JAR 启动后 `GET /health` 实际返回 HTTP 200，body 包含 `status=UP`, `service=AIStudyServer`, `phase=SPIKE-001`。

## 2026-09-04 — SPIKE-002 Complete

MyBatis-Plus + MySQL 集成验证。已提交到 GitHub Private Repository。

### Code-defined evidence

- `server/pom.xml` 声明 MyBatis-Plus 3.5.11 (`mybatis-plus-spring-boot3-starter`) + MySQL Connector/J。
- `deploy/local/mysql/init/01_spike_record.sql` 定义 `spike_record` 表，字符集 `utf8mb4`、排序 `utf8mb4_unicode_ci`，注释标记 "SPIKE-002 verification table. Not a business entity."。
- `deploy/local/mysql/compose.yml` 声明 MySQL 8.4 image + utf8mb4 server charset。
- `SpikeRecordMapper extends BaseMapper<SpikeRecord>`。
- `SpikeRecordMapperIntegrationTest` 使用 `@ActiveProfiles("it")`，定义 2 个测试方法：
  - `insertSelectDeleteRoundTrip`：BaseMapper INSERT → SELECT → HEX 字节级 UTF-8 断言 → DELETE round-trip；中文字段断言 `"数据库系统工程师"` + `"MyBatis-Plus 与 MySQL 中文写入验证"`。
  - `connectionIsReallyMySql8AndCharsetIsUtf8mb4`：断言 MySQL version 以 `8.` 开头、database charset=`utf8mb4`、database/table/column collation 以 `utf8mb4` 开头。
- `SpikeDatabaseStartupVerifier` 使用 `@Profile("it")` + `ApplicationRunner`，代码定义 6 条 SQL：`SELECT 1`、`SELECT VERSION()`、`SELECT DATABASE()`、`SELECT @@character_set_database`、`SELECT @@collation_database`、`SELECT COUNT(*) FROM spike_record`。
- `application-it.yml` 使用 `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` 环境变量占位。
- `application-test.yml` 排除 `DataSourceAutoConfiguration` / `DataSourceTransactionManagerAutoConfiguration` / `HibernateJpaAutoConfiguration` / `MybatisPlusAutoConfiguration`。
- `application.yml` (default profile) 不配置 DataSource。
- 源码总计定义 4 个测试方法（1 context + 1 health + 2 integration）。

### Runtime-verified evidence

- `mvnw.cmd clean test`：Surefire 报告 Tests run: 4, Failures: 0, Errors: 0, Skipped: 0。
- `SpikeRecordMapperIntegrationTest` 本次运行 2/2 PASS。
- `BaseMapper<SpikeRecord>` INSERT / SELECT / DELETE 在真实 MySQL 上实际完成 round-trip。
- 中文字段 `"数据库系统工程师"` + `"MyBatis-Plus 与 MySQL 中文写入验证"` 实际写入并通过 round-trip 一致性断言。
- UTF-8 HEX 字节级断言实际通过（MySQL `HEX(name)` 与 `HexFormat.formatHex(bytes(UTF-8))` 匹配）。
- Packaged JAR (`server-0.1.0-SPIKE.jar`) 使用 `--spring.profiles.active=it` 实际启动成功。
- Hikari (`AIStudyItHikari`) 实际建立 MySQL connection。
- `SpikeDatabaseStartupVerifier` 实际打印：
  `SPIKE_DB_VERIFY_OK database=aistudy_spike mysqlVersion=8.4.10 charset=utf8mb4 collation=utf8mb4_unicode_ci spikeRecordCount=0`。
- Tomcat 实际持续监听（MICRO-11C: 8081 LISTEN；MICRO-12C: 稳定）。
- `GET /health` 实际返回 HTTP 200，body 包含 `status=UP`。
- 3 个 profile 在运行时均生效：default（未建立 DataSource bean）/ test（context 加载不依赖 DB）/ it（Hikari 实际建立 MySQL connection）。

### Artifact / environment observations

- MySQL 实际版本 `8.4.10`（本次运行环境观测；代码仅断言 `startsWith("8.")`，未锁定 8.4.10）。
- Docker container `aistudy-mysql-spike`（本地开发容器，运行期存在，MICRO-13C 后 stopped；volume `aistudy_spike_data` 保留）。
- `target/server-0.1.0-SPIKE.jar` SHA-256 `FA0DEE1127568A7A3BCEC002796CA9F9168B7CCE1DE72B605B6603B91DD0A607`：MICRO-10 构建产物记录，非源码常量。

### SPIKE-002 未覆盖范围（诚实记录）

- Transaction：未在 SPIKE-002 单独验证。将在后续实际 Service / use-case 中验证。
- LearningSpace / spaceId composite-index：未在 SPIKE-002 单独验证。将在 SPIKE-004 或 Platform Skeleton 阶段进行真实验证。

## 2026-09-05 — SPIKE-003 Complete

Flyway / Versioned SQL migration 验证完成，新增 ADR-046（`Accepted`）正式采用 Flyway。

### Code-defined evidence

- `server/pom.xml` 声明 `org.flywaydb:flyway-core` 与 `org.flywaydb:flyway-mysql`（版本跟随 Spring Boot 3.5.0 BOM 管理，不显式 pin）。
- `application-flyway-it.yml` 独立 profile 使用 `FLYWAY_DB_URL / FLYWAY_DB_USERNAME / FLYWAY_DB_PASSWORD`（与 SPIKE-002 的 `DB_URL` 环境变量族完全隔离），并 `spring.flyway.enabled=false`（禁用 Spring Boot 自动 migrate，由测试代码显式驱动 Flyway Java API）。
- `application-it.yml` 新增 `spring.flyway.enabled=false`，防止 Spring Boot 在 SPIKE-002 profile 下对 `aistudy_spike` 触发 Flyway 自动迁移。
- `V001__create_flyway_spike_record.sql`：SPIKE-only 表 `flyway_spike_record`（`id BIGINT AUTO_INCREMENT PK, name VARCHAR(100), created_at DATETIME(6), utf8mb4`），标记 "SPIKE-003 ONLY / NOT A BUSINESS TABLE"。
- `V002__add_note_to_flyway_spike_record.sql`：`ALTER TABLE flyway_spike_record ADD COLUMN note VARCHAR(255) NULL`。
- `FlywayMigrationIntegrationTest`（3 tests，`@ActiveProfiles("flyway-it")`）：
  - `freshDatabaseMigratesFromEmptyToLatest`：`clean()` → `migrate()` → 2 migrations applied → history V001+V002 各 success=true → table charset utf8mb4 → note column exists and nullable。
  - `existingV001DatabaseUpgradesToV002AndPreservesData`：`clean()` → target `001` → migrate() → 1 migration → 无 note column → 插入 "V001升级前保留数据" → 从 `flyway_schema_history` **动态读取 V001 checksum** → target default-latest → migrate() → 仅 1 migration applied（V002）→ V001 checksum 与升级前动态值相等 → 旧数据仍存在 → 第二次 migrate() `migrationsExecuted == 0` → history 仍只有 V001+V002。
  - `latestDatabaseRequiresNoMigrationOnSecondMigrate`：`clean()` → migrate() → 2 applied → 再次 migrate() → `migrationsExecuted == 0` → history 版本序列 `[001, 002]`。
  - **不硬编码任何 checksum 值**，全部动态读取。
- **Destructive guard**：`assertSchemaIsFlywayTest()` 在每次 `clean()` 前通过 `SELECT DATABASE()` 精确匹配 `aistudy_flyway_test`；不等则抛 `IllegalStateException("Refusing Flyway clean: expected schema 'aistudy_flyway_test' but got '<actual>'")`。这是后续所有 DB destructive test 的强制安全规则。
- Flyway Java API 直接构造 `Flyway.configure().dataSource(...).locations("classpath:db/migration").cleanDisabled(false).load()`，`target(null)` = default latest；`target("001")` 停止在 V001。Flyway 11.7.2 不接受空字符串 target。
- `@BeforeEach` 调用 `assertSchemaIsFlywayTest() + flyway().clean()`，每个 test 自行构造起始状态；测试不依赖执行顺序或外部预置数据。

### Runtime-verified evidence

- Full `.\mvnw.cmd clean test`（SPIKE-001 + SPIKE-002 + SPIKE-003 + SpikeHealth）：**Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**。
  - `AiStudyApplicationTests` 1 PASS。
  - `FlywayMigrationIntegrationTest` 3 PASS（fresh / upgrade-preserve / idempotent）。
  - `SpikeRecordMapperIntegrationTest` 2 PASS（中文 round-trip + HEX）。
  - `SpikeHealthControllerTest` 1 PASS。
- Flyway `flyway_migration_integration_test` RUN 1（step-08）：`Tests run: 3, Failures: 0, Errors: 0`；日志显示 `Successfully applied 1 migration ... now at version v001`、`Successfully applied 1 migration ... now at version v002`、`Successfully applied 2 migrations ... now at version v002`。
- Flyway RUN 2 repeatability（step-09，同一命令再次执行）：`Tests run: 3, Failures: 0, Errors: 0`。
- 运行后 `SELECT DATABASE() FROM aistudy_flyway_test` = `aistudy_flyway_test`；`SHOW TABLES FROM aistudy_spike` = `spike_record`（唯一，无 Flyway 副作用）。
- Flyway 13.4.0 兼容实验（SPIKE-003-COMPAT-01）：`flyway-core-13.4.0.jar` 与 `flyway-mysql-13.4.0.jar` 成功从 Maven Central 下载（HTTP 200），Java 测试代码零修改即可编译，但运行时抛出 `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`（Jackson 3 API 缺失）。结论：当前 Spring Boot 3.5.0 依赖栈下零修改升级到 Flyway 13.4.0 失败。实验后 pom.xml 已恢复 BOM 管理，`mvnw.cmd clean test` 再次 7/7 PASS。

### Artifact / environment observations

- Flyway 版本：**11.7.2**（由 Spring Boot 3.5.0 BOM 解析）。
- MySQL 版本：**8.4.10**（Docker `mysql:8.4`，本地容器 `aistudy-mysql-spike`，运行期观测）。
- **兼容性 warning**（Flyway 11.7.2 每次启动均输出，原样保留）：
  ```
  Flyway upgrade recommended: MySQL 8.4 is newer than this version of Flyway
  and support has not been tested. The latest supported version of MySQL is 8.1.
  ```
  本次 SPIKE 范围内实际执行成功，但**不能**因此宣称"Flyway 11.7.2 官方支持 MySQL 8.4"。生产部署前必须重新验证 Spring Boot / Flyway / MySQL 版本组合。
- **数据库污染事故与恢复**：早期 SPIKE-003 STEP-03 测试因复用 `DB_URL` 变量，导致 Flyway `clean()` 作用到 `aistudy_spike`，删除 SPIKE-002 的 `spike_record` 表并在其中留下 `flyway_schema_history` + `flyway_spike_record`。已通过 `deploy/local/mysql/init/01_spike_record.sql` 恢复 `spike_record`，并对 `aistudy_spike` 执行 `DROP TABLE IF EXISTS flyway_spike_record; DROP TABLE IF EXISTS flyway_schema_history;`（仅该 schema）。根因修复为 SPIKE-002 / SPIKE-003 使用不同 env-var 族 + Flyway clean 前 `SELECT DATABASE()` 精确匹配 guard。
- 数据库 schema：`aistudy_spike`（SPIKE-002 用）+ `aistudy_flyway_test`（SPIKE-003 自动测试用，每次 clean 后重建）+ `aistudy_flyway_spike`（STEP-01/02 手工演示库，SPIKE 内保留但不再作为自动测试目标）。

### SPIKE-003 未覆盖范围（诚实记录）

- 生产环境部署策略（何时执行 pending migration、蓝绿/滚动发布协调）留待后续 ADR。
- Flyway 大版本升级（12.x / 13.x）留待独立 Spike。
- Undo migration（`Uxxx`）：当前不采用；`U` 前缀保留给未来 Flyway Teams / undo strategy。
- Multi-schema / out-of-order migrations：当前不使用。
- Flyway baseline（对已有历史数据库初始接入）：当前不使用，首次接入在空库上完成。

## 2026-09-05 — SPIKE-004 Complete

Auth + Space Authorization 技术验证完成。结论是 **Validation Complete**，不是生产认证/授权实现完成。

### Code-defined evidence

- `server/pom.xml` 新增 Spring Security、Nimbus JOSE/JWT 与 OAuth2 Resource Server 相关依赖，版本由 Spring Boot BOM 管理。
- `SpikeSecurityConfig`：`/health` `permitAll()`，其它请求 `authenticated()`；启用 Resource Server JWT 与 Method Security；提供 BCrypt `PasswordEncoder`、HS256 `JwtEncoder` / `JwtDecoder`。
- `SpikeJwtTokenService`：SPIKE-only Access Token，`iss=aistudy-spike`，`sub` 来自调用参数，TTL=5 minutes。
- `SpikeProtectedController`：包含 authenticated、`denyAll()`、space-scoped authorization 三类验证 endpoint。
- `SpikeSpaceAccess`：以 `Authentication.getName()` + request `spaceId` 调用 Repository；null / unauthenticated defensive checks 返回 false；Repository 异常不被吞掉。
- `SpikeSpaceMembershipRepository`：参数化 SQL 查询 `spike_space_membership` 中 `(user_subject, space_id, status='ACTIVE')`。
- V003 新增 SPIKE-only `spike_space_membership`：`id` PK、`user_subject`、`space_id`、`status`、`created_at`，并对 `(user_subject, space_id)` 加 UNIQUE；`utf8mb4 / utf8mb4_unicode_ci`。
- `SpikeSecurityBoundaryTest` 使用 `@MockitoBean` 隔离 Repository，验证 401 / 403 / JWT / Method Security / space authorization 边界。
- `SpikeSpaceMembershipIntegrationTest` 使用真实 MySQL 验证 ACTIVE / REVOKED / missing 三态 Repository 语义。
- `SpikeSpaceAuthorizationEndToEndIntegrationTest` 使用真实 JWT + MockMvc + Method Security + Repository + JdbcTemplate + MySQL 验证 `200 / 403 / 403`。
- `AiStudyApplicationTests`、`SpikeHealthControllerTest`、`SpikeJwtTokenServiceTest`、`SpikePasswordEncoderTest` 在 `test` profile 下通过 `@MockitoBean SpikeSpaceMembershipRepository` 保持无数据库测试隔离。

### Runtime-verified evidence

- MICRO-01：Spring Security baseline。
- MICRO-03：BCrypt PasswordEncoder。
- MICRO-04：JWT signing / decoding。
- MICRO-05：Bearer Resource Server，valid token `200`，invalid / expired token `401`。
- MICRO-06：Method Security + 初始 Space Authorization，认证后 deny → `403`。
- MICRO-07：V003 + real MySQL membership Repository + DB-backed Space Authorization E2E。
- MICRO-08：`test` profile context isolation 修复，不引入 fake DataSource / H2。
- SPIKE-004 focused regression：**17/17 PASS**。
- Full `.\mvnw.cmd clean test`：**Tests run: 24, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**。

### SPIKE-004 deferred / production boundary

以下原计划目标未在本 SPIKE 实现，明确 **DEFERRED**：

- Opaque Refresh Token + server-side hash。
- Refresh rotation / revoke。
- USER / ADMIN roles / authorities。

同时尚未实现正式 User、Login endpoint、正式 LearningSpace membership schema、production signing-key management、client token storage、正式 authorization model。

以下内容均为 **SPIKE-only**，后续生产实现必须重做，不能直接固化为正式方案：

- `spike_space_membership`。
- `SpikeSpaceAccess`。
- `SpikeSpaceMembershipRepository`。
- `iss=aistudy-spike`、5-minute TTL、Spring Context/JVM 生命周期随机 HS256 key。

Repository / DB 异常当前会向上传播，不会静默授权；本 SPIKE 未定义或验证“DB failure → 403”的正式 contract。

Flyway 11.7.2 + MySQL 8.4 的兼容性 warning 继续保留：SPIKE 运行成功不等于官方支持，生产部署前仍需重新验证版本组合。

## 2026-09-05 — SPIKE-005 Complete (Scope Adjusted)

OpenAPI Contract 技术验证完成。原计划中的 TypeScript client/types 生成没有伪装为已完成，而是明确 Deferred 到第一个真实 LearningSpace API 后落地。

### Code-defined evidence

- `server/pom.xml` 引入 `springdoc-openapi-starter-webmvc-api`。
- `SpikeSecurityConfig` 对 `/v3/api-docs` / `/v3/api-docs/**` 匿名放行，其它认证边界保持不变。
- `SpikeOpenApiConfig` 定义 `bearerAuth`：HTTP / bearer / JWT；未设置全局 SecurityRequirement。
- `SpikeProtectedController` 保持受保护 endpoint 的 Bearer security metadata。
- SPIKE response 从通用 `Map` 改为 typed Java record：`SpikeHealthResponse`、`SpikeStatusResponse`、`SpikeSpaceAuthorizationResponse`。
- Controller 明确 `produces = application/json`，使 OpenAPI response content 不再为 `*/*`。
- `SpikeOpenApiContractTest` 验证 OpenAPI endpoint、Bearer scheme、安全边界、typed schema 与 response media type。

### Runtime-verified evidence

- `GET http://localhost:8080/v3/api-docs`：HTTP `200`。
- `Content-Type: application/json`。
- OpenAPI version：`3.1.0`。
- Contract 包含 `/health`、`/api/v1/spike/protected`、`/api/v1/spike/method-denied`、`/api/v1/spike/spaces/{spaceId}`。
- `components.securitySchemes.bearerAuth`：`type=http`、`scheme=bearer`、`bearerFormat=JWT`。
- `/api/v1/spike/protected` 声明 `bearerAuth`；`/health` security 为 `null`。
- typed response schema 已真实生成，不再是 `additionalProperties` 通用 map。
- Focused regression：**17/17 PASS**。
- Full `./mvnw.cmd clean test`：**Tests run: 31, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**。

### Scope adjustment / deferred

以下 SPIKE-005 原计划目标尚未执行，明确 **DEFERRED**：

- TypeScript client/types 实际生成。
- Desktop / Admin 共享 `api-client` package。
- generator 选型与 generated-code check-in 策略。

调整原因：继续在 `Spike*` endpoint 上验证前端生成链路的收益已经低于成本。后续将在第一个真实 LearningSpace API 出现后，直接基于真实业务 OpenAPI Contract 落地共享 TypeScript client/types。

### Development process adjustment

SPIKE-006 ~ SPIKE-014 保留为风险检查清单，但不再作为 Platform Skeleton / 核心业务开发的串行前置门槛。后续仅在对应能力进入真实实现、且存在明确阻塞性技术不确定性时执行 just-in-time Spike。

## 当前状态

```text
Technical Baseline     COMPLETE
Business Alignment     COMPLETE
Git Baseline           COMPLETE
SPIKE-001              COMPLETE
SPIKE-002              COMPLETE
SPIKE-003              COMPLETE
SPIKE-004              COMPLETE
SPIKE-005              COMPLETE (scope adjusted)
SPIKE-006~014          DEFERRED / JUST-IN-TIME
Platform Skeleton      STARTING
Core Business          STARTING WITH LearningSpace
```

## 下一步

1. 收口并提交 / push SPIKE-005。
2. 正式开始 `TASK-001 LearningSpace Vertical Slice`。
3. 让真实 LearningSpace API 进入 OpenAPI Contract。
4. 基于真实 Contract 落地共享 TypeScript client/types。
5. 后续按业务链推进 Source → KnowledgePoint → Question / Practice → Review / Mastery / StudyPlan → Exam。

## 2026-09-05 BUSINESS-001 Closeout (LONG-RUN-001 Phase A)

### WHAT

BUSINESS-001 LearningSpace vertical slice 正式收口。生产实现 + tests + 两轮修复（FIX-01/FIX-02）全部落地。

实现内容：
- `POST /api/v1/spaces` → 201（创建，owner 来自 JWT sub）
- `GET /api/v1/spaces` → 200 typed List（owner-scoped SQL）
- `GET /api/v1/spaces/{spaceId}` → 200 / 404（不存在与非本人统一 404，防资源探测）
- V004 `learning_space` 表（BIGINT AUTO_INCREMENT / utf8mb4_unicode_ci / owner 索引）
- `com.aistudy.server.space` 包：entity / mapper / service / controller / dto（modular monolith 一致）

### WHY

第一个正式业务 vertical slice，确立 owner boundary 模式：JWT sub → owner_subject → SQL 层 WHERE owner 条件。所有后续业务（Source、KnowledgePoint…）复用该模式。

### FILES

- 新增：`V004__create_learning_space.sql`、`space/{entity,mapper,service,controller,dto}/*`（6 production + 2 test）
- 修改（test 兼容）：AiStudyApplicationTests / SpikeHealthControllerTest / SpikeJwtTokenServiceTest / SpikePasswordEncoderTest / SpikeSecurityBoundaryTest / SpikeOpenApiContractTest 各 +`@MockitoBean LearningSpaceMapper`；FlywayMigrationIntegrationTest 更新 V003→V004 断言
- 修改（FIX-01）：SpikeSecurityConfig +path-scoped CSRF ignore（`/api/v1/spaces`、`/api/v1/spaces/**`）；LearningSpaceOpenApiContractTest 修正 GET list schema 断言（type=array + items.$ref）
- 修改（FIX-02）：SpikeRecordMapperIntegrationTest +`@MockitoBean LearningSpaceMapper`（不扩大 `@MapperScan`，it profile 不接正式 mapper）

### CONTRACT

- DB：learning_space(id, name, description, owner_subject, status, created_at, updated_at)；无 User FK；无 spike_space_membership 复用
- API：/api/v1/spaces 三 endpoint，全部 bearerAuth + application/json
- SECURITY：CSRF 仅忽略 Bearer API 路径（非全局 disable）；/health、/v3/api-docs permitAll 不变；anyRequest().authenticated() 不变
- OpenAPI：typed schema（LearningSpaceResponse / CreateLearningSpaceRequest）

### TESTS

- LearningSpaceVerticalSliceIntegrationTest：8 tests（真实 MySQL，schema guard + Flyway migrate + scoped cleanup）
- LearningSpaceOpenApiContractTest：5 tests（test profile + mocks）
- 静态检查通过；Maven 未由 AI 运行

### RUNTIME EVIDENCE（用户提供）

- Focused：16 tests / 0 failures / 0 errors / BUILD SUCCESS（FIX-01 后）
- Full clean test：user reported BUILD SUCCESS after FIX-02。（未记录具体 test 总数 — 不编造数字）

### DECISIONS

- 404 统一表达“不存在/非本人”（api-guidelines §13）；不区分 403
- owner_subject 字符串 = JWT sub；不建正式 User 表 / FK
- Response 不含 ownerSubject
- 列表返回 typed List（分页 defer，不为此开基础设施）

### DEFERRED

- LearningSpace rename/delete/archive/share/membership
- 分页、ProblemDetail 错误码（SPACE_NOT_FOUND）、正式 User/Login/Refresh

### RISKS

- 无新风险。既有：HS256 JVM 随机 key 仍为 SPIKE 级（production key management defer）

### NEXT

BUSINESS-002 Source Vertical Slice（LONG-RUN-001 Phase B）

## 2026-09-05 BUSINESS-002 Source Persistence + API (LONG-RUN-001 Phase B)

### WHAT

实现 Source（SourceDocument metadata）vertical slice：

- V005 `source` 表（FK → learning_space.id）
- `com.aistudy.server.source`：entity / mapper / service / controller / dto
- `POST /api/v1/spaces/{spaceId}/sources`（201）
- `GET /api/v1/spaces/{spaceId}/sources`（200 typed List）
- `GET /api/v1/spaces/{spaceId}/sources/{sourceId}`（200 / 404）
- CSRF ignore 显式加入 Source 路径（/api/v1/spaces/*/sources、/**）
- FlywayMigrationIntegrationTest 更新到 V005
- 旧 test-profile/it-profile @SpringBootTest 补 `@MockitoBean SourceMapper`

### WHY

第二个正式 vertical slice，确立"子资源归属父空间"的授权模式：Source 不存 owner_subject，ownership 由 FK 链推导；读路径用 JOIN 把 owner 条件落进 SQL。

### FILES

- 新增：V005__create_source.sql、source/{entity, mapper, service, controller, dto}/*（5 production + 2 test：SourceVerticalSliceIntegrationTest、SourceOpenApiContractTest）
- 修改：SpikeSecurityConfig（CSRF matcher +2 条显式 Source 路径）；FlywayMigrationIntegrationTest（V004→V005）；8 个旧测试类 +@MockitoBean SourceMapper

### CONTRACT

- DB：source(id, space_id FK→learning_space.id, title, source_type, status DEFAULT 'REGISTERED', created_by_user_id, created_at, updated_at)；idx (space_id, created_at, id)
- API：三 endpoint，bearerAuth + application/json
- SECURITY：create/list 先 parent owner-scoped 校验（复用 LearningSpaceService.getMine）；get 用 JOIN（s.id + s.space_id + ls.owner_subject）三条件合一 → 统一 404
- 明确不含：owner_subject 冗余列、文件元数据列（original_filename/mime_type/size_bytes/storage_key 属 SourceAsset per data-model §5.2，本轮无 upload 无实际用途 → 不建 placeholder）

### TESTS

- SourceVerticalSliceIntegrationTest：12 tests（真实 MySQL；含跨 space IDOR 测试：owner + spaceB + sourceId(spaceA) → 404）
- SourceOpenApiContractTest：6 tests（test profile + mocks）
- FlywayMigrationIntegrationTest：3 tests 更新（fresh=5 / upgrade=4 / second=0 + source 表/列/索引/FK/charset 断言）
- 均为静态检查；Maven 未由 AI 运行

### DECISIONS

- source_type 用 data-model.md §5.1 定义值（DESKTOP_UPLOAD / DESKTOP_FOLDER_IMPORT / ADMIN_UPLOAD / ADMIN_MANUAL），@Pattern 校验
- status 用 'REGISTERED'（无 processing pipeline，不引入 PROCESSING/FAILED）
- 字段名用 docs 的 title（不是 name）
- FK 采用（docs 无禁用规则；RESTRICT 默认，无 ON DELETE CASCADE）
- 列表 typed List，分页 defer

### DEFERRED

- upload/multipart/IngestionJob/SourcePage/OCR/AI/KnowledgePoint
- SourceAsset 表、storageKey 元数据
- createdByUserId 不回显到 API response

### RISKS

- V005 FK 依赖 V004 顺序（Flyway 版本序保证）；flyway-it 重复 migrate 幂等
- SourceVerticalSliceIntegrationTest cleanup 顺序：先删 source 再删 learning_space（FK RESTRICT），已按此实现

### NEXT

Source OpenAPI contract tests 已包含；下一 checkpoint 在 Phase C（shared types）后写；最终 LONG-RUN-001 收口记录。

## 2026-09-05 Shared OpenAPI Types Foundation (LONG-RUN-001 Phase C)

### WHAT

建立 `packages/api-client` 共享包 foundation：

- package.json（private @aistudy/api-client；openapi-fetch 0.13.x + openapi-typescript 7.x + typescript 5.x）
- tsconfig.json（strict, ESNext, Bundler resolution）
- scripts/generate-api.mjs：`npm run api:generate` — 从 OPENAPI_URL（默认 http://localhost:8080/v3/api-docs）拉取真实 OpenAPI JSON → openapi-typescript → src/generated/api.d.ts；服务器不可达时非零退出，绝不伪造成功
- src/client.ts：openapi-fetch createClient<paths> 最薄 wrapper（createLearningSpace / listLearningSpaces / getLearningSpace / createSource / listSources / getSource）；TokenProvider 注入 getAccessToken()，不碰 localStorage/cookie
- src/index.ts barrel export

### WHY

SPIKE-005 明确 Deferred 的 TypeScript client 现在基于真实业务 Contract（LearningSpace + Source）落地。Desktop / Admin Web 将来共享同一 generated contract（api-guidelines.md §18）。

### FILES

packages/api-client/{package.json, package-lock.json, tsconfig.json, scripts/generate-api.mjs, src/client.ts, src/index.ts}

### CONTRACT

- 类型必须来自 /v3/api-docs 生成，禁止手写业务类型冒充
- auth 注入点：调用方提供 TokenProvider（Desktop=safeStorage 未来，Admin=自有 transport）

### TESTS / STATIC

- `npm install` 真实执行成功：added 37 packages（真实网络证据）
- generate-api.mjs `node --check` 通过；tsc 5.9.3 已安装
- `src/generated/api.d.ts` 未生成（服务器未运行）—— 明确 Deferred runtime evidence，不伪造

### DECISIONS

- generator 选型：openapi-typescript + openapi-fetch（SPIKE-005 未定 ADR，任务推荐方向；不引入 openapi-generator 大型 Java SDK）
- package manager：npm（technology-selection.md 未强制 pnpm/yarn；Windows Node 生命周期规则适用于 Desktop/Admin 构建期）

### DEFERRED

- src/generated/api.d.ts 实际生成（需用户启动 server + npm run api:generate）
- typecheck 通过（依赖生成文件存在）
- Desktop / Admin 工程接入该 package
- generated-code check-in 策略

### RISKS

- client.ts 引用 ./generated/api.js — 生成前 tsc 会报 cannot find module，这是预期状态（文档已注明生成顺序）
- openapi-typescript 7.x CLI bin 路径假设（node_modules/openapi-typescript/bin/cli.js）需在用户环境验证

### NEXT

Phase D：整体静态回归 + LONG-RUN-001 最终收口文档。

## 2026-09-05 LONG-RUN-001 Final Closeout

### 1. BUSINESS-001 Closeout

- WHAT：LearningSpace vertical slice 收口（实现 + FIX-01 + FIX-02 + 用户 runtime 验证）
- WHY：第一个正式业务 slice，确立 owner-boundary 模式
- FILES：space/{entity,mapper,service,controller,dto}、V004、SpikeSecurityConfig（CSRF ignore）、7 个旧测试兼容
- DB：learning_space(id, name, description, owner_subject, status, created_at, updated_at) + idx_learning_space_owner_subject
- API：POST/GET /api/v1/spaces、GET /api/v1/spaces/{spaceId}；typed DTO；bearerAuth；application/json
- SECURITY：SQL owner boundary（id+owner_subject）；非 owner → 404；无 User FK
- TESTS：VerticalSlice 8 + OpenApiContract 5 + Flyway 3（V004）
- STATIC EVIDENCE：git diff --check clean；token 级语法检查通过
- RUNTIME EVIDENCE：用户 focused 16/16 PASS；clean test user-reported BUILD SUCCESS（FIX-02 后）
- DEFERRED：rename/delete/archive/share/membership、分页、ProblemDetail、正式 User/Login
- RISKS：HS256 JVM key 仍 SPIKE 级；Flyway 11.7.2 vs MySQL 8.4 WARN

### 2. BUSINESS-002 Source Implementation

- WHAT：Source（SourceDocument metadata）vertical slice
- WHY：第二个正式 slice，确立"子资源归属父空间 + JOIN 防 IDOR"模式
- FILES：source/{entity,mapper,service,controller,dto}、V005、SpikeSecurityConfig（CSRF +2 显式 Source 路径）、FlywayMigrationIntegrationTest（V004→V005）、8 个旧测试 +@MockitoBean SourceMapper
- DB：source(id, space_id FK→learning_space.id, title, source_type, status DEFAULT 'REGISTERED', created_by_user_id, created_at, updated_at) + idx (space_id, created_at, id)；无 owner_subject 冗余列；无文件元数据占位列（属 SourceAsset）
- API：POST/GET /api/v1/spaces/{spaceId}/sources、GET .../{sourceId}；typed DTO
- SECURITY：create/list 先 parent owner-scoped 校验（复用 LearningSpaceService.getMine）；get 单条 JOIN（s.id+s.space_id+ls.owner_subject）→ 统一 404；跨 space IDOR 测试覆盖
- TRANSACTION：create @Transactional（parent 校验 + insert 原子）
- TESTS：SourceVerticalSlice 12（真实 MySQL，含 cross-space IDOR、anonymous GET/POST 401、blank title/invalid sourceType 400）+ SourceOpenApiContract 6 + Flyway 3
- STATIC EVIDENCE：git diff --check clean；41 个 java 文件 token 级语法检查全部通过；无 Map response、无 request DTO ownerSubject、无 csrf.disable()、无全局 /api/v1/** permitAll、无 H2
- RUNTIME EVIDENCE MISSING：BUSINESS-002 代码尚无用户 Maven 运行证据（AI 未运行 Maven）
- DEFERRED：upload/multipart/IngestionJob/SourcePage/OCR/AI/KnowledgePoint；SourceAsset 表；storageKey
- RISKS：V005 FK 依赖 V004 顺序；Source test cleanup 必须先删 source 再删 learning_space（FK RESTRICT，已实现）

### 3. Shared OpenAPI Types Foundation

- WHAT：packages/api-client（openapi-typescript + openapi-fetch + TokenProvider 注入）
- WHY：SPIKE-005 Deferred 项基于真实业务 Contract 落地
- FILES：packages/api-client/{package.json, package-lock.json, tsconfig.json, scripts/generate-api.mjs, src/client.ts, src/index.ts}
- TESTS：npm install 真实成功（37 packages）；generate-api.mjs node --check 通过；typecheck 依赖生成文件
- STATIC EVIDENCE：package.json/tsconfig lint OK
- RUNTIME EVIDENCE MISSING：src/generated/api.d.ts 未生成（server 未运行，不伪造）；typecheck 未跑（依赖生成）
- DEFERRED：generated 文件、typecheck、Desktop/Admin 接入、check-in 策略
- RISKS：client.ts 引 ./generated/api.js — 生成前 tsc cannot find module（预期，文档注明）

### 4. Overall

- git diff --check：clean
- git status：全部未暂存（用户手动 commit）
- 用户验证命令见 current-task.md Next Actions
- 未运行 Maven / npm run api:generate / git 写操作

## 当前状态

```text
SPIKE-001~005              COMPLETE
BUSINESS-001 LearningSpace IMPLEMENTED + USER RUNTIME VERIFIED
BUSINESS-002 Source        IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
Shared API Client          FOUNDATION IMPLEMENTED — AWAITING RUNTIME VERIFICATION
BUSINESS-003 KnowledgePoint NOT STARTED
```

## 下一步

1. 用户运行 focused BUSINESS-002 tests + full clean test。
2. 用户启动 server 后运行 packages/api-client 的 npm run api:generate + typecheck。
3. 用户反馈结果后修复（如有）。
4. BUSINESS-003 KnowledgePoint。

## 2026-09-05 LONG-RUN-001-FIX-TS-01 Shared Client TypeScript Contract Alignment

### WHAT

修复 shared client typecheck 唯一失败（src/client.ts:56）。

### WHY / 根因

用户真实运行：
- `npm run api:generate` → PASS（openapi-typescript 7.13.0，真实生成 src/generated/api.d.ts + openapi.json）
- `npm run typecheck` → 1 error：wrapper 手写 `description?: string | null`，而真实 generated type 是 `CreateLearningSpaceRequest.description?: string`（optional，无 null）→ body 不可赋给 openapi-fetch requestBody。

### FILES

- 修改：packages/api-client/src/client.ts
- 未修改：src/generated/api.d.ts、src/generated/openapi.json（用户生成物，未触碰）、Java 后端、package.json、generator script

### FIX

- client.ts 从 `./generated/api.js` 导入 `components` 类型
- 新增类型别名（不复制字段）：
  - `type CreateLearningSpaceRequest = components["schemas"]["CreateLearningSpaceRequest"]`
  - `type CreateSourceRequest = components["schemas"]["CreateSourceRequest"]`
- createLearningSpace(body: CreateLearningSpaceRequest)
- createSource(spaceId, body: CreateSourceRequest)
- 其它 wrapper（list/get ×2、createSource）检查：无其它手写 request/response type 重复；business path 参数 `spaceId: number` 与 generated 一致（SPIKE 的 `spaceId: string` 属无关 endpoint）

### TESTS / STATIC

- git diff --check clean
- 禁止项全部满足：无 as any、无 @ts-ignore、无类型断言、无 unknown 强转、无 generated 编辑、无 Java 修改、无版本修改
- typecheck 未由 AI 运行（用户将人工重跑）

### RUNTIME EVIDENCE MISSING

- 用户重新 `npm run typecheck` 结果（等待）

### NEXT

用户验证后视结果进入 BUSINESS-003。

## LONG-RUN-001 Runtime Verification Complete

用户已确认全部 runtime verification 成功。文档按用户反馈记录，不编造精确 test count。

### BUSINESS-001 LearningSpace

- implementation complete
- user runtime verified — BUILD SUCCESS（focused tests PASS + full clean test PASS；用户未提供精确 test count，不记录具体数字）

### BUSINESS-002 Source

- focused Maven verification PASS
- full clean Maven verification PASS
- runtime verified

### Shared API Client

- real /v3/api-docs generation PASS
- openapi-typescript generation PASS（src/generated/api.d.ts + openapi.json 已生成）
- npm typecheck PASS（FIX-TS-01 后）

### 备注

- 服务停止后再次运行 api:generate 曾因 localhost:8080 不可达出现 ECONNREFUSED —— 预期环境状态（server 未运行），不属于代码失败，不记录为 defect。

### 结论

LONG-RUN-001 = COMPLETE

```text
SPIKE-001~005              COMPLETE
BUSINESS-001 LearningSpace COMPLETE (user runtime verified)
BUSINESS-002 Source        COMPLETE (user runtime verified)
Shared OpenAPI TS Client   COMPLETE (user runtime verified)
BUSINESS-003 KnowledgePoint NOT STARTED
```

### 下一步

1. Git closeout（用户 git add / commit / push）。
2. Git baseline clean 后开始 BUSINESS-003 KnowledgePoint Vertical Slice。
