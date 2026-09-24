# AIStudy 最终后端剩余开发清单（Feature Freeze Completion Checklist）

> 用途：直接交给开发人员执行。  
> 目标：基于当前真实工作区，把 **Final Backend Feature Freeze** 范围内尚未完成、只写了一半、已写但未接通、存在明确逻辑/安全缺陷的后端能力一次性收口。  
> 本清单完成后，后端进入 **Feature Freeze**：后续默认只允许 Bug / Security / Compatibility / Test / Operational defect 修复，不再新增普通业务功能。  
> 最终状态：`AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN`

---

# 0. 当前基线与本任务边界

## 0.1 已核实基线

本任务基于最新提供的 `AIProject.zip` 静态审计。

当前 Git 基线 HEAD：

```text
c8714ac fix: expose AI endpoints in api client
```

当前工作树已经包含一批 Final Backend 未提交开发：

- `server/src/main` 大量已修改文件与新文件；
- 新 migration 已延伸到 `V051`；
- `packages/api-client/src/client.ts` 继续有未提交修改；
- 当前代码已经出现 LearningSpace/Source/Knowledge/Question/Exam 生命周期、Note、KnowledgePointRelation、DOCX、OCR adapter、ExtractionRevision、IngestionIssue、Outline、FolderImport、ExamBlueprint、SM-2、Mastery calibration、AIUsageRecord、AIGenerationJob、Admin/SystemConfig 等实现骨架或部分实现。

因此本任务 **禁止把这些能力全部推倒重写**。每个任务开始前先重新核对当前真实代码：

```text
已完整实现 → 保留实现，补缺失集成/契约/验证
部分实现 → 在现有结构上补齐
实现错误 → 修正明确错误并保留兼容边界
未实现 → 按本清单新增
```

静态审计已经确认存在若干硬缺口，列在后文“已核实代码依据”中。

## 0.2 已完成能力不要重做

以下以已完成基线处理，只做最终回归，不重新设计：

- BUSINESS-001~026 已完成主体；
- AI-001~009；
- Auth / JWT / Refresh / RBAC 基础；
- SourceAsset 基础上传；
- TXT / Markdown / text PDF / PNG/JPEG 基础 ingestion；
- Question / Practice / WrongQuestion / Review / Exam / Mastery / StudyPlan 已有主链；
- AI Settings / BYOK / Tutor / conversation/messages / references / explanation / coach；
- Actuator / Prometheus / Storage hardening / Docker 基础；
- `c8714ac` 已补的 AI Settings + Tutor shared-client wrappers。

## 0.3 永久不属于本轮最终后端范围

以下不是“以后补”，而是当前产品边界明确不做；不要创建 Future Backend TODO：

- Android-specific backend；
- 手机拍照专用后端流程；
- 公共/共享 LearningSpace、组织、多租户；
- 社交/支付/社区；
- 多人实时协作；
- offline multi-replica sync；
- Object Storage 强制迁移；
- chunk/resumable upload；
- 完整音视频解析；
- 旧 `.doc`；
- MasteryEvent event sourcing；
- Manual Mastery override；
- 微服务；
- Redis；
- Kafka/RabbitMQ；
- Elasticsearch/OpenSearch/Graph DB；
- AI 自动发布正式 KnowledgePoint / Question；
- autonomous agent / multi-agent；
- 前端 direct DB / direct AI Provider。

---

# 1. 开发人员必须遵守的执行规则

## 1.1 先读当前代码再动手

每个任务开始前必须重新检查：

- 直接相关 Controller / DTO / Service / Mapper / XML / Entity；
- migration；
- 相关调用方与下游；
- 已有测试；
- `docs/current-task.md`、`docs/development-log.md` 尾部、Final Backend master checklist。

不得仅按本任务文字猜测当前实现。

如果代码与本清单之间已有更新，以最新工作区真实实现为准，并在阶段报告中说明差异。

## 1.2 Git 权限

开发人员不得执行：

```text
git add
git commit
git push
git reset
git restore
git stash
git checkout
git switch
git clean
```

用户负责所有 Git 写操作。

允许只读：

```text
git status
git diff
git log
git show
git branch
git worktree list
```

不得为了“干净”破坏当前 dirty worktree。

## 1.3 本轮开发节奏

用户要求：**先完成全部功能代码，再集中测试**。

因此：

1. 第 2~12 大点先完成代码实现与静态自查；
2. 实现阶段可做必要 compile/type-level 检查，但不要每完成一个小点就跑一次完整 Maven 测试；
3. 第 13 大点统一进入自动化测试；
4. 第 14 大点执行真实 Runtime / Product Acceptance；
5. 测试阶段只修真实缺陷，不再加入新功能。

## 1.4 数据库规则

- 已提交的 `V001~V030` 禁止修改；
- 当前工作树里的 `V031+` 尚未形成稳定提交，可在确认依赖后重新编号，但一旦顺序固定不得再反复改历史；
- 禁止通过清空正式数据库掩盖升级问题；
- 既要验证 clean→latest，也要验证 V030→latest；
- 新 FK / CHECK / UNIQUE / INDEX 必须与现有数据升级兼容。

## 1.5 安全边界

所有普通用户 API：

- identity 只从 `Authentication/JWT subject` 获取；
- 不允许客户端通过 `userId/userSubject/requester/owner` 决定“自己是谁”；
- foreign user / foreign space / foreign parent resource 一律遵守既有 404 anti-probing；
- relation 写入必须做 same-space、same-owner、parent ownership 校验；
- ADMIN API 可以明确指定被管理用户/space，但必须 `ROLE_ADMIN` 且走 Admin service/query path；
- API key、Authorization、JWT secret、DB password、encryption key、raw upstream body 不进日志/响应/数据库非加密字段。

## 1.6 注释与 DTO

本轮新增/修改：

- 类、接口、方法、关键业务分支补项目现有风格注释；
- API 请求/响应 DTO 说明用途、字段约束、可空性、范围、状态/枚举；
- 复杂并发、异步、重试、版本发布、OCR、AI structured-output 逻辑必须解释“为什么”；
- Controller 不直接暴露可被客户端篡改的 Entity；
- 不用注释掩盖命名/校验/测试不足。

---

# 2. 第一大点：数据库 / Schema / 当前硬阻塞先收口

> 依赖：无。必须最先完成。  
> 这一大点结束后，后续所有代码都基于最终 schema 继续，避免反复改 migration。

## 2.1 修复 Flyway 重复版本

### 已核实代码依据

当前 migration 同时存在：

```text
V047__create_exam_blueprint.sql
V047__feature_freeze_additions.sql
```

Flyway 不允许同版本 migration。

### 要完成

- 重新梳理 `V031~V051` 的实际依赖关系；
- 重新编号当前尚未稳定提交的 migration，使每个版本唯一；
- 不修改 `V001~V030`；
- 检查每个新表/新字段被 Java 代码使用前已由更早 migration 创建；
- 不需要为了连续数字人为填一个“空 migration”；版本号可以有间隔，但不能重复且依赖顺序必须正确。

### 完成效果

Flyway migration 序列在结构上可执行，不再有 duplicate version blocker。

### 进入下一点条件

- migration 文件版本全局唯一；
- schema 依赖顺序明确；
- developer 静态复核无“先使用后建表/字段”。

---

## 2.2 把 Extraction Revision 从“独立表”变成真正的数据版本

### 已核实代码依据

当前 `V040__extraction_revision.sql` 有 `extraction_revision`，但现有 `SourcePage / ContentBlock / SourceOutlineNode` 并未形成完整 revision ownership。

当前 parser 仍可直接：

```text
delete old pages/blocks by asset
→ write new pages/blocks
```

这会破坏“旧 published revision 在新 revision 发布前仍可追溯”的目标。

### 要完成

建立最终 revision 模型，至少满足：

- `SourcePage` 能确定属于哪个 extraction revision；
- `ContentBlock` 能确定属于哪个 revision；
- `SourceOutlineNode` 能确定属于哪个 revision；
- Source 能确定当前 active/published revision；
- DRAFT / NEEDS_REVIEW / PUBLISHED / REJECTED（或项目最终命名）生命周期稳定；
- 重新 OCR/重新解析创建新 revision，不直接破坏旧 published 数据；
- publish 新 revision 时原子切换 active/current revision；
- Search / AI / Knowledge provenance 默认读取已发布/current 数据；
- 显式 version-compare 可以读取历史 revision。

### 技术提示

继续 MySQL + Flyway + MyBatis；无需 event sourcing。

### 完成效果

重新解析不会让既有 citation / KnowledgePointSource / QuestionSource / NoteSource 突然失去来源。

### 进入下一点条件

版本归属、发布指针、默认读取规则全部在 schema + service 中只有一个权威定义。

---

## 2.3 审计 Final Backend 新表约束

针对当前新增：

- note / note_knowledge_point / note_source；
- knowledge_point_relation；
- ingestion_issue；
- source_outline_node；
- extraction_revision；
- question_source；
- review_state；
- exam_blueprint；
- exam subjective grading fields；
- mastery calibration；
- ai_usage_record；
- ai_generation_job；
- folder import；
- system_config；
- 后续本清单新增的 full-text/revision 字段。

统一检查：

- FK；
- same-parent 约束由 service + 必要 DB constraint 双层保证；
- UNIQUE；
- CHECK 枚举；
- 时间/状态字段；
- 常用查询 index；
- utf8mb4；
- MySQL 8.4 兼容性。

### 完成效果

后续不需要再通过新增 schema 补普通业务遗漏。

### 进入下一大点条件

最终 schema 设计已冻结，后续除测试发现的 schema bug 外不再加功能性表结构。

---

# 3. 第二大点：身份边界 / IDOR / DTO 安全先修

> 依赖：第 2 大点。  
> 这些是当前代码中已经能静态确认的安全/契约缺陷，必须在继续业务集成前修掉。

## 3.1 Note 不允许信任客户端 `userId`

### 已核实代码依据

当前：

```text
NoteController
NoteKnowledgePointController
NoteSourceController
```

都要求：

```text
@RequestParam Long userId
```

`NoteService.create()` 甚至直接把该值写入 `userSubjectId`。

### 要完成

- 普通用户 Note API 移除客户端 owner identity 参数；
- 从 JWT subject 解析当前用户；
- 如果表内仍需要 numeric `user_account.id`，由服务端通过 authenticated subject 查出；
- Note / NoteKP / NoteSource 全链 owner + space scoped；
- foreign note 一律 404；
- API DTO 不返回内部敏感字段。

