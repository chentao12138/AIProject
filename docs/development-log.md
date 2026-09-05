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

## 当前状态

```text
Technical Baseline     COMPLETE
Business Alignment     COMPLETE
Git Baseline           COMPLETE
SPIKE-001              COMPLETE
SPIKE-002              COMPLETE
SPIKE-003              COMPLETE
SPIKE-004              COMPLETE
SPIKE-005              NEXT
Platform Skeleton      NOT STARTED
Core Business          NOT STARTED
```

## 下一步

1. SPIKE-005：OpenAPI → TypeScript Client（按 `docs/development-plan.md` 顺序）。
2. 后续 Spike 按原顺序执行。
3. Platform Skeleton。
4. Vertical Slice A：Source → Knowledge。
5. 后续 Vertical Slice 和阶段。
