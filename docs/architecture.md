# 系统架构

> 状态：**FINAL TECHNICAL + BUSINESS BASELINE / Accepted**
>
> 技术 ADR Source of Truth：`decisions.md`。
>
> 业务总入口：`business-baseline.md`。

## 1. 总体目标

系统服务于个人 AI 学习工作台业务，并支持：

- Windows Desktop
- Web Admin
- Future Android
- 本地 Windows 开发
- 后续 Linux Server 部署
- MySQL
- 本地/未来服务器文件存储
- 多种 AI Provider
- 学习资料摄取、知识结构化、练习、考试和学习状态闭环

主架构：

```text
Client-Server Architecture
+
Modular Monolith Backend
+
LearningSpace-scoped Business Domain
```

## 2. 系统拓扑

```text
┌──────────────────────── Clients ────────────────────────┐
│                                                         │
│ Desktop                     Admin Web          Android   │
│ Electron + React            React + Vite       Future    │
│                                                         │
└──────────┬──────────────────────┬────────────────┬───────┘
           │                      │                │
           └───────────────────── REST ─────────────────────┘
                                  │
        （长任务用 Job 表轮询；当前没有 WebSocket / SSE / 流式端点）
                                  ▼
                      ┌────────────────────────┐
                      │ Spring Boot 3.5.x      │
                      │ Java 21                │
                      │ Modular Monolith       │
                      ├────────────────────────┤
                      │ auth / user            │
                      │ space                  │
                      │ ingestion / content    │
                      │ knowledge / note       │
                      │ question / practice    │
                      │ wrong / review         │
                      │ exam / mastery / plan  │
                      │ ai / search            │
                      │ resource / admin       │
                      │ system                 │
                      └───────────┬────────────┘
                                  │
             ┌────────────────────┼────────────────────┐
             │                    │                    │
             ▼                    ▼                    ▼
          MySQL              StorageService       AI Providers
                             Local first          SenseNova / etc.
```

## 3. Spring Boot 是唯一业务权威入口

所有核心业务规则由 Spring Boot 执行：

- Authentication / Authorization
- LearningSpace access check
- Source ingestion orchestration
- Content publishing
- Knowledge/Question invariants
- Practice / Review
- Exam rules / scoring
- Mastery update
- StudyPlan
- AI Provider access
- Storage
- Admin writes

禁止：

```text
Desktop → MySQL
Admin Web → MySQL
Android → MySQL
```

客户端也不能自行复制一套“考试判分/掌握度/空间隔离”规则作为最终事实。

## 4. Backend 模块

仍然是 **一个 Maven Project**，不是 Maven multi-module，也不是微服务。

当前实际包结构（`server/src/main/java/com/aistudy/server/`）：

```text
common/          跨模块基础设施：ProblemDetail 错误契约
auth/            账号 / JWT / refresh session
space/           LearningSpace + 作用域强制（见 §5.2）
source/          Source / SourceAsset / SourcePage / ContentBlock / Outline / Folder
ingestion/       IngestionJob / 解析 / OCR / ZIP / Revision / Issue
knowledge/       Category / Point / Point-Source / Relation
note/ question/ practice/ wrong/ exam/ mastery/ studyplan/
ai/              provider / context / prompt / learning / explain / coach / settings
search/ storage/ provenance/ operations/ config/ admin/
```

`user`/`content`/`review`/`plan`/`resource`/`system` 不是独立包：分别由
`auth`、`source.content`、`wrong`、`studyplan`、`source.asset`+`storage`、
`config`+`operations` 承担。

小模块不要求机械使用 DDD 四层；复杂模块可采用
`api / application / domain / infrastructure`。

规则：

- Controller 不承载核心业务规则。
- Mapper 不被其他模块任意直接调用以绕过应用服务。
- `common` 只保留真正通用能力。

这些规则由 `src/test/.../architecture/ModuleBoundaryTest.java` 回读源码 import
来检查（不依赖外部框架，离线可跑）。它带**只准收缩**的遗留基线：

| 度量 | 当前值 |
| --- | --- |
| Controller 直接 import Mapper | 9 个类（基线锁定） |
| Controller 使用 `JdbcTemplate` | 0（硬性） |
| 跨模块 Mapper import | ≤ 85 |
| 一级模块互相可达的环 | 1 个 12 模块大团（`ai, config, exam, knowledge, mastery, operations, practice, provenance, question, source, storage, studyplan`） |

那个 12 模块大团是**测出来的事实**，不是假设：ADR-024“未来需要时可拆”的前提
（模块边界有方向性）目前不成立。新增一个模块进大团、或新增一个
Controller→Mapper 依赖，都会让测试失败；从大团里解耦出一个模块，必须同步删掉
基线条目。

## 5. LearningSpace 作用域

### 5.1 业务作用域

用户在 Desktop 中首先进入某个 LearningSpace。

例如：

```text
数据库管理
Java 学习
```

所有默认学习行为均在当前空间内。

### 5.2 服务端授权

典型 API：

