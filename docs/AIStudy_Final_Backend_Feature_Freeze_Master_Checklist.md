# AIStudy 最终后端全功能开发与冻结总清单

> 文档用途：**最终后端全功能总任务书 / Final Feature Freeze Master Checklist**  
> 基线：以当前最新 `main`（包含 BUSINESS-001~026、AI-001~009、FE-002B 合并，以及 `c8714ac fix: expose AI endpoints in api client`）为起点。  
> 目标：把当前 Accepted 产品规划中**所有仍需要后端代码支持的确定能力一次性实现并验证完成**。本清单完成后，AIStudy 后端进入**功能冻结**；后续默认只允许缺陷修复、安全修复、兼容性修复、测试修复和必要运维修复，**不再保留任何普通产品功能开发 backlog**。  
> 最终状态名称：`AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN`

---

## 0. 最终范围冻结规则

### 0.1 优先级标签不再决定本轮范围

项目 Accepted 文档历史上使用过 `P0 / P1 / P2(Future)`。这些标签继续保留在原始需求文档中作为历史优先级语义，但**从本总任务书开始，不再作为“这次做 / 以后做”的划分依据**。

本轮的执行规则只有三种状态：

```text
ALREADY COMPLETE        已完成，不重复开发，但最终回归必须覆盖
IMPLEMENT NOW           当前尚缺、且最终产品需要的后端能力，本轮全部实现
PERMANENTLY OUT OF SCOPE 当前最终产品明确不包含，不形成 future backend backlog
```

不存在第四种状态：

```text
P1 later
P2 later
V2 backend later
TODO after freeze
先让前端绕过去
以后再补 endpoint/table/job/migration
```

如果原始 Accepted 文档中的某个历史 `P1` 能力仍会影响最终 Desktop/Admin 产品后端，则本轮直接归入 `IMPLEMENT NOW`。历史 `P2/Future` 项则必须逐项明确：要么本轮实现，要么明确永久不属于最终后端范围。

### 0.2 本轮一次性完成的范围

本轮不是“再做一个 V1 小迭代”，而是最终后端功能收口。范围包括：

- 当前代码中尚未完整实现的核心业务能力；
- Accepted `requirements.md / feature-map.md / business-baseline.md / data-model.md / content-ingestion.md / learning-engine.md / ai-architecture.md` 中所有仍需要后端代码支持的确定能力；
- 历史 P1 中仍属于正式产品的 DOCX、文件夹增量导入、KnowledgePointRelation、完整 Review scheduling、ExamBlueprint、Mastery calibration、全文 Search、advanced statistics、source version compare、bulk admin、richer content 等；
- Accepted 文档中虽标为 optional/deferred，但如果最终产品闭环需要，且否则未来必然需要新增后端代码的能力，例如 AIGenerationJob、主观题最终评分闭环、AIUsageRecord、System Config；
- OpenAPI、shared api-client、错误码、任务恢复、版本化、运维、安全和最终真实验收；
- 真实教材、真实中文 OCR、真实 StepFun env/BYOK、Docker、数据库升级路径、双 LearningSpace 隔离、secret audit。

完成本清单后，不允许再留下普通业务意义上的“以后补后端”。

### 0.3 历史优先级 / Deferred 项目的最终处置表

| 历史规划项 | 最终处置 | 本清单位置 / 说明 |
|---|---|---|
| DOCX | `IMPLEMENT NOW` | §3.8 |
| Desktop folder import / incremental sync backend | `IMPLEMENT NOW` | §3.9；本地目录 watcher 属于 Desktop，本轮后端提供完整可重复 sync 协议 |
| KnowledgePointRelation | `IMPLEMENT NOW` | §4.6 |
| Note format | `IMPLEMENT NOW` | §4.1；后端格式中立，不把 Markdown 写死为唯一业务格式 |
| more advanced Question types | `IMPLEMENT NOW` | §5.1；本清单冻结一组最终结构化题型，避免未来再扩 schema/handler |
| AI variant question workflow | `IMPLEMENT NOW` | §5.8 |
| mature Review scheduling | `IMPLEMENT NOW` | §5.7；算法可参数化，但 API/schema 本轮封口 |
| ExamBlueprint | `IMPLEMENT NOW` | §6.3 |
| 主观题人工 / AI 辅助评分最终合同 | `IMPLEMENT NOW` | §6.5 |
| Mastery algorithm version / calibration / recompute | `IMPLEMENT NOW` | §7.2~7.3；以后如调参应以配置/数据完成，不再新增 schema/API |
| unified / Chinese full-text Search | `IMPLEMENT NOW` | §4.7~4.8 |
| advanced statistics | `IMPLEMENT NOW` | §6.9 |
| source version compare | `IMPLEMENT NOW` | §3.16 |
| richer ContentBlock backend contract | `IMPLEMENT NOW` | §3.13 |
| bulk admin operations | `IMPLEMENT NOW` | §9.7 |
| System Config | `IMPLEMENT NOW` | §9.8；只允许白名单业务配置，不做任意 env/secret 编辑器 |
| AIGenerationJob（原 conceptual optional） | `IMPLEMENT NOW` | §8.6 |
| AIUsageRecord | `IMPLEMENT NOW` | §8.1 |
| Extraction revision/versioning | `IMPLEMENT NOW` | §3.15；避免重新 OCR/解析破坏 provenance |
| chunk/resumable upload | `PERMANENTLY OUT OF SCOPE` | 当前最终产品以 bounded multipart 为正式合同；不留“文件大了以后再做”的 backend TODO |
| MasteryEvent 独立事件流 | `PERMANENTLY OUT OF SCOPE` | Practice/Exam/Review 等事实表即最终证据历史；不再另建 event sourcing |
| Manual Mastery override | `PERMANENTLY OUT OF SCOPE` | 当前最终产品不提供人工直接改 mastery 分数 |
| 旧 `.doc` 格式 | `PERMANENTLY OUT OF SCOPE` | 只支持 `.docx` |
| 完整音视频解析 | `PERMANENTLY OUT OF SCOPE` | 当前最终产品资料类型不包含完整音视频 ingestion |

> 注：原始文档没有正式 `P3` 定义；Accepted `requirements.md` 定义的是 `P0 / P1 / P2(Future)`。本清单已经把这些历史优先级全部归一到上表的最终处置语义。

### 0.4 永久不属于最终后端产品范围

以下能力明确不形成后续 backend backlog：

- Android 客户端本身，以及 Android 专用后端接口；现有通用 API 可被未来客户端复用，但本轮冻结后不计划新增 Android-specific backend；
- 手机拍照专用 ingestion 流程；普通 JPG/PNG/WebP 上传合同已经覆盖图片内容；
- 公共/共享 LearningSpace、membership；
- 企业组织、多租户；
- 社交、社区、公开内容市场、支付、复杂商业化推荐；
- 多人实时协作；
- offline sync / 多副本冲突合并；
- ObjectStorageService / 强制云对象存储；最终后端冻结在 `StorageService + LocalStorageService` 合同；
- chunk/resumable upload；
- 完整音视频解析；
- MasteryEvent event-sourcing；
- Manual Mastery override；
- 微服务拆分；
- Redis（无当前证据需要）；
- Kafka/RabbitMQ 等 MQ（DB-backed bounded jobs 为最终当前架构）；
- Elasticsearch/OpenSearch/Meilisearch；本轮完成 MySQL 中文搜索并作为最终当前搜索实现；
- Graph DB；
- 自研数据库 migration framework；
- AI 自动发布正式 KnowledgePoint / Question；
- autonomous agent / multi-agent / 无审核自治写入；
- 客户端直连数据库、后端文件系统业务路径或第三方 AI Provider；
- 旧 `.doc`。

这些不是“以后再做”的遗留任务。只有用户将来**主动改变产品边界并重新立项**时，才会成为全新项目范围；不能把它们解释为本次漏做。

### 0.5 已完成基线不重复开发

以下能力以当前 `main` 基线为已完成能力，开发人员只在最终回归/集成中验证，不重新设计：

- BUSINESS-001~026 已完成部分；
- AI-001~009；
- Auth / JWT / Refresh / RBAC 基础；
- SourceAsset 基础上传、TXT/Markdown/PDF/Image 已有基础链路；
- Search 当前基础实现；
- Question / Practice / WrongQuestion / Review / Exam / Mastery / StudyPlan 已有基础模型与主链；
- AI Tutor / explanation / coach / runtime settings / BYOK；
- Actuator / metrics / storage hardening / Docker 基础；
- `c8714ac fix: expose AI endpoints in api client` 已补的 AI Settings + Tutor 9 个 wrapper。

若本清单要求扩展这些能力，只补缺口，不做无必要重写。

---

# 1. 开发执行纪律

## 1.1 Git 与工作树

- 开发人员不得自行执行 `git add / commit / push / reset / restore / stash / checkout / clean`。
- 用户负责最终 Git 写操作。
- 不得通过 reset/restore 清掉现有工作树内容。
- 开发期间必须保持 `docs/current-task.md` 为可恢复的滚动任务胶囊；`docs/development-log.md` 只追加。

## 1.2 数据库迁移

