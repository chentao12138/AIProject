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
           └────────── REST / WebSocket ───────────┘
                                  │
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

推荐包结构：

```text
server/src/main/java/<base-package>/
├── common/
├── auth/
├── user/
├── space/
├── ingestion/
├── content/
├── knowledge/
├── note/
├── question/
├── practice/
├── review/
├── exam/
├── mastery/
├── plan/
├── ai/
├── search/
├── resource/
├── admin/
└── system/
```

小模块不要求机械使用 DDD 四层；复杂模块可采用：

```text
api
application
domain
infrastructure
```

规则：

- Controller 不承载核心业务规则。
- Mapper 不被其他模块任意直接调用以绕过应用服务。
- `common` 只保留真正通用能力。

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

Ingestion/AI 批量任务不使用一个超长同步 HTTP 请求。第一版可由 Spring Boot 内部异步执行并通过 Job 表查询状态，不为此提前引入 MQ。

只有可靠性/吞吐量证明需要时，再讨论消息队列。

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