### 完成效果

客户端无法通过传别人的 `userId` 创建/读取/修改别人的 Note。

### 临界条件

普通 Note API 不再出现任何决定 owner 的 `userId/userSubject` 请求参数。

---

## 3.2 Folder Import 不允许信任客户端 `userId`

### 已核实代码依据

`FolderImportController` 的 create/submit/diff/get/list/delete 均使用：

```text
@RequestParam String userId
```

且 delete 当前明确抛：

```text
405 delete not implemented in V1
```

### 要完成

- 所有普通 folder-sync API 改用 `Authentication.getName()`；
- 删除客户端 owner 参数；
- snapshot / entries / diff / delete 都做 owner+space+source 约束；
- 实现最终 snapshot 删除/清理语义，或若业务设计决定 snapshot 不可删除，则移除伪 DELETE endpoint，并在最终契约中明确；不能保留“以后实现 V1 TODO”；
- 增量同步必须以 relative path + hash/metadata 的服务端规则计算，不相信客户端声明“这是我的资源”。

### 完成效果

Folder Import 成为真正安全可重复调用的 Desktop backend protocol。

### 临界条件

所有 endpoint identity 仅来自 Authentication；不存在 405 placeholder/TODO。

---

## 3.3 SourceOutlineNode path/source 绑定与 tree invariant

### 已核实代码依据

当前 `SourceOutlineNodeController.create()` 路径带 `sourceId`，却把客户端 `SourceOutlineNode request` 直接交给 service；service 只强制 `spaceId`，没有强制 path `sourceId`。

update/delete 也主要按 nodeId+space 查询，没有完整 path source ownership / parent cycle 校验。

### 要完成

- 使用 typed Create/Update DTO；禁止 Controller 接收持久化 Entity；
- create 时 `sourceId` 必须由 path 注入，忽略/不允许 body sourceId；
- source 必须属于当前 owner+space；
- parent 必须属于同 source、同 revision；
- 禁止 parent cycle；
- reparent 明确语义；
- start/end page 范围必须属于同 source/revision；
- update/delete 必须同时约束 nodeId + sourceId + spaceId + owner；
- 删除有子节点时行为明确：拒绝、级联或重挂必须固定一种，不留隐式行为。

### 完成效果

Outline tree 不可跨 source 污染，也不会形成环。

### 临界条件

路径参数成为资源归属真相来源，body 只能修改允许字段。

---

## 3.4 Page reorder 防跨 Source 修改

### 已核实代码依据

当前 `SourcePageController.batchReorder()` 接收 `List<SourcePage>`；`SourcePageService` 循环调用 mapper；mapper 更新条件只约束 `pageId + spaceId`，没有 `sourceId`。

### 要完成

- 改成 typed reorder request；
- request 只接受允许人工修改的字段；
- 服务端先读取当前 source 的全部目标 page；
- 每个 pageId 必须属于 path source；
- 禁止重复 ID、重复 pageOrder；
- 确认是否要求完整排列；如果允许 partial reorder，必须有稳定算法；
- 更新 SQL 至少约束 `id + space_id + source_id`；
- 成功后设置 `orderStatus=CONFIRMED`；
- printedPageNumber / pageType 是否允许用户改要用显式 DTO 字段，不允许 Entity mass assignment。

### 完成效果

用户不能拿同一 space 里另一本资料的 pageId 修改它。

### 临界条件

跨-source pageId 整个请求失败且零部分更新。

---

## 3.5 Subjective grade 权限先冻结

### 已核实代码依据

当前普通用户 `ExamAttemptController` 暴露：

```text
PUT /exam-attempts/{attemptId}/answers/{answerId}/grade
```

并直接使用当前 attempt owner 调 `gradeAnswer()`；未见管理员/可信 grader 权限检查。

### 要完成

- 最终决定并实现：主观题人工评分只能由 `ROLE_ADMIN`（当前产品没有独立 teacher role）；
- 不允许普通考生给自己的答案评分；
- 建议放入明确 Admin grading endpoint，或至少方法级 `hasRole('ADMIN')` + Admin service；
- 管理员指定 attempt/user 时必须验证真实关系；
- 评分审计字段保留 grader identity/time/reason（按现有 schema 能力）。

### 完成效果

主观评分不再是 privilege escalation。

### 临界条件

USER 调评分 API → 403；ADMIN 正常；foreign resources 不泄漏。

---

# 4. 第三大点：SourceAsset 与文件合同最终化

## 4.1 WebP + DOCX 上传 allowlist 接通

### 已核实代码依据

`SourceAssetService` 注释写了 webp，但 `ALLOWED_MIME_BY_EXTENSION` 实际只有：

```text
zip pdf jpg jpeg png md markdown txt
```

没有 `webp`、`docx`。

同时 IngestionJob 已存在 webp/docx dispatch，形成“下游支持、上传入口先拒绝”的断链。

### 要完成

允许：

```text
.webp → image/webp / octet-stream fallback
.docx → application/vnd.openxmlformats-officedocument.wordprocessingml.document / octet-stream fallback
```

并同步：

- extension error message；
- MIME validation；
- asset role；
- ingestion dispatch；
- OpenAPI/client 文件策略。

### 临界条件

正常 `.webp/.docx` 可以通过唯一正式上传入口进入 ingestion，而不是测试里绕过 service。

---

## 4.2 RAW 原始文件授权读取

### 已核实代码依据

`SourceAssetController` 当前主要有 upload/list/get metadata，没有完整 RAW stream/download endpoint；`SourceAssetResponse` 正确地不暴露 `storageKey`。

### 要完成

增加授权后的 binary endpoint：

- owner/admin 可读；
- foreign 404；
- 从 `StorageService` stream；
- 正确 Content-Type；
- Content-Disposition 安全文件名；
- 不返回 storageKey；
- 不加载整个大文件进 JVM 内存；
- 是否支持 Range 取决于现有 storage abstraction，若不支持不要为此扩大范围。

### 完成效果

前端 provenance 可以真正打开原 PDF/图片/DOCX RAW。

### 临界条件

PDF/PNG/JPEG/WebP/DOCX/TXT/MD/ZIP RAW 均能按授权读取。

---

## 4.3 sha256 去重合同

### 已核实代码依据

当前 upload 先存并插入 asset，之后 controller 才可能 checkDuplicate；这不是真正的“上传前/写入前 duplicate 行为”。

### 要完成

固定最终业务规则：

- 同 LearningSpace byte-identical（sha256 相同）可识别；
- 同名不同内容不能误判；
- 不同名相同内容能识别；
- 客户端可明确选择 `skip` 或 `importAgain`；
- 不允许意外创建重复 asset；
- 并发相同文件上传行为明确；
- ZIP entry 同样要有 idempotent duplicate policy。

### 技术提示

由于 hash 需要读流，可采用 storage temp/stream hash 后事务判断，再决定是否保留；复用现有 Storage compensation 机制。

### 临界条件

duplicate 行为不依赖 filename，且并发下不会出现不可解释的重复数据。

---

# 5. 第四大点：Ingestion / OCR / Versioning 主链最终闭环

> 这是本轮最关键的大点。完成前禁止进入 AI 批量生成最终化，因为 AI 必须建立在 published/current provenance 上。

## 5.1 IngestionJob 改成可恢复的 DB-backed async job

### 已核实代码依据

当前 `IngestionJobService`：

- create/retry 后直接 `ingestionWorkerExecutor.execute(() -> processJob(id))`；
- `processJob()` 内存在多处 `Thread.sleep(100)`；
- 未形成可靠的数据库 claim/lease/recovery；
- 应用在 QUEUED 后崩溃可能永远不再处理；
- 多 worker 竞争下缺乏明确一次 claim 语义。

### 要完成

最终 job：

```text
QUEUED
→ IMPORTING
→ EXTRACTING/OCR
→ STRUCTURING
→ NEEDS_REVIEW
→ PUBLISHED（人工发布后）
```

失败：

```text
→ FAILED
```

要求：

- job 持久化 requester/owner 或能从受信任 parent 安全解析 owner；
- DB 原子 claim；
- bounded executor；
- 应用重启后可恢复遗留 QUEUED/可恢复 PROCESSING；
- 同一个 job 不会被两个 worker 同时处理；
- 删除模拟进度用 `Thread.sleep`；
- progress 来自真实 stage；
- graceful shutdown；
- external OCR/AI/file I/O 不持有长 DB transaction；
- safe errorCode + safe message。

### 完成效果

创建 ingestion HTTP 请求快速返回，后台可靠执行，进程重启不丢任务。

### 临界条件

kill/restart 后 pending job 最终继续；并发 worker 只能一个 claim 成功。

---

## 5.2 修复当前 ExtractionRevision `ownerSubject=null` 硬 bug

### 已核实代码依据

当前 ingestion 流程调用：

```text
extractionRevisionService.create(null, spaceId, sourceId, ...)
```

而 `ExtractionRevisionService.create()` 会执行 owner-scoped LearningSpace/Source 查询。

### 要完成

- 不允许内部任务通过传 null 绕过 ownership；
- job 必须保存/解析可信 requester；
- worker 调用 revision service 时提供受信任 owner；
- 内部后台 worker 如需要 system-level service，应创建明确 internal service method，并以 job/source 已验证关系为前提，不能靠 null 特判。

### 完成效果

正常 ingestion 不会在 extraction 后创建 revision 时必然失败。

### 临界条件

job 从 upload 到 NEEDS_REVIEW 能完整走通。

---

## 5.3 真正 Stage Retry

### 已核实代码依据

当前 retry 会把 job 放回队列，但 `processJob()` 仍从开头统一执行，并不是真正从 failed stage 继续。

### 要完成

至少支持：

```text
IMPORT
EXTRACT/OCR
STRUCTURE
```

重试规则：

- 复用已成功阶段的持久化 artifact；
- OCR 失败无需重新上传 RAW；
- STRUCTURE 失败无需重做 OCR；
- retry count / previous error 可追踪；
- repeated retry 幂等；
- 不生成重复 pages/blocks/assets/revisions。

### 临界条件

人为制造 OCR failure 后修复 OCR，retry 从现有 RAW/asset 恢复。

---

## 5.4 ZIP 实际导入的幂等与安全收口

当前已有 ZIP inspection/extraction 代码，不重写，补齐：