- 现有 `V001 ~ V030` **历史 migration 禁止修改**。
- 所有新 schema 统一从 `V031__...` 顺序追加。
- 对已有数据的新约束采用：

```text
add nullable/default
→ backfill
→ validate
→ constraint/index
```

- 不允许通过“清库即可”掩盖升级路径问题。

## 1.3 开发与测试分离

本轮按用户要求：**先完成全部功能代码，再统一进入测试阶段**。

实现阶段：

- 可以做编译级静态检查；
- 可以做必要的依赖/CLI 最小 smoke（例如 OCR 引擎是否能启动）；
- 不进行每个小点一轮完整 Maven 测试的循环。

只有第 13 大点开始，才进入集中自动化与真实验收。

## 1.4 安全边界继续保持

所有 user/space scoped API：

- user identity 只取 JWT/Authentication；
- 不信任 JSON/query 中的 userId；
- foreign user/space resource 优先 404 anti-probing；
- ADMIN API 使用明确 ADMIN service/query path，不伪装成 owner；
- AI Key / JWT secret / DB password 不进 Git、响应、日志或前端 bundle。

## 1.5 技术栈总约束

继续沿用：

- Java 21；
- Spring Boot 3.5.x；
- Spring Security；
- MyBatis-Plus / 明确 Mapper XML；
- MySQL 8.x；
- Flyway；
- springdoc-openapi；
- `StorageService` 抽象 + LocalStorage；
- JDK HttpClient AI Provider abstraction；
- React/TypeScript 客户端统一消费 `packages/api-client`。

没有明确必要性，不引入新的大型基础设施。

---

# 2. 账号、LearningSpace 与核心资源生命周期

> 目的：把当前“能创建/读取”的核心资源补成真正可长期维护的业务对象。

## 2.1 LearningSpace 生命周期完整化

### 功能

补齐：

- rename；
- description update；
- archive；
- restore（如果归档后仍允许恢复）；
- archived space 的写入限制；
- list 时可选择是否包含 archived。

### 技术要求

- owner-scoped query；
- `updatedAt / archivedAt`；
- 不物理删除历史学习证据；
- Space 状态必须被 Source / Practice / Exam / AI 等写操作统一检查。

### 完成效果

用户可完整管理空间，不需要数据库手工修改。

### 进入下一点临界条件

- 生命周期状态只有一个权威 Service；
- foreign owner 返回 404；
- archived space 的允许/禁止操作规则已经固定，不再由各 Controller 自己判断。

---

## 2.2 Admin 用户管理最终闭环

### 功能

在已有 list/detail/status/roles 基础上补齐：

- Admin 创建 USER；
- 设置初始/临时密码；
- Admin 重置密码；
- 用户修改自己的密码；
- 禁用账号后的 refresh session revoke；
- 密码修改后的 refresh session 策略明确（建议全部 revoke）。

### 技术要求

- BCrypt；
- 不返回 password hash；
- last active ADMIN protection 保持；
- refresh token family/reuse 逻辑不得破坏。

### 完成效果

系统不再依赖 DB 手工插入普通用户。

### 临界条件

用户生命周期可以完全通过正式 API 管理。

---

## 2.3 SourceDocument 生命周期

### 功能

补齐：

- update title/metadata；
- archive；
- restore；
- reject；
- review/publish 状态；
- sourceType 完整支持：`DESKTOP_UPLOAD / DESKTOP_FOLDER_IMPORT / ADMIN_UPLOAD / ADMIN_MANUAL`。

最终建议状态集合：

```text
DRAFT
PROCESSING
NEEDS_REVIEW
PUBLISHED
ARCHIVED
REJECTED
```

### 完成效果

Source 不再长期停留在只有注册语义的状态。

### 临界条件

Source 状态与 IngestionJob 状态含义分离：Job 表示处理过程，Source 表示资料业务生命周期。

---

## 2.4 ADMIN_MANUAL Source

### 功能

Admin 可创建手工原始资料：

- title；
-正文；
- 可选章节/分类提示；
- 来源标识 `ADMIN_MANUAL`。

手工正文仍进入统一：

```text
Source
→ SourcePage/ContentBlock
→ Review/Publish
→ Knowledge / Question / Search / AI
```

### 禁止

不得再建一套绕开 Source/ContentBlock 的“管理员文本”旁路表。

### 临界条件

手工 Source 与上传 Source 能被后续同一套 provenance、Knowledge、Question、AI 流程消费。

---

## 2.5 RAW 文件读取 / 原文查看

### 功能

提供经过授权的 RAW stream API：

- PDF；
- ZIP（必要时下载）；
- PNG/JPEG/WebP；
- TXT/Markdown；
- DOCX（本清单后续实现）。

建议能力：

- 正确 `Content-Type`；
- safe `Content-Disposition`；
- PDF/大文件支持 HTTP Range；
- 不暴露 `storageKey` 作为真实文件路径；
- 不允许客户端任意 key 读取 StorageService。

### 特殊技术

Spring `ResourceRegion` 或等价安全 Range 实现。

### 完成效果

UI 的“查看原图/原 PDF/来源”可以走正式 API。

### 临界条件

foreign space/source/asset 永远不可读取；Range 与普通 GET 均通过同一授权链。

---

## 2.6 KnowledgeCategory 生命周期

### 功能

补齐：

- rename；
- description（如最终模型保留）；
- re-parent；
- reorder；
- archive/soft-delete 策略；
- child/category/KP 已存在时的删除规则。

### 约束

- 防 parent cycle；
- parent 必须 same space；
- 删除不能使已有 KnowledgePoint 静默丢失。

### 临界条件

分类树可长期维护且无循环。

---

## 2.7 KnowledgePoint 生命周期

### 功能

补齐：

- edit title/summary/content/difficulty/category；
- DRAFT / NEEDS_REVIEW / PUBLISHED / ARCHIVED；
- archive/restore；
- originType：`SOURCE_DERIVED / ADMIN_CURATED / AI_DERIVED / USER_CURATED`；
- AI/自动派生默认不直接 PUBLISHED。

### 完成效果

KnowledgePoint 具备真正的内容治理生命周期。

### 临界条件

已发布内容不能被后台 AI job 静默覆盖；编辑策略与历史来源关系稳定。

---

## 2.8 Question 生命周期

### 功能

在现有 create/publish 基础上补齐：

- DRAFT edit；
- NEEDS_REVIEW；
- PUBLISHED；
- ARCHIVED/downline；
- restore（如产品需要）；
- originType 完整支持；
- 编辑 option/answer/explanation/difficulty/KP/source。

### 关键约束

历史 Practice/Exam snapshot 不受后续题目编辑影响。

### 临界条件

题库可在不破坏历史证据的情况下维护。

---

## 2.9 Exam 定义生命周期

### 功能

- DRAFT edit；
- PUBLISHED；
- ARCHIVED；
- title/type/description/time/score/paper composition 修改仅限允许状态；
- 已发布 paper/version immutable。

### 临界条件

Exam 管理不再只能 create/publish，且历史 paper 不受 definition 修改影响。

---

# 3. Ingestion / Extraction 最终完整闭环

> 这是本轮最大的后端工作块。完成后，ZIP/PDF/图片/文本/DOCX/文件夹都必须进入统一、可重试、可审核、可版本化的资料链路。

## 3.1 真正异步 IngestionJob

### 当前缺口

长处理不能继续在创建 HTTP 请求线程中同步跑完整解析。

### 功能

实现真正 DB-backed 异步处理：

```text
QUEUED
→ IMPORTING
→ EXTRACTING
→ STRUCTURING
→ AI_PROCESSING（需要时）
→ NEEDS_REVIEW
→ PUBLISHED
```

失败允许：

```text
PARTIAL_FAILED
FAILED
```

### 技术建议

- Spring `ThreadPoolTaskExecutor`；
- MySQL 为任务状态真相来源；
- 原子 claim（行锁 / compare-and-set status）；
- worker 数量和 queue 有上限；
- provider/OCR/文件 I/O 不放在长数据库事务里；
- 应用重启后恢复遗留 QUEUED/RUNNING job；
- graceful shutdown。

不引入 MQ。

### 完成效果

`POST ingestion-job` 快速返回，客户端随后轮询状态；即使 HTTP 已结束，任务仍继续。

### 临界条件

请求线程不再承担完整 OCR/结构化流程；同一 job 不可能被两个 worker 同时执行。

---

## 3.2 Stage Retry

### 功能

允许从现有 RAW 重新执行指定阶段：

- IMPORT；
- EXTRACT/OCR；
- STRUCTURE；
- AI_PROCESSING（如适用）。

### 要求

- 不要求用户重新上传 RAW；
- retryCount / previous error 可追踪；
- retry 幂等；
- 不产生重复 published 派生数据。

### 临界条件

OCR 失败后可以只重跑 OCR，而不是重建所有原始资料。

---

## 3.3 ZIP 实际导入

### 功能

从“只安全检查 ZIP”升级到：

```text
RAW ZIP
→ safe manifest
→ entries
→ SourceAsset
→ SourcePage / extractor
```

保留：

