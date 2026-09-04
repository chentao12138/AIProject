# 功能地图

> 状态：**FINAL BUSINESS BASELINE / Accepted**

## 1. 功能域总览

```text
Identity
└── User / Role / Auth

LearningSpace
├── Space management
├── KnowledgeCategory
└── Space-scoped authorization

Content Ingestion
├── Source
├── Upload / Manual Input
├── ZIP/PDF/Image/Text
├── Page Ordering
├── OCR / Extraction
├── Outline / ContentBlock
├── Review / Publish
└── Provenance

Knowledge
├── KnowledgePoint
├── SourceReference
├── Note
└── Search

Learning Engine
├── Question Bank
├── Practice
├── WrongQuestion
├── Review
├── Exam
├── Mastery
├── StudyPlan
└── Statistics / Diagnosis

AI
├── Tutor
├── Content Structure
├── Knowledge Extraction
├── Question Generation
├── Explanation
├── Diagnosis
└── Provider / Usage

Admin
├── User
├── LearningSpace
├── Source / Ingestion
├── Knowledge
├── Question
├── Exam
├── AI Jobs
└── System Config
```

## 2. 客户端能力矩阵

| 能力 | Desktop | Admin Web | Android Future |
|---|---|---|---|
| 登录 | P0 | P0 | Future |
| 选择/切换 LearningSpace | P0 | P0 | Future |
| 创建个人 LearningSpace | P0 | 管理 | Future |
| 本地文件/文件夹选择 | P0 | - | Future 拍照/文件 |
| ZIP/PDF/图片上传 | P0 | P0 | Future |
| 手工 Source 录入 | 可选 | P0 | - |
| 页面排序 Review | P0 | P0 | Future optional |
| OCR/结构 Review | P0 | P0 | Future optional |
| KnowledgePoint 学习 | P0 | 管理 | Future |
| 笔记 | P0 | 查看/管理 | Future |
| AI 辅导 | P0 | 内容辅助 | Future |
| Question Bank | 使用 | P0 管理 | 使用 |
| Practice | P0 | - | Future |
| WrongQuestion | P0 | - | Future |
| Review | P0 | - | Future |
| Exam | P0 | P0 管理 | Future |
| Exam Diagnosis | P0 | 查看 | Future |
| Mastery | P0 | 查看 | Future |
| StudyPlan | P0 | 查看 | Future |
| Search | P1 | P1 | Future |
| AI Job/Usage 管理 | - | P0 | - |
| 系统配置 | - | P0 | - |

## 3. P0 功能

### Platform P0

- Auth / User / Role
- LearningSpace
- OpenAPI Client
- StorageService
- MySQL persistence
- Versioned SQL

### Ingestion P0

- SourceDocument / SourceAsset
- ZIP/PDF/Image/Markdown/TXT
- Upload stream
- sha256
- SourcePage
- Page ordering
- manual reorder
- OCR/text extraction
- SourceOutlineNode
- ContentBlock
- IngestionJob / Issue
- Review / Publish

### Knowledge P0

- KnowledgeCategory
- KnowledgePoint
- KnowledgePointSource
- Note
- source navigation

### AI P0

- Provider abstraction
- space-scoped retrieval
- tutor
- knowledge/question draft generation
- usage/error record

### Learning P0

- Question / QuestionOption
- QuestionKnowledgePoint / QuestionSource
- PracticeSession / attempt
- WrongQuestion
- ReviewTask / ReviewRecord
- Mastery current state
- StudyPlan / StudyTask

### Exam P0

- Exam
- ExamPaper
- ExamQuestion snapshot
- ExamAttempt
- ExamAnswer
- ExamResult
- ExamDiagnosis
- basic chapter/scope test

### Admin P0

- User / Space management
- Source / Ingestion review
- content editing
- KnowledgePoint management
- Question management
- Exam management
- Job monitoring

## 4. P1 功能

- DOCX
- Local Folder incremental sync
- KnowledgePointRelation
- more advanced Question types
- AI variant question generation workflow
- ExamBlueprint advanced rule engine
- more mature Review scheduling
- Mastery algorithm calibration
- full-text Search
- advanced statistics
- source version compare
- bulk admin operations
- richer content rendering

## 5. Future

- Android
- camera capture ingestion
- object storage
- offline sync (only if needed)
- public/shared learning spaces (only if needed)
- organizations/multi-tenant (only if needed)

## 6. 明确不做的“伪功能”

以下内容不能为了展示“功能很多”提前实现：

- 微服务管理平台
- Redis cache without evidence
- MQ without asynchronous reliability need
- Elasticsearch from day one
- Graph DB for KnowledgePoint
- AI autonomous publication without review
- client-side direct DB tools

## 7. 第一条 Vertical Slice

第一条真正业务 Vertical Slice 固定为：

```text
LearningSpace
→ ZIP/Image Source Upload
→ Page/OCR/Content
→ Review/Publish
→ KnowledgePoint with Source
→ Question
→ Practice
```

第二条补齐：

```text
WrongQuestion
→ Review
→ Mastery
→ StudyPlan
```

第三条补齐：

```text
Exam
→ Result
→ Diagnosis
→ Mastery/Plan feedback
```
