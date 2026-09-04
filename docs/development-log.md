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


## 当前状态

```text
Technical Baseline     COMPLETE
Business Alignment     COMPLETE
Git Baseline           COMPLETE
SPIKE-001              COMPLETE
SPIKE-002              COMPLETE
SPIKE-003              NEXT
Platform Skeleton      NOT STARTED
Core Business          NOT STARTED
```

## 下一步

1. SPIKE-003：Flyway / Versioned SQL。
2. 后续 Spike 按 `docs/development-plan.md` 顺序执行。
3. Platform Skeleton。
4. Vertical Slice A：Source → Knowledge。
5. 后续 Vertical Slice 和阶段。