- zip-slip/path traversal 防护；
- entry 数量上限；
- 单 entry/总解压大小；
- compression ratio 防 zip bomb；
- nested ZIP 策略（明确禁止或 bounded）；
- `originalRelativePath`；
- 原 ZIP 永久保留。

### 完成效果

真实教材 ZIP 可以继续走 OCR/排序/结构化。

### 临界条件

ZIP 内 PDF/Image/TXT/MD/DOCX 都能分派到正确 extractor。

---

## 3.4 PNG/JPEG/WebP 图片支持

### 功能

当前 PNG/JPEG 基础上补 WebP：

- MIME + magic validation；
- width/height/pixels 限制；
- metadata decode；
- corruption error code。

### 特殊技术栈

JDK ImageIO 默认 WebP 能力有限；使用维护中的 ImageIO-compatible WebP plugin，经一次最小兼容性验证后固定依赖版本。

### 临界条件

合法 WebP 可进入 SourcePage；损坏/伪 MIME WebP 被稳定拒绝。

---

## 3.5 中文 OCR

### 功能

必须支持：

- JPG/PNG/WebP OCR；
- image-only PDF page OCR；
- 中文教材；
- confidence；
- timeout；
- max pixels/page；
- safe temp files；
- OCR failure stable error code。

### 推荐技术栈

抽象：

```text
OcrEngine
```

优先候选：

```text
PaddleOCR / PaddlePaddle
```

建议 Spring Boot 通过**受控本地进程 adapter 或独立 OCR sidecar**调用，不把 Python 解释器逻辑散落到 Java Service。

### 安全

- 固定 executable；
- 参数数组调用，不拼 shell string；
- stdout/stderr 有大小上限；
- timeout + process kill；
- server-controlled temp directory；
- client filename 不参与命令/真实路径决定。

### 数据语义

OCR 原文 = extracted fact；AI summary/structure = derived。两者不得混为一个字段。

### 临界条件

使用真实中文教材图片能够得到可用 OCR 文本和 confidence。

---

## 3.6 PDF 混合提取

### 功能

在现有 PDF text extraction 上补齐：

- 每页先检测 text layer；
- 有可靠 text layer → PDF text；
- 无/过少 text → rasterize page → OCR；
- 混合型 PDF 可以按页选择；
- encrypted/page/text/size limit 继续生效。

### 技术

继续 PDFBox 3.x；页面 rasterization 使用 PDFBox renderer；OCR 通过 `OcrEngine`。

### 临界条件

text PDF 与扫描 PDF 都能形成 SourcePage + extractedText。

---

## 3.7 Markdown/TXT 最终 parser

### 功能

当前 parser 基础上补齐：

- UTF-8/可控编码策略；
- heading/list/code block 等结构；
- 长文本分块上限；
- locator；
- 不把 Markdown 强行作为 Note 唯一格式。

### 临界条件

Markdown 能产出至少 heading/paragraph/list/code 等可追溯 ContentBlock。

---

## 3.8 DOCX

### 功能

支持 `.docx`：

- paragraph；
- heading/style；
- list；
- table；
- embedded image（作为 asset/figure reference）；
- 文档顺序；
- 原 DOCX 保留。

### 特殊技术栈

Apache POI `XWPF`。

### 安全/限制

- 文件大小限制；
- ZIP bomb protection；
- embedded object/宏不执行；
- 旧 `.doc` 永久不属于最终后端范围；只支持 `.docx`。

### 临界条件

真实 DOCX 能产出合理 SourcePage/ContentBlock/Outline，不破坏统一 provenance。

---

## 3.9 Desktop Folder Import / Incremental Sync Backend

### 原则

后端永远不读取客户端 `D:\...`。

本轮后端负责“可重复同步协议、manifest、变更检测结果的服务端语义与幂等处理”；持续监听本地目录文件系统属于 Electron/Desktop 客户端职责，不需要也不允许服务器直接 watch 客户端路径。这样冻结后不再需要新增 folder-sync backend。

### 功能

为 Desktop folder import 提供正式协议：

- client 提交 sanitized relative path + metadata/sha256 manifest；
- changed/new files 上传 bytes；
- unchanged files 跳过；
- removed local files 的语义明确（默认标记 missing，不自动物理删除已发布资料）；
- 同一个 folder source 可重复 sync；
- 每次 sync 有 import/sync job 与结果摘要。

### 建议模型

可新增：

```text
FolderImportSnapshot / FolderImportEntry
```

或使用 SourceAsset + sync metadata，只要能稳定表达：relativePath、sha256、lastSeen、state。

### 临界条件

同一目录再次导入时，只上传/处理变化文件，且历史 published 数据不会因本地文件暂时缺失而消失。

---

## 3.10 Page Ordering + 人工修正

### 功能

SourcePage 最终支持：

- `pageOrder`；
- `sourcePageNumber`；
- `printedPageNumber`；
- `pageType`：COVER / TOC / BODY / APPENDIX / OTHER；
- `orderConfidence`；
- `orderStatus`：AUTO / NEEDS_REVIEW / CONFIRMED；
- batch reorder API；
- batch pageType update。

### 自动排序证据

可组合：

- ZIP/folder relative path；
- numeric filename；
- OCR printed page；
- chapter/section number；
- 上下页文本连续性；
- layout/AI suggestion。

不得把任何单一信号当绝对事实。

### 临界条件

人工拖拽排序保存后，重启应用/重跑其他阶段不会把 confirmed order 静默覆盖。

---

## 3.11 IngestionIssue

### 新模型

至少：

```text
id
spaceId
ingestionJobId
sourcePageId optional
issueType
severity
message
status OPEN/RESOLVED/IGNORED
resolvedBy
resolvedAt
createdAt
```

首批 issue：

- ORDER_LOW_CONFIDENCE；
- OCR_LOW_CONFIDENCE；
- OUTLINE_AMBIGUOUS；
- UNSUPPORTED_FILE；
- EXTRACTION_FAILED；
- STRUCTURE_LOW_CONFIDENCE。

### API

- list by source/job；
- resolve；
- ignore；
- reopen（需要时）。

### 临界条件

低置信度不再只写日志，而是变成可审查业务对象。

---

## 3.12 SourceOutlineNode

### 功能

完整实现 Source 自身目录树：

```text
BOOK
CHAPTER
SECTION
SUBSECTION
OTHER
```

字段至少：

- sourceId；
- parentId；
- title；
- numberLabel；
- sortOrder；
- startPage/endPage；
- status/confidence（如需要 review）。

### API

- tree read；
- create；
- update；
- reparent；
- reorder；
- delete/merge；
- cycle prevention；
- same-source invariant。

### 重要边界

`SourceOutlineNode != KnowledgeCategory`。

### 临界条件

真实教材能稳定表示 `第1章 → 1.1 → 1.1.1`。

---

## 3.13 ContentBlock 完整结构与 richer content

### block types

至少稳定支持：

- HEADING；
- PARAGRAPH；
- LIST；
- TABLE；
- FIGURE；
- CODE；
- FORMULA；
- OTHER。

### 功能

- 关联 SourcePage；
- 关联 SourceOutlineNode；
- sortOrder；
- normalizedText；
- structuredData；
- locator/bbox；
- edit；
- split/merge；
- block type correction；
- reorder。

### richer content backend contract

为 TABLE/FIGURE/CODE/FORMULA 定义稳定 structuredData schema，不让客户端猜 JSON。

例如：

- TABLE：rows/cells；
- FIGURE：caption + asset/page locator；
- CODE：language optional + text；
- FORMULA：latex/text representation + source locator。

### 临界条件

客户端可以在不解析未知大 JSON 的情况下正确渲染主要 block 类型。

---

## 3.14 Review / Publish 状态机

### 目标

彻底区分：

```text
技术处理成功
!=
内容已审核发布
```

### 流程

```text
processing success
→ NEEDS_REVIEW
→ owner/admin review
→ PUBLISHED
```

也允许：

```text
→ REJECTED
→ ARCHIVED
```

### 规则

- 低置信度 issue 未处理时是否允许 publish 必须明确（建议 blocker issue 禁止 publish）；
- Search/AI 默认只消费 PUBLISHED；
- AI/自动生成的 KnowledgePoint/Question 默认 DRAFT/NEEDS_REVIEW；
- owner 可审核自己私有 space，ADMIN 有治理权限。

### 临界条件

没有任何自动 pipeline 可以绕过 review 直接把正式派生内容标成 PUBLISHED。

---

## 3.15 Extraction Revision / Source Versioning

### 目标

禁止 retry/re-OCR 通过删除旧 page/block 的方式破坏 provenance。

### 模型

新增最小 revision：

```text
ExtractionRevision
- id
- spaceId
- sourceId
- version
- status DRAFT/NEEDS_REVIEW/PUBLISHED/ARCHIVED
- extractorVersion
- ocrEngine/model optional
- createdAt
- publishedAt
```

SourcePage/Outline/ContentBlock 必须能归属 revision。

### 流程

```text
RAW immutable
→ revision 1 published
→ reprocess
→ revision 2 draft
→ review
→ publish revision 2
```

### 临界条件