- zip-slip canonical path；
- entry count / total uncompressed bytes / compression ratio limits；
- symlink/special entry policy；
- nested ZIP policy明确；
- `originalRelativePath` 保存；
- ZIP entry → SourceAsset → 对应 parser；
- retry 不重复创建相同 entry assets；
- 不信任目录名直接作为最终章节结构；
- 临时文件清理。

### 临界条件

真实教材 ZIP 中 PDF/图片/TXT/MD/DOCX 可以继续进入正式 pipeline，恶意 ZIP 稳定失败。

---

## 5.5 WebP decode + magic validation

### 已核实代码依据

当前 `ImageContentExtractionService`：

```text
isSupportedMime only image/png + image/jpeg
matchesImageMagic only PNG/JPEG
```

### 要完成

- 选择维护中的 ImageIO-compatible WebP decoder；
- 先做最小 decode spike 后再固定依赖；
- WebP RIFF/WEBP magic 校验；
- width/height/pixel/bytes 限制与 PNG/JPEG 一致；
- corrupted WebP 返回稳定 error code；
- 不自己实现 WebP codec。

### 临界条件

真实合法 WebP → SourcePage；损坏/超限 WebP → 安全失败。

---

## 5.6 把 Paddle OCR adapter 真正接入图片 ingestion

### 已核实代码依据

当前存在：

```text
OcrEngine
PaddleOcrProcessEngine
```

但生产代码中几乎没有 ingestion 对 `OcrEngine` 的真实调用；`ImageContentExtractionService` 仍明确 “No OCR”。

### 要完成

图片链：

```text
PNG/JPEG/WebP
→ validated temp/input
→ OcrEngine
→ extractedText
→ extractionConfidence
→ ContentBlock(s)
→ IngestionIssue（低 confidence）
```

要求：

- 中文 OCR；
- timeout；
- stdout/stderr bound；
- OCR process 非 shell string 拼接；
- executable/config server-controlled；
- temp path server-controlled；
- temp cleanup；
- output length bound；
- confidence；
- failure stable error code；
- 不覆盖 RAW。

### 特殊技术栈

优先继续使用当前 `PaddleOCR` process adapter；禁止在业务 service 内手写 Python OCR 算法。

### 临界条件

真实中文教材图片可以生成可搜索 ContentBlock 文本。

---

## 5.7 PDF text + OCR fallback 混合提取

### 已核实代码依据

`PdfContentExtractionService` 当前注释仍明确：`No OCR`。

### 要完成

逐页策略：

- 文本页优先 PDFBox text extraction；
- 无文本/文本低于合理阈值的 image-only page → 渲染页面图片 → OcrEngine；
- 每页保留 extraction method / confidence；
- page limit / byte limit / text limit 继续生效；
- encrypted PDF 仍稳定错误；
- 不因为某一页 OCR 失败导致已发布旧 revision 被破坏；
- OCR low confidence 建 issue。

### 特殊技术栈

继续 PDFBox 3.x + PaddleOCR adapter；不要引入第二套 PDF library。

### 临界条件

一份“前几页有文字、后几页扫描图”的 PDF 能在同一 source/revision 中正确产生文本。

---

## 5.8 DOCX parser 正式接主链

当前已有 DOCX parser 代码，完成：

- upload allowlist；
- MIME/magic/ZIP container validation；
- paragraph / heading / list / table 的可解释映射；
- page 不存在真实分页时使用稳定 logical page/section strategy，不伪造 printed page；
- embedded media 如不处理必须安全忽略并记录 limitation，不崩溃；
- zip-bomb 约束；
- 输出 SourcePage/ContentBlock/revision/provenance；
- retry 幂等。

### 特殊技术栈

优先复用当前 POI/OOXML 依赖（若当前 parser 已采用）；不要支持旧 `.doc`。

### 临界条件

真实 `.docx` 能从正式 upload → ingestion → NEEDS_REVIEW。

---

## 5.9 Page Ordering 自动证据 + 人工确认

除了第 3.4 安全修复，还要完成产品行为：

自动排序证据可组合：

- ZIP relative path；
- numeric filename；
- PDF page index；
- OCR printed page number；
- chapter/page text signal。

要求：

- pageOrder；
- printedPageNumber；
- orderConfidence；
- orderStatus=AUTO/NEEDS_REVIEW/CONFIRMED；
- confidence 低时产生 IngestionIssue；
- 人工 reorder 后长期保存；
- revision 内排序，不污染历史 revision。

### 临界条件

重启应用后人工顺序仍保持；低置信度页面可被前端定位并处理。

---

## 5.10 IngestionIssue 真正接入 pipeline

当前已有实体/服务/API，但必须确认并补齐 producer：

至少自动产生：

```text
ORDER_LOW_CONFIDENCE
OCR_LOW_CONFIDENCE
OUTLINE_LOW_CONFIDENCE
EXTRACTION_PARTIAL_FAILURE（如最终枚举采用）
```

要求：

- source/job/page/revision 关联明确；
- severity；
- OPEN/RESOLVED；
- resolve/reopen（若最终合同包含 reopen）；
- safe message；
- issue 解决不能假装数据自动正确，人工修改后才能相应 resolve。

### 临界条件

故意构造低 OCR confidence 能在 API 查询到 issue。

---

## 5.11 SourceOutlineNode 自动建议 + 人工修正

除安全约束外，完成：

- CHAPTER / SECTION / SUBSECTION（或最终枚举）；
- tree list；
- create/update/reparent/delete；
- start/end page；
- sortOrder；
- confidence/status；
- 自动结构建议（规则或 AI）进入 NEEDS_REVIEW；
- 人工确认后 CONFIRMED；
- revision-aware；
- `SourceOutlineNode != KnowledgeCategory`，禁止合并两个概念。

### 临界条件

真实教材能形成稳定章节树，并可人工纠正。

---

## 5.12 ContentBlock richer contract + Review 编辑 API

### 已核实代码依据

当前 `UpdateContentBlockRequest` 只有：

```text
normalizedText
structuredDataJson
locatorJson
```

而 `ContentBlockController` 当前只有 GET；没有把 service update 正式暴露为安全 typed write API。

### 要完成

最终 block type 至少支持 master scope 已冻结的：

```text
HEADING
PARAGRAPH
LIST
TABLE
CODE
FORMULA
FIGURE
OTHER
```

实际 parser 可只生成合理子集，但 contract 应冻结。

增加安全编辑 API：

- normalizedText；
- structuredData；
- locator（只允许合理修正）；
- blockType；
- outlineNodeId；
- 必要的 confidence/status；
- same source/revision validation；
- 不允许客户端改 space/source/page/revision owner identity。

### 临界条件

Admin/owner 能纠正 OCR/结构结果，且 provenance 仍能回到 RAW。

---

## 5.13 Review / Publish 状态机完整化

最终必须严格区分：

```text
技术解析成功 != 内容正式发布
```

流程：

```text
Extraction succeeded
→ NEEDS_REVIEW
→ human review/fix
→ PUBLISHED
```

拒绝：

```text
→ REJECTED
```

规则：

- Search/AI/Practice generation 默认只消费 current PUBLISHED；
- DRAFT/NEEDS_REVIEW 不得静默进入正式学习数据；
- AI 生成内容同样不自动 publish；
- publish 原子切换 revision/source 状态；
- archive 不物理破坏历史 evidence。

### 临界条件

未发布 source 不会被普通 Search/AI grounding 命中。

---

## 5.14 Source Version Compare 重写到真实 revision

### 已核实代码依据

当前 `SourceVersionCompareService` 有明确语义错误：

- 只按 revisionId+space 取 revision，没有验证两个 revision 属于 path `sourceId`/owner；
- 调 `SourcePageMapper.selectBySpaceSourceAssetOwner(fromRevisionId, ...)`，把 **revisionId 当 sourceAssetId**；
- ContentBlock 同样把 revisionId 当 assetId；
- outline diff 直接固定返回 `0,0,0`。

### 要完成

基于第 2.2 的真实 revision model 重写：

- owner + space + source + revision 全约束；
- page added/removed/changed/reordered；
- outline added/removed/changed；
- block added/removed/changed；
- bounded diff response；
- 大文本不直接返回无限完整内容；
- 历史 revision 不因 current pointer 改变而不可读。

### 临界条件

两个真实 revision 比较结果与实际数据一致，不再复用 asset 查询接口作弊。

---

## 5.15 Folder Incremental Sync 最终协议

在第 3.2 身份修复基础上补齐：

- snapshot 创建；
- entries 上传；
- diff（ADDED/MODIFIED/REMOVED/UNCHANGED 或最终枚举）；
- relativePath normalization；
- hash 优先；
- idempotent repeat submit；
- snapshot cleanup；
- 不把本机绝对路径作为后端业务主键；
- 不负责 Desktop filesystem watcher（永久 out-of-scope backend）；
- changed entry 能进入正式 SourceAsset/Ingestion 流程，而不是只生成 diff 报告。

### 临界条件

同一目录 snapshot 重复提交无重复数据；一个文件修改只触发对应 entry 变化。

---

# 6. 第五大点：Knowledge / Note / Provenance / Search 最终化

## 6.1 Note Domain DTO 化与 owner 语义统一

完成第 3.1 后继续：

- Note CRUD/archive；
- contentFormat 格式中立；
- DTO，不直接返回/接收 Entity；
- NoteKnowledgePoint many-to-many；
- NoteSource many-to-many；
- link/unlink 全部 same-space；
- archived note 是否进入 Search 的规则明确。

### 临界条件

Note 作为正式学习资产可被 Search、KP 关联、source provenance 使用。

---

## 6.2 KnowledgePointRelation 最终闭环

当前已有代码骨架，完成：

- relation type 冻结；
- from/to KP 同 space；
- 不允许 self relation（除非某个明确类型业务需要，默认拒绝）；
- duplicate relation 唯一性；
- CRUD/list；
- 删除/归档 KP 后关系可解释；
- DTO 化。

### 临界条件

关系图不会产生跨空间边。

---

## 6.3 统一 SourceReference / Provenance Projection

### 已核实代码依据

当前 `KnowledgePointSourceResponse` 只有 link identity + `contentBlockId`。

`QuestionSourceController` 与 `NoteSourceController` list 甚至只返回：

```text
List<Long> blockIds
```

这会迫使前端 N+1 reverse lookup。

### 要完成

定义一个可复用的 SourceReference projection，至少包含：

```text
sourceId
sourceTitle
sourceAssetId（需要时）
sourcePageId
pageOrder/sourcePageNumber/printedPageNumber（适合时）
contentBlockId
blockType
locator
revisionId/version（需要时）
```

