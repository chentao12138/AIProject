# 技术选型

> 状态：**FINAL TECHNICAL BASELINE / Accepted**
>
> 业务已经完成 BUSINESS-ALIGNMENT。本文只记录技术栈和仍需 Spike 的实现技术，不重复业务设计。

## 1. 技术栈总览

| 层 | 当前选型 | 状态 |
|---|---|---|
| Backend | Java 21 + Spring Boot 3.5.x | Accepted |
| Build | Maven Wrapper 3.9.x；Windows 全局 Maven 3.6.2 保留 | Accepted |
| Persistence | MyBatis-Plus + MySQL 8.x | Accepted |
| Auth | Spring Security + JWT Access + Opaque Refresh | Accepted |
| API Contract | springdoc-openapi / OpenAPI | Accepted |
| Desktop | Electron + React + TypeScript + Vite | Accepted |
| Admin Web | React + TypeScript + Vite | Accepted |
| Android | 未锁定 | Future |
| File Storage | StorageService + LocalStorageService | Accepted |
| AI | Provider Abstraction | Accepted |
| Document Extraction/OCR | `ContentExtractionService` abstraction，具体引擎待 Spike | Gate |
| Search | SearchService；MySQL-first 验证 | Gate |
| DB Migration Executor | 真实持久数据前优先 Flyway | Gate |

## 2. Java / Spring Boot

固定 Java 21 + Spring Boot 3.5.x。

原因：

- Java 21 LTS 已在 Windows 可用。
- Spring Boot 是 Desktop/Admin/Android 的统一业务服务端。
- 业务规则、空间隔离、考试判分、Mastery 等必须集中服务端。
- 当前不为了追新迁 Spring Boot 4。

## 3. Maven

Windows 全局 Maven 3.6.2 保留。

`server/` 使用 Maven Wrapper 固定 Maven 3.9.x：

```text
server/
├── mvnw
├── mvnw.cmd
└── .mvn/wrapper/
```

Windows 构建：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

Wrapper 必须进入 Git。

## 4. MySQL + MyBatis-Plus

正式 Persistence：

```text
Spring Boot
→ Service/Domain
→ Mapper
→ MyBatis-Plus / explicit SQL
→ MySQL 8.x
```

规则：

```text
CRUD               → BaseMapper / simple service
简单查询           → LambdaQueryWrapper
复杂 JOIN/统计     → Mapper XML / 明确 SQL
```

不采用当前正式方案：JPA/Hibernate、SQLite、Prisma、better-sqlite3。

所有高频业务查询必须考虑 `spaceId` 索引前缀/组合索引。

## 5. Schema Versioning

数据库变更保留：

```text
V001__init.sql
V002__...
```

不自研 migration runner。

第一套不可随意删除的真实业务数据库投入使用之前，必须正式验证/接入 migration executor，首选 Flyway，并新增 ADR。

## 6. Spring Security

- Access Token：短期 JWT。
- Refresh Token：opaque random token。
- MySQL 保存 hash。
- rotation / revoke / expiry。
- 第一版不因 Auth 引入 Redis。
- Admin API 服务端验证 ADMIN。
- LearningSpace API 服务端验证 space access。

## 7. OpenAPI

springdoc-openapi 生成统一 Contract：

```text
Spring Boot
├→ Desktop TS client
├→ Admin TS client
└→ Future Android
```

默认 `/v3/api-docs`。

## 8. Electron

Electron + React + TypeScript + Vite。

职责：

- UI
- 文件/文件夹选择
- safeStorage
- 系统通知/托盘等受控 OS 能力
- 上传本地文件到 Spring Boot

不承担：

- MySQL
- 核心业务规则
- AI Provider server key
- database migration

遵守 ADR-037 安全边界。

## 9. Admin Web

React + TypeScript + Vite。

环境变量使用：

```text
VITE_API_BASE_URL
```

Admin 是内容治理后台，但仍是 API Client，不直接访问数据库和文件系统。

## 10. Storage

第一版：

```text
StorageService
└── LocalStorageService
```

Local root：

```text
D:\AIStudyData\resources
```

Future Linux：

```text
/data/ai-study/resources
```

业务 DB 只保存 storageKey。

## 11. Content Extraction / OCR

业务已经确认图片教材、PDF、ZIP 是核心数据来源，因此 Extraction 是 P0 技术能力。

但当前不锁死具体 OCR 产品。

统一边界：

```text
ContentExtractionService
├── ZipManifestExtractor
├── PdfExtractor
├── ImageOcrExtractor
└── TextExtractor
```

Spike 必须用真实中文样本《数据库系统工程师教程》验证：

- 中文 OCR 准确率
- 标题/页码识别
- 表格/公式可接受度
- 性能
- 本地运行成本
- 与 AI Vision 的互补方式

可比较本地 OCR 与 AI Vision，但不能把“AI 解释页内容”直接保存成 OCR 原文。

## 12. AI Provider

统一 Provider abstraction，至少允许：

- SenseNova
- OpenAI-compatible
- Ollama
- LM Studio
- Future

业务 AI 还必须遵守：

- LearningSpace scope
- citation/provenance
- AI derived draft review
- exam-state restrictions

## 13. Search

业务已经确认需要在学习空间内查资料/知识，但最终搜索技术仍需真实数据验证。

顺序：

1. MySQL 普通索引/LIKE。
2. MySQL FULLTEXT / ngram 实测中文。
3. 只有明显不满足后再选 Meilisearch/OpenSearch/Elasticsearch 等。

业务通过 SearchService，不把搜索产品 API 散落在业务模块。

## 14. Asynchronous Jobs

Ingestion/AI 是长任务，但第一版先使用 Spring Boot 内部异步执行 + MySQL Job 状态表。

不因为存在异步任务就立即引入 Kafka/RabbitMQ。

只有出现可靠投递、跨进程 worker、吞吐扩展等真实需求时再新增 ADR。

## 15. 测试

Backend：

- JUnit 5
- Spring Boot Test
- Mockito when useful
- MySQL Integration Test：Testcontainers 或独立 test DB

Frontend：

- Vitest
- React Testing Library
- Playwright 关键 E2E

特殊集成测试：

- LearningSpace 隔离
- Source upload/security
- Exam answer leakage
- Auth refresh rotation
- storage path traversal

测试禁止连接真实业务数据库。

## 16. Windows / WSL

- Hermes 在 WSL。
- Desktop/Admin npm 生命周期统一 Windows Node。
- 不混用 Windows/WSL node_modules。
- Java/Maven 通过 Windows toolchain 执行。
- server 正式构建优先 `mvnw.cmd`。
- Docker Desktop 可从 Windows CLI 调用。

## 17. 当前不引入

- Spring Cloud / 微服务
- Redis
- Kafka/RabbitMQ
- Elasticsearch/OpenSearch from day one
- Kubernetes
- Graph DB
- Object Storage
- Multi-tenant framework
- 自研 migration engine