发布新 revision 之前，旧 published revision 仍然完整可导航；已保存 provenance 不会变成悬空关系。

---

## 3.16 Source Version Compare

### 功能

支持两个 extraction revision 的比较：

- page added/removed/reordered；
- outline changed；
- ContentBlock added/removed/changed；
- summary counts；
- 可选 bounded textual diff。

### 约束

不要求实现复杂 Git-style merge。

### 临界条件

Admin/owner 可以在发布新 revision 前知道主要变化。

---

## 3.17 sha256 去重

### 功能

同一 LearningSpace 内识别 byte-identical asset：

- duplicate detected；
- skip；
- explicitly import as new source/asset。

### 规则

- 相同文件名不同内容不能误判；
- 不同文件名相同 bytes 能识别；
- 是否跨 source 复用 physical storage 可后端内部决定，但业务 provenance 必须清晰。

### 临界条件

去重行为有稳定 machine-readable response/error code。

---

# 4. Knowledge、Note、Provenance 与 Search

## 4.1 Note Domain

### 模型

```text
Note
- id
- userSubject
- spaceId
- title
- content
- contentFormat
- createdAt
- updatedAt
- deletedAt/archivedAt optional
```

### API

- create；
- list；
- detail；
- update；
- archive/delete；
- filter by KnowledgePoint/source（需要时）。

### 格式

`contentFormat` 明确保存；不要把 Markdown 永久硬编码为唯一业务格式。

### 临界条件

Note 完整 user + space 隔离。

---

## 4.2 NoteKnowledgePoint

### 功能

Note 可关联多个 KnowledgePoint；KnowledgePoint 可有多篇 Note。

### 约束

所有关联 same-space；一次请求出现 foreign id 时整个写入失败，不允许部分成功。

### 临界条件

KnowledgePoint detail 能查询相关 Note。

---

## 4.3 NoteSource

### 功能

Note 可关联 ContentBlock/SourceReference。

### 结果

```text
Note
→ ContentBlock
→ SourcePage
→ SourceAsset/Source
```

### 临界条件

笔记中的教材引用可以真正跳回原文。

---

## 4.4 KnowledgePointSource 最终 provenance DTO

### 当前问题

只有 contentBlockId 不足以支持客户端导航。

### 最终 response 至少包含

- sourceId；
- sourceTitle；
- extractionRevisionId；
- sourcePageId；
- pageOrder；
- printed/source page number；
- contentBlockId；
- outline breadcrumb（可直接返回或可一次查询获得）；
- locator；
- archived/unavailable 状态。

### 临界条件

客户端从一条 provenance response 即可构建“某教材 > 某章 > 某页 > 某 block”。

---

## 4.5 QuestionSource

### 功能

把当前 reserved relation 完整开放：

- add/list/remove source relation；
- same-space；
- relationType；
- AI/SOURCE_DERIVED question 应保存来源。

### 临界条件

基于教材生成/整理的 Question 可以追溯原文。

---

## 4.6 KnowledgePointRelation

### 模型

支持：

```text
PREREQUISITE
RELATED
PART_OF
CONTRAST
```

建议 relation 包含：

- spaceId；
- sourceKnowledgePointId；
- targetKnowledgePointId；
- relationType；
- weight/notes optional。

### 约束

- same space；
- 禁止 self relation；
- unique；
- `PREREQUISITE/PART_OF` 可做 cycle check；
- 不引入 Graph DB。

### 临界条件

关系 API/数据结构稳定，后续客户端无需再增加后端表即可使用这些关系。

---

## 4.7 Unified Search 加 Note

最终搜索对象：

```text
SOURCE
CONTENT_BLOCK
KNOWLEDGE_POINT
QUESTION
WRONG_QUESTION（现有扩展可保留）
NOTE
```

### 约束

- current space；
- Note 还要 current user；
- literal wildcard escaping；
- bounded Unicode snippets；
- source navigation；
- 不泄漏 Question correctness。

### 临界条件

另一个用户的 Note / 另一个 space 的任何结果都不会命中。

---

## 4.8 Full-text Search

### 目标

在真实中文教材数据上验证并最终固定搜索实现。

### 首选

MySQL 8 `FULLTEXT` + `ngram` parser（若当前 MySQL 8.4 环境验证通过），并保留短词/特殊查询 fallback。

### 要求

- 中文 `MVCC / 事务隔离 / 中央处理单元` 等真实词验证；
- relevance ranking；
- type filter；
- pagination；
- short query fallback；
- LIKE escaping 路径保留为兼容 fallback；
- 通过 `EXPLAIN` 观察主要查询。

### 禁止

没有实测证据时引入 Elasticsearch/OpenSearch。

### 临界条件

真实中文数据的相关性与性能达到产品可用标准，并记录最终选型。

---

# 5. Question / Practice / WrongQuestion / Review 最终闭环

## 5.1 Question 题型最终冻结

### 已有

- SINGLE_CHOICE；
- MULTIPLE_CHOICE；
- TRUE_FALSE；
- SHORT_ANSWER。

### 历史规划中的 “more advanced Question types” — 本轮最终冻结实现

由于本轮后端冻结，不能再把“更多题型”留成后端 TODO。最终应至少补齐一组可长期覆盖学习场景的结构化题型，建议冻结为：

- FILL_BLANK；
- ORDERING；
- MATCHING。

本清单将最终结构化题型冻结为 `FILL_BLANK / ORDERING / MATCHING`。这是为消除原始文档“more advanced Question types”未枚举具体类型的空白而新增的冻结决策；如果开发前用户明确否决其中某种，必须同步在本清单中改为 `PERMANENTLY OUT OF SCOPE`，不能留下 future backend task。

### 技术设计

引入明确 `QuestionTypeHandler` / validator/evaluator registry：

- request schema validation；
- authoring answer validation；
- practice answer validation；
- deterministic grading；
- snapshot encode/decode。

避免继续在多个 Service 中复制 switch。

### 临界条件

所有最终支持题型都能在 Question → Practice → Exam snapshot → grading 全链路一致工作。

---

## 5.2 Practice 筛选

### 功能

支持按：

- KnowledgeCategory；
- KnowledgePoint；
- difficulty；
- questionType；
- count；
- 可组合 scope。

只抽 PUBLISHED question，同 space。

### 选择策略

第一版保持 deterministic/reproducible；如使用随机，必须支持 seed 或记录最终 question set。

### 临界条件

用户能按“章节/知识点/难度/题型”开始练习。

---

## 5.3 Practice 历史/统计查询

### 功能

- session list/history；
- session detail；
- answer history；
- correctness/score/duration；
- filter by time/KP/category。

### 临界条件

Statistics 不需要直接查询内部表结构即可获得训练历史。

---

## 5.4 WrongQuestion 状态完整化

### 功能

保持：

```text
ACTIVE / IMPROVING / MASTERED / DISMISSED
```

补：

- list/filter；
- dismiss/restore；
- correct-after-wrong state transition；
- wrong trend/history projection（历史事实仍来自 Practice/Exam）。

### 临界条件

WrongQuestion 不复制 Question 正文，状态规则只有服务端一份。

---

## 5.5 ReviewTask 触发来源补全

必须支持：

- WRONG_ANSWER；
- EXAM_DIAGNOSIS；
- LOW_MASTERY；
- MANUAL；
- SCHEDULED。

### 功能

- manual create；
- automatic create/refresh；
- 去重复 pending task；
- dueAt/priority；
- target KNOWLEDGE_POINT / QUESTION（需要 Source content review 时可设计明确第三类，不滥用 targetType）。

### 临界条件

所有 Accepted 触发来源都有实际代码路径。

---

## 5.6 ReviewRecord → Mastery

### 功能

完成 ReviewTask 产生 ReviewRecord，并作为 Mastery evidence：

- KP-targeted 直接影响 KP；
- Question-targeted 通过 QuestionKnowledgePoint 影响相关 KP。

### 临界条件

一次 correct/wrong review 可以产生可解释的 Mastery 变化和 evidence count 更新。

---

## 5.7 成熟 Review Scheduling

### 目标

最终后端冻结后不再需要为“真正的间隔复习”新增 schema。

### 建议

引入 `ReviewState`（或在现有模型中等价表达）：

- user + space + target；
- intervalDays；
- repetitionCount；
- lapseCount；
- easeFactor/strength（如采用）；
- nextDueAt；
- lastReviewedAt；
- policyVersion。

实现一个**版本化、确定性、可替换配置参数**的 spaced-review policy。可采用 SM-2-compatible 思路，但业务接口不得绑定算法名称。

### 要求

- MANUAL task 不被算法吞掉；
- wrong/exam/mastery trigger 可以调整 next due；
- policy version 保存在状态中；
- 无 AI 主观分数决定 dueAt。

### 临界条件

后续只调参数就能改复习节奏，不必再改 API/schema。

---

## 5.8 AI 变式题工作流

### 功能

Practice/Exam 提交后可请求变式题：

- 必须 submitted；
- 基于原 Question + KP + optional source context；
- structured JSON validation；
- 返回临时 variant；
- 如保存入题库 → `AI_DERIVED + DRAFT/NEEDS_REVIEW`；
- 不修改原 grading。