用于：

- KnowledgePointSource；
- QuestionSource；
- NoteSource；
- AI references（能复用字段语义时）；
- Search jump target。

### 临界条件

客户端拿一条 provenance response 即可从 KP/Question/Note 导航回 Source→Page→Block→RAW，不做 N+1 猜关系。

---

## 6.4 QuestionSource 最终化

当前已有 link/unlink 基础，完成：

- typed request；
- SourceReference response；
- same-space/source current revision validation；
- AI-generated Question 必须保存真实 QuestionKnowledgePoint + QuestionSource；
- source/block 被 archive/version 切换后历史 Question provenance 仍可解释。

### 临界条件

任何“基于教材生成”的正式题都能追溯教材。

---

## 6.5 Unified Search 加 NOTE

### 已核实代码依据

当前 `SearchEntityType` 只有：

```text
SOURCE
CONTENT_BLOCK
KNOWLEDGE_POINT
QUESTION
WRONG_QUESTION
```

没有 NOTE。

### 要完成

加入 NOTE，并确保：

- 只搜索当前 user + current space；
- archived note 默认不返回；
- snippet bounded；
- SearchResult 能给出正确 jump target。

### 临界条件

另一个用户/空间 Note 永远不会被搜索出来。

---

## 6.6 MySQL 中文 Full-text Search

### 已核实代码依据

当前 `SearchMapper.xml` 仍是大量：

```text
LIKE #{containsPattern}
```

没有真实全文索引；count SQL 的 KP rank 分支还出现两次 prefixPattern 的明显复制错误。

### 要完成

最终当前架构采用 MySQL 8.x，不引入 Elasticsearch。

需要：

- 评估当前 MySQL 8.4 对中文 ngram/fulltext parser 的实际可用方式；
- 为适合字段建立 FULLTEXT/index；
- SOURCE/CONTENT_BLOCK/KP/QUESTION/WRONG_QUESTION/NOTE 统一查询；
- current PUBLISHED revision 过滤；
- user/space isolation；
- bounded snippets；
- deterministic ranking/tie-break；
- types filter；
- pagination/count 与 results 使用同一条件；
- 如部分实体不能安全使用 FULLTEXT，需要明确 bounded fallback，而不是继续把整套搜索伪装成 full-text。

### 特殊技术栈

MySQL 8.x FULLTEXT/ngram；不要加外部 Search 服务。

### 临界条件

真实中文教材关键词能稳定命中，不依赖 `%整段query%`。

---

# 7. 第六大点：Question / Practice / Wrong / Review 最终化

## 7.1 最终冻结题型端到端一致

当前 Question/Practice 已支持部分高级题型，但要确保下列最终冻结题型在 **Question authoring → snapshot → Practice answer → Exam answer → grading → result** 全链一致：

```text
SINGLE_CHOICE
MULTIPLE_CHOICE
TRUE_FALSE
SHORT_ANSWER
FILL_BLANK
ORDERING
MATCHING
```

### 已核实缺口

`ExamAttemptService.validatePayloadShape()`：

- 没有 MATCHING；
- ORDERING 当前从 `selectedOptionKeys` 转 Integer，而 DTO 已有 ordering answer 语义，合同不一致。

### 要完成

- 每种题型唯一 answer payload schema；
- Practice/Exam 共用或复用同一 codec/evaluator，不两套漂移；
- pre-submit 永不返回 answer truth；
- snapshot immutable；
- invalid payload 400 stable error。

### 临界条件

7 种题型在 Practice 和 Exam 均可保存/评分或按 subjective contract 进入待人工评分。

---

## 7.2 Practice filter selection 真正使用所有字段

### 已核实代码依据

`CreatePracticeSessionRequest` 已有：

```text
knowledgeCategoryId
difficulty
questionType
seed
```

但 `PracticeSessionService.selectQuestions()` 当前：

```text
auto = knowledgePointId != null
```

category-only/filter-only 不会进入 auto 模式；`knowledgeCategoryId` 实际未参与选择。已有 `QuestionMapper.selectPublishedByFilters` 也未真正接入主流程。

### 要完成

最终 selection mode：

```text
A. explicit questionIds
B. filter-based auto
```

filter-based 支持合法组合：

- category；
- KP；
- difficulty；
- questionType；
- count；
- seed。

要求：

- PUBLISHED only；
- deterministic；
- insufficient candidates 明确 400；
- same-space；
- scopeJson 真实记录条件。

### 临界条件

按“章节 + 难度 + 题型”创建 Practice 成功，且重复 seed 结果稳定。

---

## 7.3 Practice 历史过滤 + Statistics

### 已核实代码依据

`PracticeSessionController.list()` 接收：

```text
knowledgePointId
knowledgeCategoryId
```

但调用 service 时没有传入这两个条件，实际被忽略。

### 要完成

- 历史 list 真正支持 time/KP/category/status 等最终冻结筛选；
- 基础统计：session count、answered/correct/wrong、accuracy、按 KP/category 的必要汇总；
- user+space isolation；
- 聚合在 SQL/service 做，避免 N+1。

### 临界条件

传 KP/category 后结果确实变化，并与底层事实表一致。

---

## 7.4 WrongQuestion 状态最终检查

当前已有 dismiss/restore 等扩展，完成最终状态机审计：

- ACTIVE；
- IMPROVING；
- MASTERED；
- DISMISSED（若当前命名）；
- restore；
- duplicate wrong evidence 合并策略；
- 不允许非法跳转；
- archived/dismissed 是否进入 Search/plan/review 的规则统一。

### 临界条件

WrongQuestion 不会因并发 Practice/Exam 重复插出冲突状态。

---

## 7.5 ReviewTask 四种来源完整

最终必须存在真实路径：

```text
WRONG_ANSWER
EXAM_DIAGNOSIS
LOW_MASTERY
MANUAL
```

检查并补：

- manual create 必须验证 target QUESTION/KP 属于当前 space；
- low mastery trigger 必须验证 KP；
- exam diagnosis weakness 自动/显式产生 task；
- 同 target 不制造无限 duplicate pending task；
- reason/priority/dueAt 有单一 policy。

### 临界条件

四种 trigger 均有可验证 job/task 产生路径。

---

## 7.6 修复 SM-2 计算与 double-apply

### 已核实代码依据

当前 `ReviewSchedulePolicy.sm2Advance()` 在 rep>=3 时使用 `repetitions` 数值代替 previous interval 参与计算；这不是注释所写的：

```text
interval = previousInterval * easeFactor
```

同时 `WrongQuestionReviewService` 的 QUESTION completion 路径存在 `applyCompletion()` 被重复调用的风险/代码证据，应保证一次 completion 只推进一次 state。

### 要完成

- SM-2 policy 参数包含 previous interval；
- quality、ease、repetitions、interval 计算与固定公式一致；
- 一次 ReviewRecord 只调用一次 schedule state transition；
- MANUAL reason 不错误推进自动 schedule；
- 并发 completion 幂等/冲突处理；
- state 与 ReviewTask.nextDueAt 同步。

### 临界条件

给定固定 review history，next interval 可精确预测；重复提交不会多推进一次。

---

## 7.7 Review 真实进入 Mastery evidence

### 已核实代码依据

当前 `MasteryService` 读取 `reviewCount`，但实际：

```text
gradedCount = practiceCount + examCount
correctCount = practiceCorrect + examCorrect
```

Review 仅记录 evidence count，没有进入 score/confidence。

### 要完成

- KP-targeted Review CORRECT/WRONG 进入 Mastery；
- QUESTION-targeted Review 通过 QuestionKnowledgePoint 传播到相关 KP；
- 不重复计同一 ReviewRecord；
- 完成 review 后触发相应 KP recompute；
- score/confidence deterministic；
- response 显示 practice/exam/review evidence counts。

### 临界条件

只新增一条正确/错误 review evidence 时，相应 KP 的 mastery 可发生可解释变化。

---

## 7.8 AI 变式题工作流最终接通

当前已有相关代码基础，完成：

- 仅已提交 Practice/Exam answer 可请求；
- original deterministic grade 不变；
- structured output validation；
- 临时 variant 或保存到 Question Bank 的语义明确；
- 若保存：`AI_DERIVED + DRAFT/NEEDS_REVIEW`；
- 保存时 QuestionKnowledgePoint + QuestionSource；
- provider usage 进入 AIUsageRecord；
- 无合法 JSON 不写半成品。

### 临界条件

一条真实错误答案可得到结构合法的 variant，不影响原成绩。

---

# 8. 第七大点：Exam / Diagnosis / Statistics 最终化

## 8.1 ExamBlueprint rule engine 收口

当前已有 Blueprint Controller/Service/Entity，完成：

- category scope；
- KP scope；
- question type distribution；
- difficulty distribution；
- include/exclude；
- total count；
- time limit；
- PUBLISHED questions only；
- same-space validation；
- insufficient candidate 清晰失败；
- deterministic seed/ordering（若最终合同包含 seed，则正式加入）；
- no duplicate questions；
- generate 后生成 frozen ExamPaper；
- blueprint lifecycle（DRAFT/PUBLISHED/ARCHIVED 或最终命名）明确；
- 不允许 blueprint 变更历史 paper。

### 临界条件

同一 blueprint 能稳定生成合法 frozen paper，规则不足时零部分写入。

---

## 8.2 Exam Advanced Question payload parity

复用第 7.1 的统一 codec/evaluator，修复：

- MATCHING answer；
- ORDERING answer；
- FILL_BLANK；
- subjective SHORT_ANSWER；
- snapshot 的 answerData 与 API answer payload 不漂移。

### 临界条件

Practice 与 Exam 对同题型的 payload/评价规则只有一个语义源。

---

## 8.3 Subjective grading 完整闭环

### 已核实代码依据

当前 `doFinalize()` 对 `SHORT_ANSWER` 直接 `continue`，因此：

- subjective slot 不进入 `maxScore`；
- 初次 ExamResult 总分语义不完整；
- gradeAnswer 后未看到完整 Result/Diagnosis/Mastery/Review/Plan 下游重算。

当前 `gradeAnswer()` 还需要校验 score 不超过 slot maxScore。

### 要完成

定义最终 grading contract：

