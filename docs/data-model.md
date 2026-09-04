# 数据模型

> 状态：**FINAL BUSINESS BASELINE / Accepted at conceptual level**
>
> 本文定义业务实体、关系、作用域、状态和建模约束。它不是最终 `V001__init.sql`；字段长度、索引细节和 MySQL DDL 在技术 Spike/正式 schema 设计时确认。
>
> 技术 Persistence：MySQL + MyBatis-Plus。所有正式 schema 变更进入版本化 `Vxxx__*.sql`。

## 1. 建模总原则

1. `LearningSpace` 是一级业务隔离边界。
2. 用户状态实体同时具备 `userId + spaceId`。
3. 内容实体至少具备 `spaceId`。
4. 原始资料、解析结果、AI 派生结果分层保存。
5. 派生知识尽可能可追溯到 ContentBlock / SourcePage。
6. M:N 使用显式关联表，不存 `xxxIds JSON` 代替关系。
7. ExamQuestion 保存不可变题目快照。
8. Mastery 表示当前状态；作答/考试/复习表保存历史事实。
9. 软删除按实体语义使用，不对所有表机械添加 `deletedAt`。
10. 绝对文件路径不进入业务数据库，只保存 `storageKey`。

## 2. 总体 ER 关系

```text
User ─────< UserRole >──── Role
  │
  └──── owns ────< LearningSpace
                      │
                      ├────< KnowledgeCategory
                      │
                      ├────< SourceDocument
                      │        ├────< SourceAsset
                      │        ├────< SourcePage
                      │        ├────< SourceOutlineNode
                      │        └────< ContentBlock
                      │
                      ├────< KnowledgePoint
                      │        └────< KnowledgePointSource >──── ContentBlock
                      │
                      ├────< Question
                      │        ├────< QuestionOption
                      │        ├────< QuestionKnowledgePoint >── KnowledgePoint
                      │        └────< QuestionSource >────────── ContentBlock
                      │
                      ├────< Exam / ExamPaper / ExamQuestion
                      │
                      └──── user learning state
                               ├─ Note
                               ├─ PracticeSession / Answer
                               ├─ WrongQuestion
                               ├─ ReviewTask / Record
                               ├─ Mastery
                               ├─ StudyPlan / Task
                               ├─ ExamAttempt / Answer / Result / Diagnosis
                               └─ AIConversation / AIUsage
```

## 3. Identity

### 3.1 User

用途：登录主体和个人学习数据 owner。

核心字段方向：

```text
id
username / email（最终登录字段 Spike 时定）
passwordHash
status
createdAt
updatedAt
```

不在业务表中保存明文密码。

### 3.2 Role

```text
id
code     USER / ADMIN
name
```

### 3.3 UserRole

显式关联表：

```text
userId
roleId
```

唯一约束：`(userId, roleId)`。

### 3.4 RefreshToken

```text
id
userId
tokenHash
expiresAt
revokedAt
rotatedFromId optional
createdAt
lastUsedAt optional
clientType optional
```

RefreshToken 不要求软删除；撤销就是业务状态。

## 4. LearningSpace

### 4.1 LearningSpace

这是最重要的业务作用域实体。

```text
id
ownerUserId
name
description
status        ACTIVE / ARCHIVED
sortOrder
createdAt
updatedAt
deletedAt optional
```

第一版每个 LearningSpace 有一个 owner。ADMIN 可基于权限管理所有空间。

未来如果需要多人共享空间，再新增 membership 模型和 ADR，不提前引入组织/租户。

### 4.2 KnowledgeCategory

表示 LearningSpace 内部用户视角的知识分类树。

```text
id
spaceId
parentId optional
name
description
sortOrder
createdAt
updatedAt
```

唯一/索引设计必须包含 `spaceId`。

## 5. Source / RAW

### 5.1 SourceDocument

逻辑资料，例如《数据库系统工程师教程》。

```text
id
spaceId
title
sourceType
status
createdByUserId
originMetadataJson optional
createdAt
updatedAt
publishedAt optional
archivedAt optional
```

`sourceType` 初始可包含：

```text
DESKTOP_UPLOAD
DESKTOP_FOLDER_IMPORT
ADMIN_UPLOAD
ADMIN_MANUAL
```

SourceDocument 不等于一个物理文件；一个 ZIP 导入可以形成一个 SourceDocument + 多个 SourceAsset/Page。

### 5.2 SourceAsset

保存实际文件对象元数据。