### 临界条件

用户答错后可得到真正结构化、可作答的变式题，而不只是自然语言解释。

---

# 6. Exam / Diagnosis / Statistics

## 6.1 Exam 类型最终化

至少稳定表达业务需要的子集：

- CHAPTER；
- SPECIAL/TOPIC；
- STAGE；
- MOCK；
- CUSTOM。

### 临界条件

Exam type 不再只有无法表达产品场景的单一值。

---

## 6.2 ExamPaper 版本与手工组卷

### 功能

- draft paper；
- add/remove/reorder question；
- per-question score；
- publish immutable version；
- 创建新 version 而不是改历史 published paper。

### 临界条件

历史考试永远能按 snapshot 准确还原。

---

## 6.3 ExamBlueprint 高级规则引擎

### 功能

最终支持规则：

- questionType distribution；
- question count；
- score distribution；
- time limit；
- KP/category scope；
- difficulty distribution；
- exclude/include question ids；
- total score；
- paper uniqueness constraints（bounded）。

### 技术

可使用 schema-validated `rulesJson`，但必须有服务端 Java model/validator，不允许任意 JSON 直接驱动 SQL。

### 生成

Blueprint → deterministic paper generation（记录 seed/selection result）→ ExamPaper snapshot。

### 临界条件

模拟考试/章节考试能通过同一个 Blueprint contract 生成稳定 Paper。

---

## 6.4 Exam 自动交卷

### 功能

必须支持用户关闭前端后仍自动 finalize：

- scheduler bounded sweep；
- lazy guard（answer/result/get 时也检查）；
- idempotent finalize；
- only-once result/diagnosis/mastery feedback。

### 技术

Spring `@Scheduled` + DB conditional update/locking；不引入 Quartz。

### 临界条件

deadline 到达后最终一定产生 SUBMITTED/SCORED result，重复执行无副作用。

---

## 6.5 Subjective grading final contract

### 当前

SHORT_ANSWER 可保持 UNGRADED。

### 最终后端必须具备

- manual grading endpoint（ADMIN/authorized owner depending product）；
- score + feedback；
- gradingStatus；
- regrade audit fields（至少 updatedAt/gradedBy/previous result strategy）；
- AI 可以提供建议，但最终分数由服务端授权操作确认。

### 临界条件

含 SHORT_ANSWER 的 Exam 不会永远卡在无法完成结果的状态。

---

## 6.6 ExamDiagnosis 完整维度

确保实际输出：

- KNOWLEDGE_POINT；
- CATEGORY；
- QUESTION_TYPE；
- DIFFICULTY。

结构化 item 包含：score/max/accuracy/evidence/severity/recommendation。

### 临界条件

Mastery/Review/Plan 消费结构化数据，不解析 AI narrative。

---

## 6.7 Exam → ReviewTask / Mastery / StudyPlan

### 功能

考试完成后：

```text
Result
→ Diagnosis
→ Mastery evidence
→ ReviewTask refresh/create
→ StudyPlan priority refresh
```

### 临界条件

ExamDiagnosis 不只是展示数据，而是真的改变后续学习状态。

---

## 6.8 基础 Statistics（产品必需）

第一版至少提供 space-scoped 聚合 API：

- 学习时长；
- Practice 正确率；
- WrongQuestion 数量与趋势；
- Review 完成率；
- Exam 成绩历史；
- Mastery 分布；
- LearningSpace 总览。

### 技术

复杂聚合可使用 MyBatis Mapper XML/明确 SQL；避免把大量原始行拉到 Java 内存再统计。

### 临界条件

Desktop Dashboard 不需要自己拼多个内部表计算核心统计。

---

## 6.9 Advanced Statistics

### 功能

补齐长期可用统计接口：

- 时间范围（日/周/月）；
- KP/category practice trend；
- exam trend；
- review backlog/due trend；
- mastery movement；
- study task completion；
- question-type performance；
- global multi-space overview（仅专门全局 API）。

### 安全

普通用户只能自己的数据；Admin 查看他人统计必须走 admin API。

### 临界条件

未来新增统计页面不需要再增加基础聚合 backend endpoint。

---

# 7. Mastery 与 StudyPlan 最终化

## 7.1 Mastery 多证据完整实现

最终必须消费：

- Practice；
- WrongQuestion state/evidence；
- Review；
- Exam/Diagnosis。

保留：

- practiceEvidenceCount；
- examEvidenceCount；
- reviewEvidenceCount；
- lastEvidenceAt；
- confidence。

### 临界条件

四类证据均有真实路径影响 Mastery，而不是只在字段上存在。

---

## 7.2 Mastery algorithmVersion 与可解释 projection

### 功能

- algorithmVersion；
- 当前权重/规则由服务端 policy 管理；
- API 返回 evidence breakdown；
- 不把算法公式写进客户端；
- 支持从历史事实重新 recompute。

### 临界条件

同样历史事实可以通过明确版本规则重建当前 Mastery。

---

## 7.3 Mastery calibration support

### 目标

以后只需要调整配置/参数，不需要再改 schema/API。

### 功能

- versioned weight configuration；
- recency/volume caps；
- confidence policy；
- admin dry-run/recompute job；
- recompute job status/progress/error。

### 禁止

AI 直接覆盖 masteryScore。

### 临界条件

算法参数可调整并批量重算，历史证据不丢。

---

## 7.4 StudyPlan 用户控制

补齐：

- complete；
- skip；
- unskip/restore（如适合）；
- IN_PROGRESS；
- adjust dueAt；
- adjust priority；
- reorder；
- edit title/reason（受控）；
- regenerate/refresh。

### 临界条件

AI/规则建议始终可以被用户覆盖。

---

## 7.5 StudyTask 四类真实生成路径

必须都有真实来源：

- LEARN；
- PRACTICE；
- REVIEW；
- EXAM。

EXAM 不能只存在 enum；阶段弱点/coverage 应能生成考试建议。

### 临界条件

四种 task 在真实数据上都能被 planner 生成。

---

## 7.6 Plan 状态反馈

Mastery / Review / ExamDiagnosis 变化后：

- 后续 task priority 更新；
- 已 DONE/SKIPPED 用户决策不被静默重置；
- regeneration 策略明确；
- 同 target 不制造无限重复 TODO。

### 临界条件

计划是真正动态的，但尊重用户已做出的操作。

---

# 8. AI 最终能力补齐

> 现有 AI Settings、BYOK、Tutor、conversation/messages、grounded references、post-submit explanation、Study Coach 已完成，不重写；这里只补 Accepted 仍缺的能力。

## 8.1 AIUsageRecord

### 模型

至少：

- userSubject optional；
- spaceId optional；
- provider；
- model；
- purpose；
- requestId；
- promptTokens；
- completionTokens；
- totalTokens（可推导也可存）；
- latencyMs；
- status；
- errorCode；
- createdAt。

purpose 至少：

- CONNECTION_TEST；
- TUTOR；
- EXPLANATION；
- VARIANT_QUESTION；
- STUDY_COACH；
- CONTENT_STRUCTURE；
- KNOWLEDGE_EXTRACTION；
- QUESTION_GENERATION；
- EXAM_DIAGNOSIS_NARRATIVE（如实现）。

### 安全

永远不保存：API key、Authorization、raw upstream body。

### 临界条件

成功和失败 provider 调用都有可观测记录。

---

## 8.2 GROUNDED / MIXED / GENERAL 明确区分

### 功能

AI response 增加稳定字段，例如：

```text
groundingMode = GROUNDED | MIXED | GENERAL
```

规则：

- 有真实 SourceReference 才能称 GROUNDED；
- 无来源不能伪造 citation；
- 一般模型知识必须可区分。

### 临界条件

客户端不再通过 `references.length` 自己猜回答性质。

---

## 8.3 AI Content Structure

### 功能

基于当前 source/revision 的 bounded extracted content，产生：

- proposed outline；
- block structure suggestions；
- confidence；
- warnings。

### 规则

- current space only；
- structured output schema validation；
- 结果进入 NEEDS_REVIEW；
- 不覆盖 OCR 原文；
- 不自动 publish。

### 临界条件

AI 可以辅助复杂教材结构识别，但仍有人类审核边界。

---

## 8.4 AI Knowledge Extraction

### 流程

```text
PUBLISHED/approved Source Content
→ AI
→ KnowledgePoint candidate
→ KnowledgePointSource
→ DRAFT/NEEDS_REVIEW
```

### 要求

- exact provenance；
- current space；
- structured JSON validation；
- duplicate suggestion；
- AI_DERIVED；
- batch job 可观察。

### 临界条件

每个资料派生 KP 都能回到真实 ContentBlock。

---

## 8.5 AI Question Generation

### 流程

```text
KnowledgePoint + SourceReference
→ AI
→ Question candidate
→ QuestionKnowledgePoint + QuestionSource
→ DRAFT/NEEDS_REVIEW
```

### 要求

- 覆盖最终冻结题型中的可生成子集；
- correct answer/options 结构验证；
- 不合法输出整体拒绝，不写半成品；
- 不自动 publish。

### 临界条件