- submit 时所有题都计入 paper maxScore；
- objective 立即评分；
- subjective 进入 `NEEDS_REVIEW/UNGRADED`；
- ExamResult 明确 `PARTIALLY_GRADED`/等价状态；
- ADMIN grade 校验 `0 <= score <= slot.maxScore`；
- 可选 isCorrect / feedback 的一致规则；
- 最后一条 subjective 评分完成后变为最终 graded；
- regrade 更新 answer audit；
- 重新计算 ExamResult；
- 重新生成/更新 Diagnosis；
- 重新计算 Mastery；
- WrongQuestion/ReviewTask/StudyPlan 下游保持一致；
- repeated grade/regrade 有明确幂等与审计。

### 临界条件

含 objective+SHORT_ANSWER 的真实 Exam 从 submit→ADMIN grade→final result 全链正确。

---

## 8.4 Auto-submit 最终幂等

当前已有 scheduler/lazy finalize 基础，完成：

- scheduler 扫描过期 IN_PROGRESS；
- GET/answer/submit lazy safety；
- 同 attempt 并发 scheduler+user submit 只生成一个结果；
- auto-submit 对 subjective contract 同样正确；
- 失败日志带 request/attempt id，不泄密；
- 不静默吞掉导致永久卡住的 attempt；必要时记录 safe operational error/metric。

### 临界条件

用户关闭前端，deadline 后最终仍形成一次且仅一次 submitted result。

---

## 8.5 ExamDiagnosis 完整维度

检查当前实现并补齐最终 projection：

- weak KP；
- accuracy/evidence；
- severity；
- recommendation/action；
- question-level evidence（需要时）；
- deterministic structured diagnosis 是事实源；
- AI narrative 只能附加解释。

### 临界条件

Diagnosis API 不再只有空 severity/recommendation placeholder。

---

## 8.6 Exam → Review / Mastery / StudyPlan 一致

确保：

```text
Exam final/regrade
→ Diagnosis
→ WrongQuestion
→ ReviewTask
→ Mastery
→ StudyPlan candidate
```

要求：

- regrade 后能撤销/修正旧派生状态，不能只叠加新的错误 evidence；
- 低 mastery/diagnosis weakness 不重复创建无限 task；
- finalized result 才能作为正式 evidence。

### 临界条件

同一 Exam regrade 前后下游状态与最终评分一致。

---

## 8.7 Statistics 最终化

当前已有 statistics 代码基础，按 master scope 完成并核对：

- Practice accuracy/trend；
- Exam score/trend；
- KP/category weakness；
- question type/difficulty 分布；
- review workload/completion；
- mastery distribution/trend（按现有数据能支持的粒度）；
- bounded date range；
- user+space isolation；
- Admin 只在明确 admin endpoint 看管理范围；
- 聚合 SQL 无 N+1。

### 临界条件

统计值能用底层事实表手工核对，不读取别的用户/space。

---

# 9. 第八大点：Mastery / StudyPlan 最终化

## 9.1 Mastery 三类证据统一算法

基于第 7.7：

```text
Practice + Exam + Review
```

全部进入 deterministic policy。

要求：

- algorithmVersion；
- practice/exam/review evidence counts；
- lastEvidenceAt；
- score/confidence；
- 不由 AI 直接写 mastery；
- finalized/regraded evidence 一致。

### 临界条件

MasteryResponse 能解释“为什么是这个分数”。

---

## 9.2 Calibration 配置真正生效

当前 calibration table/service 已有，但 `MasteryService` 主要只实际使用 `confidenceFullSamples`；若 schema 还有 recency/volume 等参数，不得成为无效装饰字段。

### 要完成

- 固定 final calibration model；
- 每个保留参数必须真正被 scoring policy 使用；
- 无实际用途的字段在未稳定 migration 阶段清理，而不是留死字段；
- version/activate；
- dry-run/recompute；
- recompute owner+space 安全；
- activation 事务安全；
- 老 Mastery 可重新计算并记录 algorithmVersion。

### 临界条件

切换 calibration version 后 dry-run/recompute 结果可重复解释。

---

## 9.3 StudyPlan 用户控制 + 四类真实生成路径

当前已有 skip/unskip/due/priority/reorder 等代码基础，最终确认：

用户控制：

- TODO/IN_PROGRESS/COMPLETED/SKIPPED（最终枚举一致）；
- complete；
- skip/unskip；
- dueAt 调整；
- priority；
- reorder；
- regenerate/refresh；
- plan replacement/obsolete 规则。

四类 task 必须都有真实生成源：

```text
LEARN
PRACTICE
REVIEW
EXAM
```

EXAM 不能只是 enum 没有 candidate source。

### 临界条件

在有 weak mastery/diagnosis/review workload 的空间中能实际生成四类中符合条件的 task；用户修改不会被后台无条件覆盖。

---

# 10. 第九大点：AI 派生内容 / Usage / Job 最终闭环

## 10.1 AIUsageRecord 覆盖所有 provider 调用

### 已核实代码依据

当前 Tutor / Explanation / Coach / Connection Test 已开始写 usage；但新加入的：

```text
VARIANT_QUESTION
CONTENT_STRUCTURE
KNOWLEDGE_EXTRACTION
QUESTION_GENERATION
EXAM_DIAGNOSIS_NARRATIVE
```

必须逐一核对，当前 generation service 中并未统一看到 usage 记录。

### 要完成

每个 provider call 成功/失败记录：

- userSubject；
- spaceId；
- provider/model；
- purpose；
- requestId（有）；
- prompt/completion/total tokens；
- latency；
- SUCCESS/FAILED；
- stable errorCode。

永不存：

- API key；
- Authorization；
- raw provider body；
- 完整敏感 prompt。

### 临界条件

代码库内每个 `aiProvider.chat(...)` 都能追到统一 usage/metrics 处理路径。

---

## 10.2 GroundingMode 一致扩展

Tutor 当前已有 `GROUNDED/MIXED/GENERAL` 基础，确保：

- DTO/OpenAPI/client 全部暴露同一 enum/字符串合同；
- GROUNDED 必须有真实 SourceReference；
- GENERAL 不伪造 citation；
- 新 AI generation/diagnosis 如返回 grounding 信息也遵守同语义。

### 临界条件

前端不需要用 `references.length` 猜 grounded 状态。

---

## 10.3 AI Content Structure 不得直接破坏原数据

当前已有 service，但最终要求：

- 输入 current source/revision bounded extracted content；
- structured JSON schema validation；
- 输出 proposed outline/block suggestions + confidence/warnings；
- 进入 DRAFT/NEEDS_REVIEW；
- 不覆盖 OCR raw/extracted truth；
- 不自动 publish；
- owner/space/source validation；
- usage record；
- provider error 不写半成品；
- persistence 使用正式 domain service，不直接绕 mapper 破坏 invariant。

### 临界条件

AI structure 失败时现有 published revision 完全不受影响。

---

## 10.4 AI Knowledge Extraction + exact provenance

### 已核实代码依据

当前 `AiKnowledgeExtractionService` 已能解析 JSON 并直接插 `KnowledgePoint`，但：

- 直接 mapper insert；
- 失败常以 empty list 吞掉；
- 未见 exact KnowledgePointSource persistence；
- 输入 text 与 source/block provenance 脱节。

### 要完成

正式流程：

```text
PUBLISHED source/revision content blocks
→ bounded prompt
→ structured candidates
→ validate/dedup
→ KnowledgePoint DRAFT/NEEDS_REVIEW AI_DERIVED
→ KnowledgePointSource exact links
```

要求：

- 每个 candidate 有来源 block；
- 同一调用要么合法事务落 candidate+links，要么该 candidate 不落半条；
- duplicate suggestion，不自动覆盖 existing published KP；
- safe provider error；
- usage record。

### 临界条件

每一个 AI-derived KP 都至少有一条真实可导航 provenance。

---

## 10.5 AI Question Generation 最终化

### 已核实代码依据

当前 `AiQuestionGenerationService` 主要硬编码 SINGLE_CHOICE，并直接 mapper insert Question/options；没有完整 QuestionKnowledgePoint/QuestionSource 原子写入。

### 要完成

- 支持“最终冻结题型中的可生成子集”，至少明确清单，不允许模型任意发明 questionType；
- structured JSON validation；
- option keys unique；
- correct answer 合法；
- difficulty enum；
- answerData codec；
- Question DRAFT/NEEDS_REVIEW + AI_DERIVED；
- QuestionKnowledgePoint；
- QuestionSource exact provenance；
- domain service/transaction；
- invalid output 零半成品；
- usage record；
- 不自动 publish。

### 临界条件

生成的题可以直接进入 Admin Review，而不是成为孤立 Question row。

---

## 10.6 AIGenerationJob API + worker 重构

### 已核实代码依据

当前存在 AIGenerationJob entity/service/worker，但：

- 未发现普通 space-scoped 创建 generation job 的正式 controller；
- worker 自建 daemon Thread 轮询；
- `listPending()` 后 `start()`，缺乏清晰的单次 atomic claim/lease；
- finish successCount 基本硬编码 1；
- question-generation 把 `revisionId` 当 `knowledgePointId` 使用；
- failure 将 `e.getMessage()` 直接保存为 safeMessage，有泄漏 raw provider/internal detail 风险。

### 要完成

最终 job contract：

- explicit jobType enum；
- requester from Authentication；
- space/source/revision/knowledgePoint 等目标使用语义正确字段或 typed input JSON；
- create/get/list/retry/cancel（仅若 master scope已有 cancel；没有则不扩）API；
- DB atomic claim；
- bounded worker；
- restart recovery；
- progress；
- correct success/failure counts；
- safe error sanitizer；
- external AI call 不持长事务；
- retry 幂等，不重复发布/插入；
- Admin 可查看/重试，但不泄 secret/prompt raw。

### 临界条件

两个 worker 同时看到 pending job 时只有一个执行；question-generation 不再滥用 revisionId。

---

## 10.7 AI Exam Diagnosis Narrative

当前 `AiSourceDiagnosisService` 实际只是在拼 SourcePage 文本，不等于 Exam Diagnosis Narrative。

### 要完成

在 deterministic `ExamDiagnosis` 已生成后提供可选 AI narrative：

- human-readable summary；
- wrong reason explanation；
- recommended learning actions；
- grounding 到真实 diagnosis/KP/source（有来源时）；
- 不修改 score/grading/mastery/structured diagnosis；
- AI unavailable 时 Exam 核心结果不受影响；
- usage record；
- stable endpoint/OpenAPI/client wrapper。

### 临界条件

关闭 AI 后 Exam result/diagnosis 仍完整；开启后仅增加 narrative。

---

## 10.8 Provider/BYOK 新能力统一走 resolver