```text
/api/v1/spaces/{spaceId}/sources
/api/v1/spaces/{spaceId}/knowledge-points
/api/v1/spaces/{spaceId}/questions
/api/v1/spaces/{spaceId}/practice-sessions
/api/v1/spaces/{spaceId}/exams
```

每个 endpoint 先验证：

```text
Authenticated User
→ canAccess(spaceId)?
→ yes: continue
→ no: 403/404 according to policy
```

不能只依赖前端 CurrentSpace。

### 5.2.1 强制地板

“每个 endpoint 自己记得验证”不是机制，只是约定，在几百个端点的规模下必然漏。
因此 `/api/v1/spaces/**` 由 `space/scope/SpaceScopeInterceptor` 在进入 handler
之前统一校验：

```text
handler 解析出的 {spaceId}  →  LearningSpaceService.getMine(jwt.sub, spaceId)
                            →  null: 404 SPACE_NOT_FOUND（与不存在的空间不可区分）
```

- `spaceId` 只取 Spring 已解析的 path 变量，query/body 里的同名参数无效。
- 未认证请求不在此处判定，交给 Security 链返回 401（若这里返回 404，客户端的
  token 刷新逻辑会被误触发/失效）。
- `/api/v1/admin/**` 不受该拦截器约束：治理天然跨 owner，由 `ROLE_ADMIN` 授权，
  空间过滤改为显式 `spaceId` 谓词。
- 该地板**不取代**各模块 service 的 owner-scoped SQL：正常路径的查询条件与关系
  校验仍必须留在属主模块里（§5.3、ADR-038），地板只保证漏写不会变成越权。

### 5.3 数据关系不允许跨空间

创建 QuestionKnowledgePoint、ExamQuestion、KnowledgePointSource 等关系时，Service 必须验证双方属于同一 LearningSpace。

## 6. 内容摄取架构

```text
Desktop/Admin
   ↓ upload/manual input
SourceDocument / SourceAsset (RAW)
   ↓
IngestionJob
   ↓
ContentExtractionService
   ├─ ZIP unpack
   ├─ PDF extractor
   ├─ image OCR
   └─ text parser
   ↓
SourcePage / SourceOutlineNode / ContentBlock (EXTRACTED)
   ↓
AI/Rules
   ↓
KnowledgePoint / Question candidates (DERIVED)
   ↓
Review
   ↓
PUBLISHED
```

### 6.1 ContentExtractionService

OCR/文档解析与 AI Provider 分离。OCR 技术尚未锁死，后续用真实中文样本 Spike。

### 6.2 长任务

Ingestion/AI 批量任务不使用一个超长同步 HTTP 请求。第一版由 Spring Boot 内部异步
执行并通过 Job 表查询状态，不为此提前引入 MQ。

“内部异步”不是“随便起个线程”，它必须自己补齐 MQ 本来替你保证的那些性质：

| 要求 | 实现 |
| --- | --- |
| 有界、可观测的并发 | 专用 `ingestionWorkerExecutor`（core/max/queue 见 §runtime），拒绝即留在 `QUEUED`，不撑爆堆 |
| 不与未提交数据赛跑 | 入队发生在外层事务 **commit 之后**（`afterCommit`），worker 不会看不到刚插入的 Job 行 |
| 同一 Job 只有一个执行者 | `QUEUED → IMPORTING` 是带条件单语句 UPDATE（原子 claim），不是 select-then-update |
| 崩溃/重启不留下永久 RUNNING | 租约（`stale-lease`）+ 心跳（`heartbeat`）+ 启动时与**周期性**回收 |
| 重跑不产生重复内容 | 重抽前先删除该 asset 未定稿的 page/block；已完成 stage 由 `last_stage_status` 跳过 |
| 多写步骤的原子性 | “revision 变为可见”这一步单独成一个事务（`IngestionRevisionPublisher`） |
| 失败可诊断 | 终态与 `error_code/error_message` 同一条 UPDATE 写入，不出现“状态已改、原因丢失” |

状态词表唯一来源是 `IngestionJobService.IngestionStatus`；任何 SQL 里的状态字面量
必须与该枚举一致（历史上曾并存 `PENDING/RUNNING/SUCCEEDED` 一套死词表）。

只有可靠性/吞吐量证明需要时，再讨论消息队列；届时上述契约整体迁移到 MQ 语义。

## 7. Storage

```text
Source / Resource Module
       ↓
StorageService
       ├─ LocalStorageService
       └─ Future ObjectStorageService
```

本地：

```text
D:\AIStudyData\resources
```

Linux：

```text
/data/ai-study/resources
```

DB 只保存 `storageKey`。

RAW、派生文件可以在 storageKey 命名空间中区分，但物理目录结构属于 Storage implementation，不让业务层拼路径。

## 8. Desktop 架构

Electron 是客户端和本地文件入口。

```text
Renderer (React)
   │
   ├── HTTP API → Spring Boot
   │
   └── typed contextBridge
           ↓
        Preload
           ↓ allowlisted IPC
        Main Process
           ├─ file/folder dialog
           ├─ safeStorage
           ├─ notification/tray
           └─ controlled OS integration
```

导入本地文件时：