AI 题目可进入 Admin 正式审核流程。

---

## 8.6 AIGenerationJob

### 功能

批量结构化/KP/题目生成必须有长任务：

- type；
- status；
- progress；
- requester；
- space/source/revision；
- successCount/failureCount；
- errorCode/safe message；
- started/finished；
- retry。

### 技术

复用 DB-backed worker 框架；不把 provider HTTP call 包在长事务中。

### 临界条件

批量 AI 任务中断后可查询、可重试、不会重复发布内容。

---

## 8.7 AI Exam Diagnosis Narrative

### 功能

在 deterministic structured ExamDiagnosis 生成后，可选 AI 生成：

- 人类可读总结；
- 错因解释；
- 学习建议。

### 边界

AI narrative 不改变：

- score；
- grading；
- structured diagnosis；
- mastery evidence。

### 临界条件

即使 AI 不可用，Exam 核心结果仍完整。

---

## 8.8 Provider/BYOK 最终一致性

保持：

- per-user runtime settings；
- encrypted secret；
- env fallback；
- decrypt failure hard fail；
- StepFun OpenAI-compatible；
- no frontend provider direct call。

补：所有新增 AI purpose 均走 `AiRuntimeConfigResolver` 和统一 Provider abstraction。

### 临界条件

项目内不存在绕过 provider abstraction 的第三方 AI HTTP client。

---

# 9. Admin Backend 与 System Config 最终化

## 9.1 Admin LearningSpace

### 功能

- list all spaces；
- filter owner/status；
- detail；
- archive/restore；
- 查看基础 statistics；
- 不通过 owner service 假装用户。

### 临界条件

ADMIN 能治理所有空间，USER 不能调用 admin path。

---

## 9.2 Admin Source/Ingestion/OCR

Admin 必须能：

- upload；
- manual source；
- list jobs；
- job detail；
- retry stage；
- issue resolve/ignore；
- page reorder；
- OCR text correction；
- outline edit；
- ContentBlock edit/split/merge；
- revision compare；
- review/publish/reject/archive。

### 临界条件

真实内容治理不需要直接改数据库。

---

## 9.3 Admin Knowledge

- Category full lifecycle；
- KnowledgePoint full lifecycle；
- provenance；
- KnowledgePointRelation；
- AI-generated draft review；
- bulk publish/archive（见 9.7）。

---

## 9.4 Admin Question

- create/edit/review/publish/archive；
- options/answers/explanation/difficulty；
- KP relation；
- Source relation；
- AI draft review；
- bulk operations。

---

## 9.5 Admin Exam

- Blueprint CRUD；
- paper generate/manual compose；
- coverage preview；
- publish/archive；
- attempts/results/diagnosis read；
- subjective grading；
- 不可改历史 frozen paper。

---

## 9.6 Admin AI/Ingestion Jobs

- list/filter；
- detail；
- status/progress/error；
- retry；
- usage/tokens/latency；
- 不显示任何 Key。

---

## 9.7 Bulk Admin Operations

### 功能

为高频治理提供 bounded bulk API：

- bulk KnowledgePoint publish/archive/category move；
- bulk Question publish/archive/tag relation；
- bulk Source review/archive；
- bulk issue resolve/ignore；
- bulk job retry（仅允许可重试状态）。

### 规则

- 最大批量条数；
- same-space 或 admin explicit target；
- 明确 all-or-nothing 与 partial-result 语义；
- 不能做任意 SQL-like filter mutation。

### 临界条件

Admin 不需要 N 次单条请求完成常见批处理。

---

## 9.8 System Config

### 目标

提供一个**白名单式**系统配置后端，而不是把任意环境变量暴露给 Admin。

### 建议分类

1. 只读 effective config：
   - build/version；
   - enabled feature flags；
   - upload/file limits；
   - OCR engine name；
   - AI provider capability（不含 secret）；
   - job worker limits；
   - retention settings。

2. 可运行时修改的安全配置（如业务确认需要）：
   - review policy 参数；
   - mastery weight 参数；
   - default pagination/limits 的受控子集；
   - AI generation batch limits；
   - ingestion confidence thresholds。

3. 永远不通过普通 JSON 明文读写：
   - DB password；
   - JWT signing secret；
   - API key；
   - encryption master key。

### 技术

- typed whitelist registry；
- DB-backed versioned settings for mutable keys；
- validation；
- restart-required flag for immutable settings；
- ADMIN only。

### 临界条件

“Admin System Config” 有正式 API，但不会演变成远程修改任意环境变量的安全漏洞。

---

# 10. Shared OpenAPI / API Client 最终封口

> 原则：后端存在的产品能力，如果 Desktop/Admin 需要使用，就必须由 shared client 合法暴露；不再允许前端 direct fetch 绕过。

## 10.1 OpenAPI 全量契约

所有新增 endpoint：

- typed request/response；
- 不返回裸 `Map`；
- security requirement；
- status codes；
- error code；
- nullable/optional 正确；
- multipart/binary 正确。

### 临界条件

live `/v3/api-docs` 包含全部新增产品 API。

---

## 10.2 `packages/api-client/src/client.ts` 全量 wrapper

最终至少覆盖：

- Auth/account；
- LearningSpace lifecycle；
- Source lifecycle；
- SourceAsset upload/raw stream metadata；
- folder sync；
- Ingestion jobs/stage retry/issues；
- page order；
- outline；
- content blocks/revisions/compare；
- KnowledgeCategory；
- KnowledgePoint/provenance/relation；
- Note；
- Question/source/KP；
- Practice；
- WrongQuestion；
- Review；
- Exam/Blueprint/Paper/Attempt/Result/Diagnosis；
- Mastery；
- StudyPlan；
- Statistics；
- Search；
- AI settings；
- conversation/messages/archive；
- explanation；
- variant question；
- coach；
- generation jobs；
- Admin APIs。

### 现有 AI wrapper

`c8714ac` 已补 AI Settings + Tutor 9 个方法，不重复回退。

### 临界条件

前端开发不再因“generated type 有、client wrapper 没有”被阻塞。

---

## 10.3 Generated types 为唯一业务类型来源

`client.ts` 使用：

```ts
type XxxRequest = components['schemas']['XxxRequest']
```

禁止复制 Java DTO 成一套手写 TS interface。

---

## 10.4 Multipart / binary contract

继续保持：

- 不设置全局 `Content-Type: application/json`；
- FormData 由 fetch 生成 boundary；
- raw asset/download 与 JSON client helper 区分清楚；
- Range header 可传递。

---

## 10.5 Stable Error Code Registry

最终为客户端需要分支处理的业务错误提供稳定 code：

- ingestion；
- OCR；
- ZIP；
- duplicate asset；
- revision/review/publish state；
- Question/Practice；
- Review；
- Exam；
- AI；
- auth/admin。

前端不得通过英文 message substring 做业务判断。

---

# 11. 数据完整性、并发、安全与可观测性

## 11.1 Async Job 幂等/并发

Ingestion/AIGeneration/Recompute：

- one worker claim；
- retry idempotent；
- crash recoverable；
- bounded concurrency；
- bounded queue；
- backpressure；
- stale running job recovery；
- graceful shutdown。

---

## 11.2 Transaction boundaries

继续遵守 AI-001~009 已证明的模式：

```text
short DB transaction
→ external call outside transaction
→ short persistence transaction
```

OCR/AI/large file decode 同样不能持有长数据库事务。

---

## 11.3 Cross-space relation invariant

以下关联全部验证 same-space：

- Category parent；
- KP category；
- KP source；
- KP relation；
- Note KP/source；
- Question KP/source；
- Practice scope/question；
- Exam paper/question；
- Review target；
- StudyTask target；
- AI context/generation。

可能的地方使用 FK/unique/index；其余由 Service invariant + integration test 保证。

---

## 11.4 Source/Revision 删除与归档

- RAW 不因 re-OCR/re-AI 覆盖；
- Source archive 不级联删除历史 Question/KP evidence；
- provenance 显示 archived/unavailable；
- physical storage cleanup 独立于业务 archive；
- Storage reconciliation 继续安全、bounded。

---

## 11.5 SSRF 最终部署判定

当前 Desktop trusted-local 模式允许自定义 HTTP(S) AI base URL。

最终开发结束前必须固定一种部署结论：

### 若仍是可信个人 Desktop / self-hosted

- 保留当前 V1 trust assumption；
- 文档明确；
- 不把 SSRF allowlist 当 blocker。

### 若最终同时开放公网多用户

本轮必须实现：

- block loopback/private/link-local；
- DNS rebinding 防护；
- redirect revalidation；
- hostname allowlist/policy。

最终不能留下“以后公网再补”的模糊状态。

---

## 11.6 Metrics

在现有 RequestId / Actuator / Prometheus 基础上补：

- ingestion duration by stage；
- OCR success/failure/latency；
- async queue depth；
- AI job outcome；
- AI latency/token usage；
- review/publish failures；
- exam auto-submit count/failure；
- mastery recompute job outcome。

禁止 userId/sourceId/jobId 等高基数 metric tag。

---

## 11.7 Health

readiness 至少继续覆盖：