```text
id
spaceId
sourceDocumentId
assetRole
originalName
originalRelativePath optional
storageKey
mimeType
sizeBytes
sha256
createdAt
```

`assetRole` 示例：

```text
ORIGINAL_PACKAGE
ORIGINAL_FILE
PAGE_IMAGE
ATTACHMENT
```

存储 key 跨平台，例如：

```text
sources/<spaceId>/<documentId>/<uuid>.jpg
```

此示例是逻辑 key，不要求最终目录必须使用该格式。

## 6. SourcePage / EXTRACTED

### 6.1 SourcePage

表示可独立排序/引用的页面。

```text
id
spaceId
sourceDocumentId
sourceAssetId optional
sourcePageNumber optional       # PDF 内部 page number
pageOrder                       # 最终阅读顺序
printedPageNumber optional      # 识别到的书本页码
pageType optional               # COVER / TOC / BODY / APPENDIX / OTHER
orderConfidence optional
orderStatus                     # AUTO / NEEDS_REVIEW / CONFIRMED
extractedText optional
extractionConfidence optional
createdAt
updatedAt
```

对于 PDF，SourcePage 可以引用同一个 PDF SourceAsset + sourcePageNumber；不要求每页物理拆成独立文件。

### 6.2 SourceOutlineNode

忠实表达单个资料自己的目录。

```text
id
spaceId
sourceDocumentId
parentId optional
nodeType        BOOK / CHAPTER / SECTION / SUBSECTION / OTHER
title
numberLabel optional       # 1.1.1
sortOrder
startPageId optional
endPageId optional
createdAt
updatedAt
```

### 6.3 ContentBlock

结构化内容最小可引用单元。

```text
id
spaceId
sourceDocumentId
sourcePageId optional
sourceOutlineNodeId optional
blockType
sortOrder
normalizedText
structuredDataJson optional
locatorJson optional
createdAt
updatedAt
```

`locatorJson` 可用于保存 bbox/页内位置等非核心可变解析信息。

blockType 初始允许：

```text
HEADING
PARAGRAPH
LIST
TABLE
FIGURE
CODE
FORMULA
OTHER
```

## 7. Ingestion

### 7.1 IngestionJob

```text
id
spaceId
sourceDocumentId
stage
status
progressPercent
startedAt
finishedAt optional
retryCount
errorCode optional
errorMessage optional
createdByUserId
createdAt
updatedAt
```

Job 不是业务资料本身，属于过程状态，可按需要清理历史。

### 7.2 IngestionIssue

```text
id
spaceId
ingestionJobId
sourcePageId optional
issueType
severity
message
status       OPEN / RESOLVED / IGNORED
resolvedByUserId optional
resolvedAt optional
createdAt
```

issueType 示例：

```text
ORDER_LOW_CONFIDENCE
OCR_LOW_CONFIDENCE
OUTLINE_AMBIGUOUS
UNSUPPORTED_FILE
EXTRACTION_FAILED
```

## 8. Knowledge

### 8.1 KnowledgePoint

正式核心学习单元。

```text
id
spaceId
categoryId optional
title
summary optional
content
originType
status
difficulty optional
createdByUserId optional
createdAt
updatedAt
publishedAt optional
deletedAt optional
```

`originType`：

```text
SOURCE_DERIVED
ADMIN_CURATED
AI_DERIVED
USER_CURATED
```

注意：AI_DERIVED 与 SOURCE_DERIVED 可以同时有语义重叠。最终实现可将 `originType` 与 `generatedBy` 分开；V001 时再确定最不混乱的枚举。

### 8.2 KnowledgePointSource

KnowledgePoint 与 ContentBlock 的 M:N 来源关系。

```text
knowledgePointId
contentBlockId
relationType optional
relevanceScore optional
createdAt
```

不使用单个 `sourcePageId` 限制一个知识点只能来自一页。

### 8.3 KnowledgePointRelation [P1]

后续可表示：

```text
PREREQUISITE
RELATED
PART_OF
CONTRAST
```

第一版不为“知识图谱”提前引入 Graph DB。

## 9. Note

### 9.1 Note

```text
id
userId
spaceId
title
content
contentFormat
createdAt
updatedAt
deletedAt optional
```

### 9.2 NoteKnowledgePoint

```text
noteId
knowledgePointId
```

### 9.3 NoteSource [optional P0/P1]

```text
noteId
contentBlockId
```

## 10. Question

### 10.1 Question