1. Renderer 请求 Main 打开受控 file/folder dialog。
2. Main 返回用户选择结果的安全句柄/路径信息给受控上传逻辑。
3. Desktop 读取文件并上传 bytes/stream。
4. Backend 不依赖客户端本地路径。

Electron 安全基线遵守 ADR-037。

## 9. Admin Web

Admin Web 是内容治理后台：

- User / Role
- LearningSpace
- Source / Ingestion
- OCR/Page/Outline Review
- KnowledgePoint
- Question
- Exam
- AI Job / Usage
- System Config

所有写操作经过 Spring Boot。

## 10. AI 架构

```text
Business Module
   ↓
AI Application Service
   ↓
AI Provider Port
   ├─ SenseNova
   ├─ OpenAI-compatible
   ├─ Ollama
   ├─ LM Studio
   └─ Future
```

当前端口只有一个实现（`ai/provider/OpenAiCompatibleAiProvider`）；SenseNova /
Ollama / LM Studio 通过各自的 OpenAI 兼容端点接入，BYOK 配置与密钥见
`ai/settings` 与 `runtime-configuration.md`。新增非兼容协议时才新增 provider 实现。

### 10.1 Retrieval Scope

资料型 AI 请求必须显式携带服务端已验证的 `spaceId`，检索层不得跨空间。

### 10.2 Grounding

AI response 可返回：

```text
answer
citations[]
provider/model metadata
```

citation 指向 ContentBlock/SourcePage。

### 10.3 AI 不是 RAW

AI 总结/知识点/题目属于 DERIVED。重新换模型不修改 RAW。

## 11. Question / Practice / Exam

Question 是共享内容资源；Practice 与 Exam 使用不同 Session/Attempt 模型。

```text
Question
  ├─ PracticeSessionQuestion → PracticeAnswer
  └─ ExamQuestion snapshot   → ExamAnswer
```

ExamQuestion 必须保存历史快照。

考试中 Backend 根据 ExamAttempt 状态限制答案/AI 辅导能力。

## 12. Mastery / Review / Plan

```text
PracticeAnswer ─┐
ReviewRecord ───┼─> MasteryService ─> Mastery(current)
ExamDiagnosis ──┘                       │
                                       ├─> ReviewPolicy
                                       └─> StudyPlanService
```

Mastery 当前只保存 current state；历史事实来自 answer/review/exam 表。

第一版算法保持可解释，不锁死 SM-2 或 AI 黑盒评分。

## 13. Search

Search 必须以 LearningSpace 为过滤条件。

第一版优先 MySQL 能力验证；不提前引入独立搜索集群。

业务调用通过 SearchService 边界，未来如需更换实现不会让 Controller/业务代码直接依赖某搜索产品。

## 14. Persistence

```text
Spring Boot
→ Application/Domain
→ Mapper
→ MyBatis-Plus / explicit SQL
→ MySQL
```

复杂统计/诊断 SQL 允许使用明确 Mapper XML/SQL。

Schema 使用版本化 `Vxxx__*.sql`。不自研 migration engine；第一个不可随意删除的持久数据库前正式接入 migration executor，首选 Flyway。

## 15. Auth

- Access Token：短期 JWT。
- Refresh：opaque random token + MySQL hash + rotation/revoke。
- Desktop Refresh：safeStorage。
- Admin Refresh：HttpOnly + Secure + SameSite Cookie。
- Android Future：Keystore。

## 16. 本地与服务器运行

### Local

```text
Desktop ─┐
Admin ───┼→ http://127.0.0.1:8080 → Spring Boot → local MySQL
         │                            └→ D:\AIStudyData\resources
```

### Future Server

```text
Desktop / Android / Admin
       ↓ HTTPS
Nginx
       ↓
Spring Boot
   ├→ MySQL
   └→ /data/ai-study/resources
```

同一 API 模型，不因迁服务器重写业务。

### 16.1 实例数约束（当前架构事实）

**同一时刻只能有一个后端实例。** 这不是部署偏好，而是三件事共同决定的：

```text
StorageService 只有本地磁盘实现        → 多实例必须有共享卷，否则对象互不可见
摄取/AI worker 是进程内线程 + DB claim  → 多实例可并存，但共享池/队列语义并未按多实例设计
不引入 Redis                            → 没有跨实例的限流/锁/会话共享
```

因此以下动作在扩容前必须先有 ADR：

- 需要多实例并发 → 先解决 Storage 对象存储（§7）与 worker 的多实例公平调度；
- 需要限流/配额 → 目前依赖反向代理（`deployment.md` Rate limiting），后端不实现；
- readiness 依赖 storage root 可写，多实例共享卷时该探针无法区分“只有我活着”。

单实例下 30s 优雅停机会切断长摄取任务，这是被接受的行为，代价由 §6.2 的租约回收
与幂等重抽兜住。

## 17. 当前不引入

- 微服务
- Redis（当前 Auth 不需要）
- MQ（当前内部 async job 先验证）
- Elasticsearch/OpenSearch
- Graph DB
- 对象存储
- 多租户
- Android 工程

只有真实瓶颈/需求出现后新增 ADR。