- DB；
- storage；
- 必要 OCR dependency（如果 OCR 是本机 sidecar 且不可用会导致核心 ingestion 失效，则提供独立 health contributor；是否影响 readiness 要按部署策略固定）。

AI Provider 不应因为第三方暂时故障导致整个学习后端 liveness DOWN。

---

# 12. 文档与代码一致性收口（实现完成后、测试前）

在进入集中测试前，开发人员必须更新但不得提前写 PASS：

- `docs/current-task.md`；
- `docs/development-log.md`；
- `docs/development-plan.md`；
- `docs/requirements.md`（只更新实现状态，不擅自改需求含义）；
- `docs/data-model.md`；
- `docs/content-ingestion.md`；
- `docs/learning-engine.md`；
- `docs/ai-architecture.md`；
- `docs/runtime-configuration.md`；
- `docs/deployment.md`；
- `docs/operations.md`；
- OpenAPI/client 使用说明。

必须清理已经过时的状态文字，例如旧的：

```text
verification deferred
release candidate ready
pending user commit
```

但只有真实验证完成后才能写 `VERIFIED / COMPLETE`。

---

# 13. 集中自动化测试阶段

> 只有第 2~12 大点全部实现完成后进入。此阶段不再增加新功能，只修复真实缺陷。

## 13.1 Compile Gate

先执行：

```text
server test-compile
api-client typecheck
```

目标：先清除签名/DTO/mapper/生成类型问题，再进入大型测试。

### 通过条件

- BUILD SUCCESS；
- TypeScript typecheck PASS。

---

## 13.2 Flyway / Schema Gate

必须验证：

1. empty schema：`V001 → latest`；
2. 当前稳定 `V030 → latest` upgrade；
3. repeated migrate idempotent；
4. FK/check/unique/index；
5. backfill；
6. utf8mb4；
7. revision/version constraints；
8. no historical migration checksum change。

### 通过条件

专用 test schema 全部 PASS；真实业务 DB 不被测试访问。

---

## 13.3 Core Lifecycle Batch

覆盖：

- user create/reset/change password；
- LearningSpace rename/archive/restore；
- Source lifecycle；
- Category cycle/reparent；
- KP edit/review/publish/archive；
- Question edit/review/publish/archive；
- Exam lifecycle；
- foreign user/space 404。

---

## 13.4 Ingestion Batch

完整覆盖：

- TXT；
- Markdown；
- PDF text；
- PDF image-only；
- mixed PDF；
- PNG；
- JPEG；
- WebP；
- DOCX；
- ZIP；
- folder sync；
- corrupt ZIP；
- zip-slip；
- zip bomb；
- encrypted PDF；
- invalid PDF；
- invalid image；
- oversized image/file/page；
- OCR timeout/failure；
- page ordering；
- manual reorder；
- issue lifecycle；
- outline tree/cycle；
- ContentBlock edit/split/merge；
- review/publish；
- stage retry；
- revision publish；
- version compare；
- duplicate sha256；
- RAW stream/range。

### 必须加入 failure injection

验证：

- 写 DB 失败；
- storage write failure；
- OCR process failure；
- worker crash/retry；
- revision publish failure。

不得产生不可恢复半成品。

---

## 13.5 Knowledge / Note / Provenance / Search Batch

覆盖：

- Note CRUD；
- Note↔KP；
- Note↔Source；
- KP source；
- Question source；
- KP relation；
- cross-space relation reject；
- provenance locator；
- archived source reference；
- unified search；
- NOTE search user isolation；
- MySQL Chinese full-text real fixture；
- LIKE escape fallback；
- snippet Unicode bounds。

---

## 13.6 Question / Practice / Wrong / Review Batch

覆盖：

- 所有最终题型 authoring validation；
- 所有 objective type grading；
- SHORT_ANSWER ungraded/manual flow；
- Practice filters；
- snapshot immutable；
- immediate feedback；
- no pre-submit correctness leak；
- WrongQuestion transitions；
- 5 种 Review trigger；
- review scheduling policy；
- ReviewRecord → Mastery；
- duplicate task suppression；
- AI variant structured output。

---

## 13.7 Exam / Diagnosis / Statistics Batch

覆盖：

- Blueprint validation；
- paper generation；
- manual paper；
- paper version immutable；
- timer/deadline；
- scheduler auto-submit；
- lazy auto-submit；
- concurrent finalize idempotency；
- objective scoring；
- subjective manual grading；
- result；
- diagnosis 4 dimensions；
- diagnosis→review/mastery/plan；
- basic statistics；
- advanced statistics；
- global vs space isolation。

---

## 13.8 Mastery / StudyPlan Batch

覆盖：

- Practice evidence；
- Wrong evidence；
- Review evidence；
- Exam evidence；
- algorithmVersion；
- recompute；
- calibration config validation；
- LEARN/PRACTICE/REVIEW/EXAM generation；
- complete/skip/adjust；
- regeneration respects user decisions；
- duplicate task suppression。

---

## 13.9 AI Batch

覆盖：

- settings；
- AES-GCM；
- Tutor；
- Conversation/message/archive；
- references；
- groundingMode；
- explanation；
- variant；
- coach；
- content structure；
- KP extraction；
- question generation；
- generation job；
- AIUsageRecord；
- diagnosis narrative；
- malformed JSON；
- timeout；
- provider 4xx/5xx；
- secret decrypt failure；
- no raw upstream body leakage。

---

## 13.10 Admin / System Config / Security Batch

覆盖：

- anonymous → 401；
- USER admin path → 403；
- ADMIN success；
- last admin protection；
- managed space/source/content/question/exam；
- bulk bounds；
- system config whitelist；
- secret keys not readable；
- disabled account；
- role/status refresh revoke；
- all IDOR cases；
- archived-space write policy。

---

## 13.11 Full Clean Regression

最终执行完整：

```text
mvn clean test
```

以及所有 JS/TS shared-client tests/typecheck。

### 通过条件

```text
Failures = 0
Errors   = 0
```

Windows symlink 等环境性 skip 可以存在，但：

- 必须有明确原因；
- 不允许新增未解释 skip。

测试数不预设为旧的 639；新增功能后应明显增长。

---

# 14. 真实 Runtime / Product Acceptance

## 14.1 真实教材 End-to-End

必须使用至少两个 LearningSpace，并使用《数据库系统工程师教程》真实数据走完整链：

```text
ZIP
→ RAW preserved
→ ZIP manifest/assets
→ Page ordering
→ OCR
→ Outline
→ ContentBlock
→ Issue review
→ Revision review/publish
→ KnowledgePoint + provenance
→ AI Knowledge draft + review
→ Question + QuestionSource
→ Practice
→ WrongQuestion
→ Review
→ Mastery
→ Exam
→ auto-submit
→ Diagnosis
→ StudyPlan
→ Statistics
→ AI Coach
```

### 通过条件

这是最终产品级闭环，不允许用 mock OCR/mock AI 代替全部链路。

---

## 14.2 双 LearningSpace 隔离

用 Space A 与 Space B 验证：

- Source；
- Page/Content；
- KP；
- Note；
- Question；
- Practice；
- Wrong；
- Review；
- Exam；
- Mastery；
- Plan；
- Search；
- AI retrieval/generation；
- Statistics。

### 通过条件

任何修改 URL/path/body 中 id 的尝试都不能越权读写另一空间。

---

## 14.3 真实 OCR Smoke

至少：

- 中文 JPG/PNG；
- WebP；
- image-only PDF；
- 真实教材页。

记录：

- engine/version；
- latency；
- confidence；
- failure behavior。

---

## 14.4 StepFun env path

使用真实 `step-3.5-flash`：

- test-connection；
- tutor；
- explanation；
- coach；
- variant；
- structured generation；
- usage record。

不打印 API key/Authorization/raw body。

---

## 14.5 BYOK encrypted DB path

这次必须补真实 runtime：

```text
PUT apiKey
→ DB only ciphertext
→ GET no key
→ test-connection success
→ restart backend
→ decrypt success
→ provider call success
→ DELETE api-key
→ encrypted row removed/invalidated
```

### 通过条件

不再只有自动化测试覆盖 AES-GCM 路径。

---

## 14.6 Live OpenAPI + api-client

真实启动 server：

```text
GET /v3/api-docs
```

随后：

```text
npm ci
npm run api:generate
npm run typecheck
```

检查：

- generated OpenAPI 与 live server 一致；
- client wrappers 覆盖实际产品调用；
- multipart 正常；
- binary/raw 正常。

---

## 14.7 Docker Smoke

使用当前源码完整重建：

- multi-stage build；
- non-root user；
- Flyway latest；
- persistent storage volume；
- OCR dependency/sidecar；
- async job worker；
- health/liveness/readiness；
- real ingestion；
- real AI；
- secrets runtime injection；
- no secret in image layers。

---

## 14.8 Security / Secret / Repository Audit

检查工作树和镜像：

- `sk-`/真实 API key；
- Bearer token；
- JWT secret；
- DB password；
- encryption master key；
- `.env`；
- logs；
- OCR temp；
- uploaded real textbook fixture；
- `target/`；
- `node_modules/`；
- build output。

