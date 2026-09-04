# 内容摄取与数据录入设计

> 状态：**FINAL BUSINESS BASELINE / Accepted**
>
> 本文专门定义 Source、文件导入、OCR/文本提取、页面排序、结构化、审核与发布。

## 1. 目标

将“用户已有的真实学习资料”稳定转化为可追溯的结构化内容，同时保证：

- 原始数据不丢。
- 自动处理可重跑。
- 错误不直接污染正式知识库。
- 本地开发和服务器部署使用同一上传协议。
- Desktop 与 Admin Web 使用同一后端业务模型。

## 2. 数据层次

```text
RAW
├── original ZIP
├── original PDF
├── original image
└── original text file

EXTRACTED
├── page
├── OCR/text
├── outline
└── content block

DERIVED
├── knowledge point
├── summary
├── question
└── AI generated explanation
```

RAW 是事实来源。EXTRACTED/DERIVED 是可再生结果。

## 3. 入口类型

`SourceDocument.sourceType` 建议至少表达：

- `DESKTOP_UPLOAD`
- `ADMIN_UPLOAD`
- `ADMIN_MANUAL`
- `DESKTOP_FOLDER_IMPORT`

具体文件格式通过 SourceAsset 的 MIME/extension 识别，不需要为 ZIP/PDF/JPG 再造不同业务表。

管理员直接创建 KnowledgePoint/Question 时，派生实体使用 `originType = ADMIN_CURATED`，不强制创建虚假 SourceDocument。

## 4. SourceDocument 与 SourceAsset

`SourceDocument` 表示一个逻辑资料，例如：

```text
《数据库系统工程师教程》
```

`SourceAsset` 表示实际保存的文件：

- 原始 ZIP
- PDF
- 图片
- Markdown/TXT
- ZIP 展开后的文件（如需要独立持久化）

每个 Asset 至少保留：

- originalName
- storageKey
- mimeType
- size
- sha256
- assetRole
- createdAt

不使用原文件名作为物理存储路径。

## 5. 上传协议

### Desktop

```text
File Dialog / Folder Scan
→ Desktop reads bytes
→ multipart/chunk upload
→ Spring Boot
→ StorageService
```

后端绝不接受“请读取 D:\xxx”作为主协议。

### Admin Web

浏览器通过 multipart 上传文件，或通过 JSON API 创建 ADMIN_MANUAL Source。

### 大文件

第一版可以先支持普通 multipart。超过实际阈值后再引入 chunk/resumable upload，不提前复杂化。

## 6. IngestionJob

每次需要长处理的导入建立 Job：

```text
QUEUED
→ IMPORTING
→ EXTRACTING
→ STRUCTURING
→ AI_PROCESSING
→ NEEDS_REVIEW
→ PUBLISHED
```

失败可进入：

```text
PARTIAL_FAILED
FAILED
```

Job 至少记录：

- jobId
- spaceId
- sourceDocumentId
- stage
- progress
- startedAt / finishedAt
- retryCount
- errorCode / safeErrorMessage

不要把完整 stack trace 暴露给客户端。

## 7. ZIP 导入

ZIP：

1. 原始 ZIP 先作为 RAW Asset 保存。
2. 校验压缩包安全：防 zip-slip、路径穿越、异常解压比等。
3. 生成内部 manifest。
4. 按文件夹和格式识别可能的目录图片/正文图片。
5. 展开内容作为 SourceAsset/SourcePage 输入。

目录名是辅助证据，不等于最终结构事实。

## 8. Page Ordering

对于图片教材，SourcePage 至少保存：

- `pageOrder`：系统最终阅读顺序。
- `printedPageNumber`：图片中识别出的印刷页码，可空。
- `originalRelativePath`
- `orderConfidence`
- `orderStatus`

排序证据优先级不能写死成单一算法，可综合：

- 文件夹结构
- 数字文件名
- OCR 印刷页码
- 章/节编号
- 上下页文本连续性
- 图片相似版式
- AI suggestion

系统输出“建议顺序”，不是把 AI 结果当绝对事实。

### 人工 Review

页面 UI 至少支持：

- 缩略图
- 拖拽排序
- 查看原图
- 标记封面/目录/正文/无效页
- 批量确认

低于阈值的页面必须生成 `IngestionIssue(ORDER_LOW_CONFIDENCE)`。

## 9. 文本提取

不同格式采用不同 extractor：

```text
PDF text layer → PDF extractor
Image          → OCR
Markdown/TXT   → parser
```

统一抽象概念：

```text
ContentExtractionService
```

OCR 引擎当前不锁死；使用真实中文教材样本做 Spike 后决定。

AI Vision 可以作为补充能力，但不应该把“AI 总结”当 OCR 原文保存。

## 10. SourceOutlineNode

SourceOutlineNode 表示某个 SourceDocument 本身的目录：

```text
Book
└── Chapter
    └── Section
        └── Subsection
```

字段方向：

- sourceDocumentId
- parentId
- nodeType
- title
- code/number（例如 1.1.1，可空）
- sortOrder
- startPage / endPage

`SourceOutlineNode` 不等于 LearningSpace 的 `KnowledgeCategory`。

## 11. ContentBlock

ContentBlock 是结构化正文最小可引用单元。

建议 blockType：

- HEADING
- PARAGRAPH
- LIST
- TABLE
- FIGURE
- CODE
- FORMULA
- OTHER

ContentBlock 关联：

- SourceDocument
- SourcePage
- SourceOutlineNode
- order
- normalizedText
- optional structuredData

需要保留足够定位信息，使 UI 可以从知识点引用返回原页。

## 12. Review / Publish

Source/派生内容采用明确发布状态：

- DRAFT
- PROCESSING
- NEEDS_REVIEW
- PUBLISHED
- ARCHIVED
- REJECTED

默认规则：AI/自动化生成的正式 KnowledgePoint/Question 先进入 DRAFT/NEEDS_REVIEW。

用户自己导入到个人 LearningSpace 时，可以由该空间 owner Review；ADMIN 有全局治理权限。

## 13. Provenance

从 Source 派生的核心实体必须保存来源关系。

推荐使用明确关联表：

- KnowledgePointSource
- QuestionSource
- NoteSource（需要时）

避免一个全局 `targetType + targetId` 多态表承担所有强关系，因为会失去外键完整性。

引用至少可定位：

```text
SourceDocument
→ SourcePage
→ ContentBlock
```

## 14. 重新处理

更换 OCR/AI 模型时：

```text
RAW 保持不变
→ 新 Extraction/Derived 版本
→ Review
→ 切换 Published version
```

禁止覆盖历史结果后导致用户无法解释“为什么知识点变了”。第一版可以只保留有限版本，但模型必须允许 future versioning。

## 15. 去重

sha256 用于识别字节级相同文件。

不能仅凭：

- 文件名
- 文件路径

判断资料相同。

第一版去重策略可限制在同一 LearningSpace，并给用户选择“跳过/作为新资料导入”。

## 16. 删除

删除 SourceDocument 时必须考虑已发布派生数据。

第一版建议：

- 删除 Source 先归档/软删除。
- 不级联物理删除 KnowledgePoint/Question。
- 显示“来源已归档/不可用”。
- 真正物理清理 storage 作为独立维护动作。

## 17. 第一套样本验收

`数据库系统工程师教程.zip` 必须能验证：

- ZIP 安全展开
- 原始 ZIP 保存
- 目录/第一章图片识别
- hash 文件名不导致错误假设
- 页面拖拽排序
- OCR
- 第1章/1.1/1.1.1 结构
- ContentBlock
- KnowledgePointSource
- 查看原图
- Review/Publish