所有新 AI service 必须：

- `AiRuntimeConfigResolver.resolveForUser(subject)`；
- 统一 `AiProvider` abstraction；
- per-user runtime settings > env；
- encrypted secret > env（无 secret row 时才 fallback）；
- decrypt failure hard fail；
- 不在新 service 里自己 new HttpClient 调 provider URL。

### 临界条件

代码搜索不存在第二套第三方 AI HTTP 实现。

---

# 11. 第十大点：Admin Backend + SystemConfig 最终化

## 11.1 Admin Source / Ingestion / OCR 治理 API

### 已核实代码依据

当前 admin 包已有 space/knowledge/question/exam/ai/bulk 等，但未见完整 Admin Source/Ingestion/OCR governance controller。

### 要完成

ADMIN 能：

- list/search Source；
- source detail；
- asset/revision/job/issue 查看；
- retry failed ingestion stage；
- page order 修正；
- ContentBlock 修正；
- Outline 修正；
- resolve issue；
- review/publish/reject/archive；
- 查看 RAW（授权）；
- 查看 OCR/extraction safe errors。

要求：

- ROLE_ADMIN；
- 不伪装成 owner 调普通 owner endpoint；
- 复用 domain service validation；
- 不直接随意 mapper update 状态。

### 临界条件

Admin Web 所需内容治理不再需要 DB 手工操作。

---

## 11.2 Admin Knowledge / Question / Exam service 边界复核

当前部分 Admin Controller 有直接 mapper 使用迹象。完成：

- 管理查询可以专用 mapper；
- 任何业务状态写入必须经过 domain/admin service；
- Knowledge publish/archive/provenance；
- Question edit/publish/archive/source relation；
- Exam create/edit/publish/archive/blueprint/grade；
- historical frozen paper 不能被 Admin 修改；
- foreign space/operator scope 清晰。

### 临界条件

Admin 写操作不绕过业务 invariant。

---

## 11.3 Admin AI / Ingestion Job + Usage

完成统一后台：

- AI generation jobs list/detail/filter/retry；
- ingestion jobs list/detail/filter/retry；
- AI usage by provider/model/purpose/status/time；
- token/latency aggregate（bounded）；
- safe errors；
- 不返回 API key / ciphertext / auth header / raw prompt/provider body。

### 临界条件

故障排查不需要直接查数据库。

---

## 11.4 Bulk Admin Operations

当前已有 bulk 基础，最终复核：

- 支持 master scope 中明确的批量 publish/archive/status 操作；
- max batch size；
- 每 item validation；
- 明确 atomic-all-or-nothing 或 per-item result，不得含糊；
- 不能通过 bulk 绕过单体 API 的 lifecycle/security；
- 返回 typed per-item outcome。

### 临界条件

bulk 与单项 service 使用同一状态机。

---

## 11.5 SystemConfig 从“可存字段”变成真正白名单配置

### 已核实代码依据

当前 `SystemConfigService` 有 whitelist，但 Controller 允许客户端传：

```text
configType
validationSchema
restartRequired
```

Service create 直接接受这些字段；`validationSchema` 并未真正执行；whitelist key 的类型不是服务端固定 descriptor。

Entity 注释提到 NEVER_READABLE secrets，但当前实现只是 response redaction，不是真正加密。当前 whitelist 本身又都是非 secret key。

### 要完成

最终设计：

- 每个 key 的 type/validation/restartRequired 由服务端 registry 固定；
- 客户端只能提供 value；
- 不允许客户端把普通 key 自行标成 NEVER_READABLE；
- SystemConfig **不作为任意 secret/env editor**；JWT/DB/AI master key/API key 继续现有安全配置路径；
- 如果没有必须在 SystemConfig 存的 secret，就删除 NEVER_READABLE 的“伪安全能力”，不要存 plaintext 再靠 `***` 假装加密；
- value 类型/范围真正校验；
- 保留的 runtime key 必须真正被对应服务读取并生效；
- 若 `ai.temperature`、`ai.maxOutputTokens`、`studyPlan.dailyItemLimit`、`mastery.confidenceFullSamples` 与现有专门配置体系冲突，统一来源和 precedence，不允许两个真相源；
- version/audit updatedAt。

### 临界条件

修改一个允许 runtime key 后，真实业务行为变化；非法 key/value 400；无法通过 SystemConfig 读取/写入项目 secret。

---

# 12. 第十一大点：OpenAPI / Shared Client / Error Contract / Ops 最终封口

> 依赖：第 2~11 大点的业务 API 基本稳定后再做。不要边改 endpoint 边反复 regenerate。

## 12.1 所有新 API DTO/OpenAPI 化

检查 Final Backend 新 Controller：

- 不直接接收/返回 Entity；
- request/response typed；
- validation annotation；
- security requirement；
- status code；
- nullable/optional；
- enum；
- pagination；
- binary endpoint content type；
- 404/409/422/5xx 语义一致。

重点清理当前已确认 Entity-style API：

- SourceOutlineNode；
- Note；
- FolderImportSnapshot/Entry（若直接返回 entity）；
- ExtractionRevision（若直接返回 entity）；
- SystemConfig 内部字段；
- 其他新 controller。

### 临界条件

live OpenAPI 不暴露 storageKey、ciphertext、内部 secret、任意 DB-only 字段。

---

## 12.2 `packages/api-client/src/client.ts` 全量 wrapper

最终后端 feature freeze 之后，shared client 必须覆盖 Desktop/Admin 正式需要的所有 API，不允许 frontend direct fetch 绕过。

至少审计并补：

- LearningSpace lifecycle；
- Source lifecycle/manual；
- SourceAsset upload/raw/duplicate；
- Ingestion/retry/revision/issues；
- pages/order；
- outline；
- ContentBlock review；
- folder sync/version compare；
- Category/KP/KPRelation/provenance；
- Note/NoteKP/NoteSource；
- Search；
- Question/QuestionSource；
- Practice/history/stats；
- Wrong/Review；
- Exam/Blueprint/Attempt/Grade/Diagnosis/Stats；
- Mastery/calibration；
- StudyPlan/StudyTask controls；
- AI settings/tutor/messages/explanation/variant/coach/generation/diagnosis/usage；
- Admin Source/Knowledge/Question/Exam/Jobs/Bulk/SystemConfig。

现有 `c8714ac` 的 AI 9 wrappers 不重复写，但要确认缺失的：

```text
listConversationMessages
explainPracticeAnswer
explainExamAnswer
studyCoach
```

及所有新 endpoint。

### 技术规则

- 类型来自 generated OpenAPI；
- 不复制手写 DTO；
- 不设置全局 `Content-Type: application/json`；
- FormData 让运行时生成 boundary；
- RAW binary 用 Blob/ArrayBuffer/Response-compatible contract；
- 统一 auth/error helper。

### 临界条件

前端实现正式功能不需要任何 direct fetch。

---

## 12.3 Stable Error Code Registry

当前大量逻辑仍依赖 `ResponseStatusException` reason 文本。最终把前端需要分支判断的错误固定为 machine-readable code。

至少覆盖：

- upload/duplicate；
- ZIP；
- PDF；
- image/WebP；
- OCR；
- ingestion stage；
- revision/review/publish；
- Practice/Review；
- Exam deadline/state/grading；
- AI settings/provider/generation；
- folder sync；
- bulk admin。

要求：

- message 可读；
- code 稳定；
- HTTP status 合理；
- 不把 exception class/raw SQL/raw provider body 暴露给客户端。

### 临界条件

客户端不需要解析英文 message 判断业务错误。

---

## 12.4 Async / Transaction / Concurrency 最终硬化

统一审计：

- Ingestion job claim/recovery/idempotency；
- AI job claim/recovery/idempotency；
- Exam submit/auto-submit；
- subjective regrade；
- Review completion/SM2；
- duplicate upload；
- revision publish；
- bulk operations。

规则：

- 外部 OCR/AI/large file I/O 不处于长事务；
- DB persistence 用短事务；
- state transition 使用 expected-status conditional update；
- repeated request/retry 不制造重复 evidence；
- crash recovery 明确。

### 临界条件

关键状态机在并发下不会产生两份 result/revision/task/job processing。

---

## 12.5 Metrics / Health / Deployment

补最终新增能力的 bounded metrics：

- ingestion duration by stage；
- ingestion success/failure；
- OCR duration/success/failure；
- async queue depth；
- AI generation outcome；
- AI latency/token usage；
- review/publish failure；
- auto-submit/regrade failure（需要时）。

禁止用：

```text
userId
spaceId
sourceId
jobId
```

作为高基数 metric tag。

Deployment：

- OCR executable/model/runtime 必须进入 Docker/deploy 文档与环境变量；
- non-root；
- temp/storage permissions；
- graceful shutdown；
- health/liveness/readiness；
- secrets runtime inject；
- 不 bake AI/JWT/DB secret。

SSRF：当前若仍是可信个人 Desktop/self-hosted，保留已记录的 arbitrary http(s) BYOK trust assumption；不要本轮擅自扩大公网多租户范围。

### 临界条件

Docker 环境与本机正式能力一致，不出现“本机 OCR 能跑、镜像不能跑”。

---

# 13. 第十二大点：功能覆盖审计——确保本轮之后没有普通后端开发 backlog

> 这是代码阶段最后一关。在开始集中测试前执行。不是让开发人员新增想象中的功能，而是对照最终冻结范围逐项查漏。

开发人员必须逐项建立状态表，并给出代码证据：

```text
ALREADY COMPLETE
COMPLETED IN THIS ROUND
PERMANENTLY OUT OF SCOPE
```

不得出现：

```text
TODO later
P1 later
V2 backend
暂不实现
前端绕过
以后补 API/schema/job
```

必须逐项覆盖以下最终产品面：

### Platform/Auth

- Login/refresh/logout/me；
- Admin user create/reset/status/roles；
- self password change；
- LearningSpace lifecycle；
- archived write policy。

### Source/Ingestion

- upload；
- raw read；
- TXT/MD；
- PDF text + OCR fallback；
- PNG/JPEG/WebP OCR；
- DOCX；
- ZIP；
- folder incremental sync backend；
- page order；
- issues；
- outline；
- richer blocks；
- review/publish；
- revision；
- stage retry；
- version compare；
- sha256 duplicate。

### Knowledge/Note/Search

- Category lifecycle；
- KP lifecycle；
- KP relation；
- KP provenance；
- Note；
- NoteKP；
- NoteSource；
- QuestionSource；
- unified Search + Note；
- Chinese full-text。