最终：

```text
git diff --check
```

必须 clean（仅平台换行提示不算 whitespace failure）。

---

# 15. 最终 Git / 文档 / 功能冻结门禁

## 15.1 开发人员最终报告必须包含

- 所有 checklist ID 的状态；
- 新 migration 列表；
- 新 endpoint 列表；
- 新 config/env 列表；
- 新 error codes；
- 测试总数/失败/错误/skip；
- real OCR 结果；
- real StepFun env 结果；
- real BYOK 结果；
- live OpenAPI path count；
- api-client generation/typecheck；
- Docker smoke；
- known limitations；
- `git diff --check`；
- `git status --short`。

开发人员仍不得自行 commit/push。

## 15.2 用户 Git 收口

由用户：

```text
git add
commit
push
```

然后：

```text
git status --short
```

必须无输出。

## 15.3 最终声明条件

只有以下全部成立，才允许写：

```text
AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN
```

条件：

- 本清单所有开发项 DONE，或用户明确批准 PERMANENTLY OUT OF SCOPE；
- Accepted 产品规划中所有 `IMPLEMENT NOW` 后端能力无缺口；
- 历史 P0/P1 不再存在“以后补”的后端项；
- 历史 P2/Future 中所有可能需要后端代码的事项均已明确归入 `IMPLEMENT NOW` 或 `PERMANENTLY OUT OF SCOPE`；
- no future backend feature backlog；
- Flyway clean/upgrade PASS；
- focused batches PASS；
- full clean Maven PASS；
- shared api-client PASS；
- real textbook E2E PASS；
- two-space isolation PASS；
- real OCR PASS；
- StepFun env PASS；
- StepFun BYOK PASS；
- live OpenAPI PASS；
- Docker PASS；
- security/secret audit PASS；
- docs consistent；
- stable user commit；
- backend worktree clean。

---

# 16. 最终完成后的后端维护规则

完成本清单并正式冻结后：

## 允许

- 修复测试发现 bug；
- 修复生产 bug；
- 安全漏洞修复；
- 依赖兼容性修复；
- migration 修复（只能新增 migration，不修改已部署历史）；
- 性能问题修复；
- 观测/日志错误修复；
- API 实现与已冻结契约不一致时的修复。

## 不允许在“bugfix”名义下偷偷新增

- 新业务域；
- 新客户端特供旁路 API；
- 新 AI 产品功能；
- 新题型；
- 新考试模型；
- 新 ingestion 格式；
- 新搜索后端；
- 新权限模型；
- 新计划/推荐系统。

如果未来确实需要以上内容，必须明确重新开启新的后端版本范围，而不是把本轮称为“没做完”。

---

# 16.1 Feature Freeze 后“允许改代码”的判定标准

为避免把新功能伪装成 bugfix，冻结后只有满足以下至少一项才可修改后端生产代码：

- 已有 Accepted/本清单定义行为与实际行为不一致；
- 自动化或真实验收暴露确定 defect；
- 安全漏洞或数据完整性风险；
- 依赖/JDK/MySQL/OS 升级造成兼容性缺陷；
- 性能问题已经达到既定功能无法正常使用的 defect 级别；
- OpenAPI/shared client 与已经存在的正式 endpoint 发生 contract defect。

以下不算 bugfix：新增新实体、新业务模块、新题型、新上传格式、新推荐算法、新客户端专用 endpoint、新外部基础设施集成。它们在冻结后都属于产品范围变更。

---

# 17. 推荐实施顺序（开发人员必须按依赖推进）

```text
A. 生命周期与基础资源
   2.1 ~ 2.9
        ↓
B. Ingestion / OCR / Revision
   3.1 ~ 3.17
        ↓
C. Knowledge / Note / Provenance / Search
   4.1 ~ 4.8
        ↓
D. Question / Practice / Wrong / Review
   5.1 ~ 5.8
        ↓
E. Exam / Diagnosis / Statistics
   6.1 ~ 6.9
        ↓
F. Mastery / StudyPlan
   7.1 ~ 7.6
        ↓
G. AI 派生能力 / Usage / Jobs
   8.1 ~ 8.8
        ↓
H. Admin / System Config / Bulk
   9.1 ~ 9.8
        ↓
I. OpenAPI / shared api-client
   10.1 ~ 10.5
        ↓
J. Security / Data / Ops hardening
   11.1 ~ 11.7
        ↓
K. Docs consistency
   12
        ↓
L. 集中自动化测试
   13
        ↓
M. Real runtime / product acceptance
   14
        ↓
N. User commit / worktree clean / freeze
   15~16
```

不得把 AI Knowledge/Question Generation 放在 provenance/revision 之前；不得把 Admin 治理 API 直接绕过正式业务 Service；不得在测试阶段继续添加新的产品功能。

---

# 18. 一页式最终核对表

## Platform / Auth

- [ ] LearningSpace rename/archive/restore
- [ ] Admin create/reset user
- [ ] self password change
- [ ] archived-space unified write policy

## Source / Ingestion

- [ ] Source lifecycle
- [ ] ADMIN_MANUAL
- [ ] authorized RAW stream + Range
- [ ] async DB-backed IngestionJob
- [ ] stage retry
- [ ] ZIP real extraction
- [ ] PNG/JPEG/WebP
- [ ] Chinese OCR
- [ ] scanned/mixed PDF OCR fallback
- [ ] TXT/Markdown final parser
- [ ] DOCX
- [ ] folder incremental sync backend
- [ ] page ordering + manual reorder
- [ ] IngestionIssue
- [ ] SourceOutlineNode
- [ ] rich ContentBlock
- [ ] Review/Publish
- [ ] ExtractionRevision
- [ ] version compare
- [ ] sha256 duplicate flow

## Knowledge / Note / Search

- [ ] Category full lifecycle
- [ ] KP full lifecycle
- [ ] Note
- [ ] NoteKnowledgePoint
- [ ] NoteSource
- [ ] KP provenance navigation DTO
- [ ] QuestionSource
- [ ] KnowledgePointRelation
- [ ] Note in search
- [ ] final Chinese full-text search

## Learning Engine

- [ ] final Question type set
- [ ] Practice category/KP/difficulty/type filters
- [ ] Practice history
- [ ] WrongQuestion transitions
- [ ] Review all trigger sources
- [ ] ReviewRecord affects Mastery
- [ ] mature review scheduling
- [ ] AI variant question workflow

## Exam / Statistics

- [ ] final Exam types
- [ ] paper version/manual composition
- [ ] advanced ExamBlueprint
- [ ] auto-submit
- [ ] subjective manual grading
- [ ] diagnosis 4 dimensions
- [ ] exam feedback to mastery/review/plan
- [ ] basic statistics
- [ ] advanced statistics

## Mastery / Plan

- [ ] all evidence sources
- [ ] algorithmVersion/explainability
- [ ] calibration/recompute support
- [ ] plan complete/skip/adjust
- [ ] four task types have real generation path
- [ ] dynamic feedback respects user decisions

## AI

- [ ] AIUsageRecord
- [ ] GROUNDED/MIXED/GENERAL
- [ ] AI content structure
- [ ] AI KP extraction
- [ ] AI question generation
- [ ] AIGenerationJob
- [ ] AI diagnosis narrative
- [ ] all AI paths use runtime resolver/provider abstraction

## Admin

- [ ] users/spaces
- [ ] source/ingestion/OCR/review
- [ ] knowledge
- [ ] question
- [ ] exam
- [ ] AI/Ingestion jobs
- [ ] bulk operations
- [ ] safe System Config

## Contract / Ops

- [ ] OpenAPI all typed
- [ ] shared client all needed wrappers
- [ ] no duplicated handwritten TS DTO
- [ ] stable error codes
- [ ] async idempotency/recovery
- [ ] transaction boundaries
- [ ] cross-space invariants
- [ ] revision/archive semantics
- [ ] SSRF deployment decision finalized
- [ ] metrics/health

## Final Verification

- [ ] test-compile
- [ ] Flyway empty + V030 upgrade
- [ ] all focused batches
- [ ] full clean Maven
- [ ] api-client typecheck/tests
- [ ] real textbook E2E
- [ ] two-space isolation
- [ ] real Chinese OCR
- [ ] StepFun env
- [ ] BYOK encrypted DB runtime
- [ ] live OpenAPI + regenerate
- [ ] Docker smoke
- [ ] security/secret audit
- [ ] docs consistent
- [ ] user commit/push
- [ ] backend worktree clean

---

**Freeze rule:** 上述清单完成后，后端产品功能开发结束并进入 Feature Freeze。之后默认只允许 bug / security / compatibility / test / operational defect 修复；普通业务新功能不属于维护范围，也不得作为“遗漏 P1/P2”继续追加。只有用户主动改变产品边界并重新立项，才属于新的项目范围，而不是本清单遗留任务。


---

文档版本：Final Scope-Normalized Revision 2  范围原则：历史 P0/P1/P2 仅作来源追溯；执行只认 `ALREADY COMPLETE / IMPLEMENT NOW / PERMANENTLY OUT OF SCOPE`。