```text
id
spaceId
questionType
stem
answerDataJson
explanation optional
difficulty optional
originType
status
createdByUserId optional
createdAt
updatedAt
publishedAt optional
deletedAt optional
```

题目业务内容不能因为“方便”全部塞一个不可理解大 JSON；`answerDataJson` 只用于题型差异明显的答案结构。题干、解释、状态等核心字段保持明确。

### 10.2 QuestionOption

客观题选项：

```text
id
questionId
optionKey
content
sortOrder
```

### 10.3 QuestionKnowledgePoint

```text
questionId
knowledgePointId
weight optional
```

M:N，不能在 Question 存一个 `knowledgePointId` 假设每题只考一个知识点。

### 10.4 QuestionSource

```text
questionId
contentBlockId
relationType optional
```

## 11. Practice

### 11.1 PracticeSession

```text
id
userId
spaceId
status
scopeJson optional
startedAt
finishedAt optional
createdAt
```

### 11.2 PracticeSessionQuestion

```text
id
practiceSessionId
questionId
sortOrder
questionSnapshotJson
createdAt
```

显式关系取代旧 `questionIds JSON`。

### 11.3 PracticeAnswer

```text
id
practiceSessionQuestionId
userId
spaceId
answerDataJson
isCorrect optional
score optional
submittedAt
durationMs optional
feedbackJson optional
```

## 12. WrongQuestion

```text
id
userId
spaceId
questionId
firstWrongAt
lastWrongAt
wrongCount
lastCorrectAt optional
status       ACTIVE / IMPROVING / MASTERED / DISMISSED
createdAt
updatedAt
```

唯一：`(userId, spaceId, questionId)`。

WrongQuestion 不保存单一 knowledgePointId，知识点由 QuestionKnowledgePoint 获取。

## 13. Review

### 13.1 ReviewTask

```text
id
userId
spaceId
targetType
targetId
reason
dueAt
priority
status
createdAt
updatedAt
```

`targetType` 第一版可以是：

```text
KNOWLEDGE_POINT
QUESTION
```

这是任务调度层的受控多态引用，必须由 Service 校验，不作为核心内容关系表替代品。

### 13.2 ReviewRecord

```text
id
reviewTaskId
userId
spaceId
result
score optional
completedAt
durationMs optional
notes optional
```

## 14. Exam

### 14.1 ExamBlueprint [P1 / basic P0 optional]

```text
id
spaceId
name
examType
rulesJson
timeLimitMinutes
totalScore optional
status
createdAt
updatedAt
```

复杂规则可先用受 schema 校验的 `rulesJson`，不要为了所有未来考试类型提前建几十张规则表。实际稳定后再规范化热点字段。

### 14.2 Exam

```text
id
spaceId
blueprintId optional
title
examType
description optional
timeLimitMinutes
totalScore
status       DRAFT / PUBLISHED / ARCHIVED
createdByUserId
createdAt
updatedAt
publishedAt optional
```

### 14.3 ExamPaper

```text
id
spaceId
examId
paperVersion
status
createdAt
publishedAt optional
```

### 14.4 ExamQuestion

```text
id
examPaperId
questionId
sortOrder
score
questionSnapshotJson
createdAt
```

`questionSnapshotJson` 至少包含完成历史还原所需题干、选项、必要判分信息/引用版本。敏感正确答案是否进入客户端下载由 API DTO 控制，数据库快照不等于考试期间返回答案。

### 14.5 ExamAttempt

```text
id
userId
spaceId
examId
examPaperId
status       NOT_STARTED / IN_PROGRESS / SUBMITTED / SCORED / ABORTED
startedAt
deadlineAt
submittedAt optional
createdAt
```

### 14.6 ExamAnswer

```text
id
examAttemptId
examQuestionId
answerDataJson
score optional
isCorrect optional
answeredAt optional
gradingStatus
feedback optional
```

### 14.7 ExamResult

```text
id
examAttemptId
userId
spaceId
score
maxScore
correctCount
wrongCount
unansweredCount
durationMs
createdAt
```

### 14.8 ExamDiagnosis

诊断不只保存一段 AI 文本。

建议：

```text
id
examAttemptId
userId
spaceId
summary optional
createdAt
```

以及结构化明细：

### ExamDiagnosisItem

```text
id
examDiagnosisId
dimensionType       KNOWLEDGE_POINT / CATEGORY / QUESTION_TYPE
dimensionId optional
label
score
maxScore
accuracy
evidenceCount
severity optional
recommendation optional
```