### Learning Engine

- frozen question types；
- Practice filters/history/stats；
- Wrong states；
- Review four triggers；
- SM-2；
- Review→Mastery；
- AI variant。

### Exam/Stats

- exam lifecycle/type；
- paper snapshot；
- blueprint；
- auto-submit；
- subjective grade/regrade；
- diagnosis；
- exam→review/mastery/plan；
- statistics。

### Mastery/Plan

- three evidence types；
- algorithmVersion；
- calibration/recompute；
- StudyPlan user controls；
- LEARN/PRACTICE/REVIEW/EXAM generation；
- plan state feedback。

### AI

- usage；
- grounding mode；
- tutor/explanation/coach；
- content structure；
- KP extraction；
- question generation；
- generation job；
- diagnosis narrative；
- provider/BYOK consistency。

### Admin/Contract/Ops

- Admin spaces；
- Admin source/ingestion/OCR；
- Admin knowledge/question/exam；
- Admin AI/ingestion jobs；
- bulk；
- system config；
- live OpenAPI；
- shared client wrappers；
- stable errors；
- metrics/health/deploy。

### 进入测试阶段的唯一条件

上面所有项只能是：

```text
ALREADY COMPLETE
或
COMPLETED IN THIS ROUND
或
PERMANENTLY OUT OF SCOPE
```

没有任何 `PARTIAL/TODO/DEFERRED`。

---

# 14. 第十三大点：集中自动化测试阶段

> 只有第 2~13 大点全部功能代码完成后开始。  
> 这一阶段不再扩大功能范围，只修真实 Bug、契约错、并发错、安全错、migration 错。

## 14.1 Compile Gate

从后端目录执行项目现有 Maven Wrapper：

```powershell
cd D:\AIProject\server
.\mvnw.cmd test-compile
```

shared client：

```powershell
cd D:\AIProject\packages\api-client
npm ci
npm run typecheck
```

通过条件：

- Java main/test compile 全 PASS；
- TypeScript typecheck PASS；
- 不通过时先修 compile，不继续大测试。

---

## 14.2 Flyway Gate

使用现有 `flyway-it` 专用测试库验证：

1. clean → latest；
2. committed V030 schema → latest；
3. 所有 CHECK/FK/UNIQUE/index；
4. utf8mb4；
5. duplicate migration version = 0；
6. no destructive historical migration edit。

通过条件：两条 migration path 均 PASS。

---

## 14.3 Core/Auth/Lifecycle Batch

覆盖：

- LearningSpace rename/archive/restore；
- archived write policy；
- Admin create/reset/self-password；
- Source lifecycle/manual；
- Category/KP/Question/Exam lifecycle；
- 401/403/404 anti-probing；
- disabled user；
- refresh revoke；
- last active admin protection。

---

## 14.4 Ingestion Batch

必须覆盖真实或可信 fixture：

```text
TXT
Markdown
PDF text
PDF image-only
mixed PDF text+scan
PNG
JPEG
WebP
DOCX
ZIP
corrupt ZIP
zip-slip
zip bomb
invalid WebP/image
encrypted PDF
oversize file/image
OCR success
OCR low confidence
OCR timeout/OCR process failure
page reorder
cross-source reorder attack
ingestion issues
outline/cycle/reparent
content block edit
review/publish/reject
stage retry
revision publish
old revision retained
source version compare
sha256 duplicate
RAW stream
folder snapshot/diff/repeat sync
```

必须有 failure injection：

- DB insert failure after storage write；
- worker crash/restart；
- concurrent claim；
- parser failure mid-job；
- revision publish race。

---

## 14.5 Knowledge / Note / Provenance / Search Batch

覆盖：

- Note CRUD/archive；
- identity cannot be client-forged；
- Note↔KP；
- Note↔Source；
- KP relation；
- KP/Question/Note SourceReference projection；
- cross-space link rejection + zero partial write；
- Search NOTE；
- Chinese full-text；
- wildcard/special chars；
- bounded snippet；
- current PUBLISHED only；
- user/space isolation。

---

## 14.6 Question / Practice / Review Batch

覆盖：

- 7 frozen question types；
- authoring/publish/archive；
- answer truth zero leak before submit；
- Practice explicit IDs；
- Practice category/KP/difficulty/type/count/seed；
- history filter；
- statistics；
- Wrong states；
- four Review triggers；
- manual target validation；
- SM-2 fixed known sequences；
- no double-advance；
- Review→Mastery；
- AI variant success/malformed/provider failure。

---

## 14.7 Exam / Diagnosis / Statistics Batch

覆盖：

- exam lifecycle/types；
- blueprint all rule dimensions；
- frozen paper；
- answer payload parity for all relevant types；
- auto-submit；
- scheduler + user submit concurrency；
- subjective partial grading；
- USER cannot grade；
- ADMIN grade/regrade；
- score bound；
- result recompute；
- diagnosis recompute；
- mastery/review/plan downstream；
- statistics correctness；
- cross-user/space isolation。

---

## 14.8 Mastery / StudyPlan Batch

覆盖：

- Practice evidence；
- Exam evidence；
- Review evidence；
- question-target review → KP；
- algorithmVersion；
- confidence；
- calibration create/activate/dry-run/recompute；
- task complete/skip/unskip/in-progress/due/priority/reorder；
- regenerate；
- LEARN/PRACTICE/REVIEW/EXAM candidates；
- plan state。

---

## 14.9 AI Batch

覆盖：

- Settings/BYOK crypto；
- Tutor/messages/references/groundingMode；
- explanation；
- variant；
- coach；
- content structure；
- KP extraction + exact provenance；
- question generation + relations/source；
- exam diagnosis narrative；
- AIUsageRecord every purpose；
- AIGenerationJob create/claim/progress/retry/recovery；
- malformed JSON；
- provider timeout；
- provider 4xx/5xx；
- decrypt failure；
- safe errors；
- no API key/Auth/raw body leakage。

---

## 14.10 Admin / SystemConfig / Security Batch

覆盖：

- anonymous admin → 401；
- USER admin → 403；
- ADMIN success；
- Admin Source/Ingestion/OCR；
- Admin Knowledge/Question/Exam；
- jobs；
- bulk；
- SystemConfig whitelist + validation + real runtime effect；
- no arbitrary secret editor；
- SourceOutline forged sourceId；
- Page cross-source reorder；
- Note/Folder forged userId；
- subjective self-grade；
- revision/version compare cross-source；
- all final IDOR cases。

---

## 14.11 Full Clean Regression

在相关 focused batches 全部 PASS 后执行：

```powershell
cd D:\AIProject\server
.\mvnw.cmd clean test
```

通过条件：

```text
0 failures
0 errors
```

已有 Windows symlink 条件性 skip 可以保留，但：

- 必须说明原因；
- 不允许新增无法解释的 skip；
- 未运行的测试不能写 PASS。

---

# 15. 第十四大点：真实 Runtime / Product Acceptance

## 15.1 真实中文教材 End-to-End

至少用一份真实中文教材（项目此前规划可继续使用《数据库系统工程师教程》或等价真实资料）跑完整链：

```text
ZIP/Folder/RAW upload
→ internal assets
→ PDF/Image/DOCX/TXT/MD
→ Page
→ Chinese OCR
→ Page ordering
→ Extraction Revision
→ Outline
→ ContentBlock
→ IngestionIssue
→ human correction
→ Publish
→ KnowledgePoint
→ exact provenance
→ AI Knowledge draft
→ AI Question draft
→ admin review/publish
→ Practice
→ WrongQuestion
→ Review
→ Mastery
→ Exam Blueprint/Paper
→ Exam answer/auto-submit
→ subjective grade（如样本含主观题）
→ Diagnosis
→ StudyPlan
→ AI Coach / Diagnosis narrative
```

通过条件：

- 每一阶段都有可追溯 ID；
- 无数据库手工补数据；
- 无 direct fetch 绕 shared client 的 contract 缺失；
- provenance 能回 RAW。

---

## 15.2 双 LearningSpace 隔离

准备 Space A / B：

- Source；
- Note；
- KP；
- Question；
- Practice；
- Exam；
- Review；
- Search；
- AI context；
- Folder snapshot；
- revision；
- admin/user boundary。

验证 B 的普通用户请求无法读取/修改 A 的任何数据。

通过条件：所有跨-space 读写符合 404/403 既定规则，无 IDOR。

---

## 15.3 真实中文 OCR Smoke

必须使用真实中文扫描图片/扫描 PDF，不只 hello-world 英文图。

记录：

- OCR engine/model；
- latency；
- text；
- confidence；
- low-confidence issue；
- timeout/failure 行为；
- Docker 内同样可用。

---

## 15.4 StepFun env path

继续用当前 provider abstraction 验证：

```text
OPENAI_COMPATIBLE
https://api.stepfun.com/v1
step-3.5-flash
```

至少：

- test-connection；
- Tutor；
- Coach；
- 一个新 generation purpose；
- AIUsageRecord；
- no key leakage。

真实 key 只放运行时环境，不打印到日志/报告。

---

## 15.5 BYOK encrypted DB path

本轮必须补上此前只有自动化覆盖、没有真实 smoke 的路径：

```text
PUT /settings/ai with apiKey
→ DB only ciphertext
→ GET no key
→ test-connection success
→ restart backend
→ decrypt + provider call success
→ DELETE api-key
→ fallback/AI_NOT_CONFIGURED behavior correct
```

通过条件：数据库、日志、响应均无 plaintext key。

---

## 15.6 Live OpenAPI + api-client

真实后端启动后：

```powershell
cd D:\AIProject\packages\api-client
npm ci
$env:OPENAPI_URL="http://127.0.0.1:18080/v3/api-docs"
npm run api:generate
npm run typecheck
```

如果实际启动端口不同，使用实际 URL；不要改脚本来迎合测试。

检查：

- 所有最终 endpoint 在 live spec；
- generated `openapi.json/api.d.ts` 与 live contract 一致；
- `client.ts` wrapper typecheck；
- binary/multipart 正确；
- 无 direct-fetch-only contract blocker。

---

## 15.7 Docker Smoke

使用当前源码重建镜像，验证：

- multi-stage build；
- non-root；
- latest Flyway migration；
- storage volume；
- OCR runtime/model/permission；
- async ingestion；
- async AI generation；
- health/liveness/readiness；
- RAW read；
- real Chinese OCR；
- real StepFun call；
- graceful shutdown/restart job recovery；
- image layer 不含 API key/JWT/DB password。