这样 Mastery/Plan 可以消费结构化数据，而不需要解析 AI 文字。

## 15. Mastery

### 15.1 Mastery

当前状态表：

```text
id
userId
spaceId
knowledgePointId
masteryScore
confidence
practiceEvidenceCount
examEvidenceCount
reviewEvidenceCount
lastEvidenceAt optional
updatedAt
createdAt
```

唯一：`(userId, spaceId, knowledgePointId)`。

`Mastery` 不宣称自己保存完整历史轨迹。

历史事实来自：

- PracticeAnswer
- WrongQuestion changes
- ReviewRecord
- ExamAnswer/Diagnosis

如算法以后需要完整事件流，再新增 `MasteryEvent`。

## 16. StudyPlan

### 16.1 StudyPlan

```text
id
userId
spaceId
name
startDate optional
endDate optional
status
createdAt
updatedAt
```

### 16.2 StudyTask

```text
id
studyPlanId
userId
spaceId
taskType       LEARN / PRACTICE / REVIEW / EXAM
targetType
targetId optional
title
reason optional
dueAt optional
priority
status         TODO / IN_PROGRESS / DONE / SKIPPED
completedAt optional
createdAt
updatedAt
```

## 17. AI

### 17.1 AIConversation

```text
id
userId
spaceId
title optional
scopeType optional
scopeId optional
createdAt
updatedAt
```

所有资料型 Retrieval 强制 space scope。

### 17.2 AIMessage

```text
id
conversationId
role
content
createdAt
```

### 17.3 AIUsageRecord

```text
id
userId optional
spaceId optional
provider
model
purpose
requestId optional
promptTokens optional
completionTokens optional
latencyMs
status
errorCode optional
createdAt
```

不保存 API Key。

### 17.4 AIGenerationJob [optional]

批量知识点/题目生成可与 IngestionJob 类似异步管理，最终是否合并为通用 Job 表在实现时决定。

## 18. Source Provenance

核心溯源路线：

```text
KnowledgePointSource / QuestionSource
        ↓
ContentBlock
        ↓
SourcePage
        ↓
SourceAsset / SourceDocument
```

UI “查看原文”必须能通过这条链路定位到原始资料。

对于 `ADMIN_CURATED` 内容：

- 有真实来源就建立 Source relation。
- 没有来源就明确显示“管理员整理”，不伪造 page/source。

## 19. Space Scope 索引原则

所有高频查询索引必须考虑 `spaceId`。

示例方向：

```text
KnowledgePoint(spaceId, status, categoryId)
Question(spaceId, status, questionType)
SourceDocument(spaceId, status)
WrongQuestion(userId, spaceId, status)
Mastery(userId, spaceId, knowledgePointId) UNIQUE
```

具体索引由真实查询和 `EXPLAIN` 决定，不在概念模型中过度优化。

## 20. 跨空间数据禁止关系

默认禁止：

- Database space 的 Question 关联 Java space 的 KnowledgePoint。
- Database ExamPaper 引用 Java Question。
- Database AI conversation 检索 Java Source。

Service 在创建关联时必须验证双方 `spaceId` 相同。

数据库层可通过冗余 `spaceId` + Service invariant 保证；如果某关系可以通过 FK 严格表达，也优先利用 FK。

## 21. 时间

API 使用 ISO-8601 UTC/带偏移时间。

MySQL 业务时间统一使用同一策略（优先 `DATETIME(3)` + 服务端 UTC 约定），正式 V001 前通过 Spike 确认 JDBC/MyBatis 序列化和时区行为。

## 22. 删除策略

需要软删除/归档的典型实体：

- LearningSpace
- SourceDocument
- KnowledgePoint
- Question
- Exam

不机械软删：

- UserRole
- QuestionKnowledgePoint
- KnowledgePointSource
- RefreshToken
- PracticeAnswer
- ExamAnswer
- immutable history rows

历史考试/作答数据原则上不因内容下架而物理消失。

## 23. P0 Schema Gate

在写 `V001__init.sql` 前必须再做一次针对本数据模型的 schema review，重点确认：

1. 主键类型。
2. enum 存储策略。
3. JSON 字段边界。
4. source/extraction versioning 第一版最小实现。
5. Exam snapshot schema。
6. Space-scoped unique/index。
7. 时间/时区。
8. 软删除与 unique 冲突处理。
9. Flyway 是否在第一个真实持久数据库前正式接入。