---

## 15.8 Secret / Repository Audit

检查工作树不得包含：

```text
真实 API key
Bearer token
JWT secret
DB password
AI master encryption key
.env real secrets
OCR temp files
uploaded textbook fixtures（除明确测试 fixture）
target/
node_modules/
*.log
```

开发人员最后执行只读检查：

```powershell
cd D:\AIProject
git --no-pager diff --check
git status --short
```

注意：工作树在用户 commit 前仍会 dirty，这是正常状态；开发人员不得自行提交。

---

# 16. 文档最终收口

功能实现与测试结果稳定后统一更新：

- `docs/current-task.md`；
- `docs/development-log.md`；
- `docs/development-plan.md`；
- `docs/requirements.md` 状态（不改原需求含义）；
- `docs/data-model.md`；
- `docs/content-ingestion.md`；
- `docs/learning-engine.md`；
- `docs/ai-architecture.md`；
- `docs/runtime-configuration.md`；
- Docker/deployment 文档；
- 重要 ADR/技术决策文档。

### 已核实文档漂移

当前 `docs/current-task.md` 仍写：

```text
AI V1 BACKEND RELEASE CANDIDATE READY
pending USER commit
```

这已经与当前 Final Backend 大开发状态不一致，最终必须修正。

部署/运行文档至少记录：

- OCR 安装/命令/model/timeout；
- AI runtime/BYOK；
- DB migration；
- async workers；
- storage/temp；
- Docker/Compose；
- start/stop/restart；
- health/log；
- 回滚/恢复；
- secrets 不写入文档。

重要技术决策必须记录：

- revision model；
- DB-backed job claim/recovery；
- WebP decoder；
- OCR adapter；
- MySQL Chinese full-text；
- SM-2 final policy；
- subjective grading result semantics；
- SystemConfig source-of-truth；
- AI generation structured output/provenance。

---

# 17. 任务依赖与并行安排

开发人员开始时先输出实际执行拆分，然后立即开始，不等待用户再次确认（除非遇到真实业务决策/权限/不可逆风险）。

推荐依赖：

```text
第2 Database/Schema
↓
第3 Security/DTO boundary
↓
第4 SourceAsset contract
↓
第5 Ingestion/Revision/OCR
↓
第6 Knowledge/Provenance/Search
↓
第7 Practice/Review
↓
第8 Exam/Statistics
↓
第9 Mastery/StudyPlan
↓
第10 AI
↓
第11 Admin/SystemConfig
↓
第12 OpenAPI/Client/Ops
↓
第13 Feature coverage audit
↓
第14 Automated verification
↓
第15 Runtime acceptance
↓
第16 Docs/final report
```

安全并行建议：

- 第 5 大点 Ingestion 主链稳定 schema 后，第 7/8 大点的 Learning Engine 修正可由无文件冲突的执行者并行；
- 第 9 Mastery/Plan 依赖 Review/Exam evidence contract，晚于第 7/8；
- 第 10 AI Knowledge/Question generation 依赖第 5/6 的 published revision/provenance，不能提前封口；
- 第 11 Admin 可以与第 10 后半并行，但写操作必须复用已经稳定的 domain service；
- 第 12 API/client 必须等 endpoint 基本冻结后统一做。

若当前工具/开发环境不支持真实并行，必须说明“串行执行”，不得把串行描述为并行。

---

# 18. 每个小任务的进度汇报格式

每完成一个小任务立即汇报：

```text
任务编号 / 名称：
状态：IMPLEMENTED / BLOCKED / VERIFIED-LATER
完成内容：
主要修改文件：
数据库变更：
静态/编译检查：
本阶段未运行测试：是（集中测试阶段统一执行）
发现的新问题：
对其他模块影响：
下一任务：
并行安排：
```

长任务中途遇阻要主动汇报，不要等所有代码写完才说。

实现阶段不要把“未运行测试”写成 PASS。

---

# 19. 开发人员最终报告要求

最终必须提交完整报告：

1. 本次需求与实际实现内容；
2. 本清单全部大点/小点状态；
3. 所有主要修改文件；
4. 所有新/调整 migration 及升级路径；
5. focused tests、compile、full clean test 的真实结果；
6. Runtime E2E、OCR、StepFun env、BYOK、Docker、OpenAPI/api-client 的真实结果；
7. 新增/更新文档；
8. 技术决策及理由；
9. security/secret audit；
10. 未验证项及原因；
11. 已知问题、限制、风险；没有已知问题可写“暂无已知问题”，但不能隐藏未验证项；
12. 后续建议只能是维护/bugfix 建议，不得自行开启新后端功能开发。

---

# 20. 最终完成门禁

只有以下全部满足，才能声明完成：

```text
Final feature coverage audit = no PARTIAL/TODO/DEFERRED
Migration sequence valid
V030 → latest PASS
clean → latest PASS
Compile gate PASS
All focused batches PASS
Full clean Maven PASS (0 failures / 0 errors)
Real Chinese textbook E2E PASS
Real Chinese OCR PASS
Two-space isolation PASS
StepFun env PASS
StepFun encrypted BYOK runtime PASS
Live OpenAPI PASS
api-client generate/typecheck PASS
Docker PASS
Security/secret audit PASS
Docs consistent
No unimplemented placeholder/405/TODO in final backend feature scope
```

然后开发人员停止业务开发，等待用户完成 Git 写操作：

```text
user git add
user commit
user push
backend worktree clean
```

最终才允许写：

```text
AIStudy FINAL BACKEND
— FEATURE COMPLETE + VERIFIED + FROZEN
```

从这一刻开始，后端默认只允许：

```text
Bug fix
Security fix
Compatibility fix
Test fix
Operational defect fix
Performance defect fix（有证据）
Contract defect fix（属于已冻结功能的缺陷）
```

不允许在“bugfix”名义下新增普通产品功能、表、业务模块或下一代架构。

---

# 21. 已核实当前代码缺口索引（开发人员优先检查）

为了避免遗漏，当前静态审计已经明确确认以下点，不应再从头猜：

| 编号 | 已核实问题 | 直接相关位置 |
|---|---|---|
| C-01 | Flyway 有两个 V047 | `server/src/main/resources/db/migration/V047__*.sql` |
| C-02 | ingestion 创建 revision 传 null owner | `IngestionJobService` → `ExtractionRevisionService.create` |
| C-03 | async ingestion 缺完整 DB claim/restart recovery，且有 Thread.sleep | `IngestionJobService` |
| C-04 | retry 不是真正 stage resume | `IngestionJobService.retry/processJob` |
| C-05 | upload allowlist 没有 webp/docx | `SourceAssetService.ALLOWED_MIME_BY_EXTENSION` |
| C-06 | Image parser 只支持 PNG/JPEG，无 OCR | `ImageContentExtractionService` |
| C-07 | PDF parser 明确 No OCR | `PdfContentExtractionService` |
| C-08 | OcrEngine/Paddle adapter 未接主 ingestion | `ingestion/ocr/*` 与 extraction services |
| C-09 | RAW asset 没有完整 stream/download endpoint | `SourceAssetController` |
| C-10 | Page reorder 接收 Entity 且 update SQL 未约束 sourceId | `SourcePageController/Service/Mapper` |
| C-11 | Outline create 可忽略 path sourceId，缺 tree invariant | `SourceOutlineNodeController/Service` |
| C-12 | ContentBlock controller 只有 GET，review edit 未接通 | `ContentBlockController` |
| C-13 | Revision 没有真正拥有 Page/Block/Outline | revision + page/content/outline schema |
| C-14 | SourceVersionCompare 把 revisionId 当 assetId，outline diff=0 | `SourceVersionCompareService` |
| C-15 | FolderImport 信任 query userId，delete 是 405 placeholder | `FolderImportController` |
| C-16 | Note 全链信任 query userId | `NoteController/NoteKnowledgePointController/NoteSourceController` |
| C-17 | provenance DTO 只有 contentBlockId；Question/Note source list 只返回 blockIds | provenance controllers/DTOs |
| C-18 | Search 无 NOTE 且仍是 LIKE，不是真正中文 full-text | `SearchEntityType` + `SearchMapper.xml` |
| C-19 | Practice category/filter 字段未真正接 selection；history KP/category 参数被忽略 | `PracticeSessionService/Controller` |
| C-20 | Exam answer payload 缺 MATCHING，ORDERING 使用错误字段语义 | `ExamAttemptService.validatePayloadShape` |
| C-21 | subjective grade 普通 USER 可调用，submit 时 SHORT_ANSWER 被排除 maxScore | `ExamAttemptController/Service` |
| C-22 | subjective regrade 未完整传播 Result/Diagnosis/Mastery/Review/Plan | `ExamAttemptService.gradeAnswer` |
| C-23 | Review SM-2 rep>=3 未使用 previousInterval；存在 double apply 风险 | `ReviewSchedulePolicy` / `WrongQuestionReviewService` |
| C-24 | Mastery 读取 reviewCount 但 score/confidence 排除 review | `MasteryService` |
| C-25 | calibration 部分参数可能未真实影响 scoring | `MasteryService` + calibration services |
| C-26 | AI Usage 只覆盖部分旧调用，新 generation purpose 尚需统一 | AI services |
| C-27 | AI KP generation 直接 mapper insert，缺 exact provenance/原子 domain write | `AiKnowledgeExtractionService` |
| C-28 | AI Question generation 主要 SINGLE_CHOICE，缺 KP/source 原子关系 | `AiQuestionGenerationService` |
| C-29 | AIGenerationJob 没有完整 user create API；worker 语义/claim/safe error 有缺口 | `AIGenerationJobService/Worker` |
| C-30 | question-generation 把 revisionId 当 knowledgePointId | `AIGenerationJobWorker` |
| C-31 | `AiSourceDiagnosisService` 只是 source text extractor，不是 Exam Diagnosis Narrative | AI source package |
| C-32 | Admin Source/Ingestion/OCR governance 缺失 | `server/admin/*` |
| C-33 | SystemConfig type/schema 由客户端提供且未形成真正 typed runtime registry | `SystemConfigController/Service` |
| C-34 | 当前 `docs/current-task.md` 仍停留 AI V1 RC，文档已漂移 | `docs/current-task.md` |

开发人员完成本任务时，C-01~C-34 必须逐项给出：

```text
FIXED
或
经重新核对证明当前代码已经不存在该问题（附代码证据）
```

不得跳项。

