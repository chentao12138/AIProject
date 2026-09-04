# 需求规格

> 状态：**FINAL BUSINESS BASELINE / Accepted**
>
> 优先级：P0 = 第一版核心闭环；P1 = 第一版稳定后优先增强；P2 = Future。

## 1. 身份与权限

### R-AUTH-001 [P0] 登录

系统必须支持 USER / ADMIN 身份登录，由 Spring Security 执行认证。

### R-AUTH-002 [P0] ADMIN 授权

`/api/v1/admin/**` 必须由服务端验证 ADMIN 权限。

### R-AUTH-003 [P0] 用户私有数据

服务端不能信任客户端传入的任意 userId 作为访问依据；用户身份从认证上下文获得。

## 2. LearningSpace

### R-SPACE-001 [P0] 创建与管理空间

用户必须能创建、重命名、归档 LearningSpace。

### R-SPACE-002 [P0] 先选空间再学习

Desktop 主学习流程必须存在明确 Current LearningSpace 上下文。

### R-SPACE-003 [P0] 强隔离

所有 space-scoped 数据必须带 `spaceId` 或通过明确关系归属于某个 LearningSpace。

### R-SPACE-004 [P0] 服务端隔离

隔离不能只靠前端过滤。Service/Repository 查询必须验证当前用户对目标 space 的访问权。

### R-SPACE-005 [P0] AI 隔离

AI 检索、上下文构建和题目生成默认只能读取当前 LearningSpace 数据。

### R-SPACE-006 [P0] 考试隔离

Exam/组卷不得从其他 LearningSpace 抽题。

## 3. 原始资料与 Source

### R-SOURCE-001 [P0] 统一 Source 模型

Desktop 上传、Admin 上传、Admin 手工录入等入口必须进入统一 Source 体系。

### R-SOURCE-002 [P0] 原始文件保留

系统必须保留 RAW 原始文件，不允许 OCR/AI 结果覆盖原文件。

### R-SOURCE-003 [P0] 文件元数据

保存原始文件名、MIME、大小、sha256、storageKey 等必要信息。

### R-SOURCE-004 [P0] V1 文件类型

至少支持：ZIP、PDF、JPG、PNG、WebP、Markdown、TXT。

### R-SOURCE-005 [P1] DOCX

稳定后增加 DOCX。

### R-SOURCE-006 [P1] 文件夹导入

Desktop 支持选中一个本地目录并批量导入；持续文件夹监视/同步可后置。

### R-SOURCE-007 [P0] 客户端路径不可作为后端事实

正式上传 API 必须传文件内容/流，后端不能依赖 `D:\...` 客户端绝对路径。

## 4. Ingestion / Extraction

### R-INGEST-001 [P0] 异步任务

较长导入流程必须形成可查询状态的 IngestionJob，而不是一个 HTTP 请求阻塞到全部 AI/OCR 完成。

### R-INGEST-002 [P0] 状态

至少支持：`IMPORTING / EXTRACTING / STRUCTURING / NEEDS_REVIEW / PUBLISHED / FAILED`。

### R-INGEST-003 [P0] 页面模型

图片教材/PDF 必须形成可排序 SourcePage。

### R-INGEST-004 [P0] 页面顺序

排序不能只依赖文件名；必须允许综合证据产生建议顺序，并保存人工修正后的最终顺序。

### R-INGEST-005 [P0] 低置信度问题

页面排序、OCR、目录识别等低置信度问题必须生成可审查 Issue。

### R-INGEST-006 [P0] OCR / 文本提取

图片必须支持 OCR；PDF/Markdown/TXT 使用适合格式的解析器。OCR 技术实现由 Spike 决定。

### R-INGEST-007 [P0] 目录识别

系统能够保存 Source 自身章节/节/子节层级。

### R-INGEST-008 [P0] ContentBlock

结构化正文至少能表达 HEADING、PARAGRAPH、LIST、TABLE、FIGURE、CODE、FORMULA 等类型中的实际需要子集。

### R-INGEST-009 [P0] Review/Publish

自动解析结果在正式进入学习库前必须存在审核/发布状态。

### R-INGEST-010 [P0] 可重试

单个解析步骤失败不应要求重新上传全部 RAW；允许针对阶段重试。

## 5. Knowledge / Content

### R-KNOW-001 [P0] KnowledgePoint

KnowledgePoint 是正式核心学习单元，必须属于一个 LearningSpace。

### R-KNOW-002 [P0] 来源追溯

从资料提取的 KnowledgePoint 必须能够关联到一个或多个 ContentBlock/SourcePage。

### R-KNOW-003 [P0] 管理员整理

管理员可以直接创建 `ADMIN_CURATED` KnowledgePoint；无来源时不得伪造 SourceReference。

### R-KNOW-004 [P0] 分类

LearningSpace 内支持层级 KnowledgeCategory，KnowledgePoint 可挂在分类中。

### R-KNOW-005 [P1] 知识关系

后续可增加 prerequisite / related / parent-child 等 KnowledgePointRelation。

## 6. Note

### R-NOTE-001 [P0] 笔记

用户可在当前 LearningSpace 创建笔记。

### R-NOTE-002 [P0] 关联知识

笔记可关联 KnowledgePoint 和 SourceReference。

### R-NOTE-003 [P1] 格式

Markdown/富文本的最终编辑格式在 UI Spike 后确定；当前不将 Markdown 强制为唯一业务格式。

## 7. AI 辅导

### R-AI-001 [P0] Provider 抽象

AI 必须通过后端 Provider abstraction 使用。

### R-AI-002 [P0] Space Scoped Retrieval

资料型问答默认仅检索当前 LearningSpace 的 PUBLISHED 数据。

### R-AI-003 [P0] 来源展示

基于资料生成的回答应尽可能返回 SourceReference。

### R-AI-004 [P0] 来源类型区分

AI 需要能够区分“资料内依据”和“模型一般知识/无来源补充”。

### R-AI-005 [P0] 派生内容审核

AI 批量生成正式 KnowledgePoint / Question 时，默认进入 DRAFT/NEEDS_REVIEW，而不是直接发布。

### R-AI-006 [P0] 调用记录

至少保留 Provider、Model、用途、状态、token/usage（如果 Provider 返回）、耗时和错误分类，禁止记录敏感 Key。

## 8. Question

### R-Q-001 [P0] 题库

Question 必须属于一个 LearningSpace。

### R-Q-002 [P0] 题型

第一版至少支持能够满足真实样本的客观题；主观题模型保留扩展能力。

### R-Q-003 [P0] 知识关联

Question 与 KnowledgePoint 为多对多。

### R-Q-004 [P0] 来源

基于资料产生的 Question 应保存 SourceReference。

### R-Q-005 [P0] 管理员维护

Admin 可以手工新建、编辑、审核、发布、下架题目。

## 9. Practice

### R-PRACTICE-001 [P0] Practice Session

用户可以按知识分类、知识点、难度等条件开始 Practice。

### R-PRACTICE-002 [P0] 即时反馈

Practice 可以逐题显示正确性和解析。

### R-PRACTICE-003 [P0] AI 讲解

答错后可请求 AI 解释/变式题。

### R-PRACTICE-004 [P0] 历史证据

每次作答必须保存，用于 WrongQuestion / Mastery / Statistics。

## 10. WrongQuestion

### R-WRONG-001 [P0] 用户题目状态

WrongQuestion 是用户对某 Question 的学习状态，不复制 Question 正文。

### R-WRONG-002 [P0] 错误统计

至少保存首次错误、最近错误、错误次数、当前状态。

### R-WRONG-003 [P0] 与知识点联动

通过 QuestionKnowledgePoint 识别相关薄弱知识。

## 11. Review

### R-REVIEW-001 [P0] ReviewTask

系统必须能创建到期复习任务。

### R-REVIEW-002 [P0] 触发来源

至少允许错题、考试薄弱点、Mastery、用户手动等触发 ReviewTask。

### R-REVIEW-003 [P0] 历史

完成复习产生 ReviewRecord，作为 Mastery 新证据。

### R-REVIEW-004 [P1] 调度算法

第一版不强制 SM-2；算法通过实际数据后优化。

## 12. Exam

### R-EXAM-001 [P0] Exam 独立于 Practice

考试必须是一级核心流程，不得仅用 Practice `mode=exam` 代替全部模型。

### R-EXAM-002 [P0] 考试类型

支持章节测试、专项测试、阶段考试、模拟考试、自定义考试中的第一版必要子集。

### R-EXAM-003 [P0] ExamPaper

每次正式考试使用明确的 ExamPaper。

### R-EXAM-004 [P0] ExamQuestion Snapshot

ExamQuestion 必须保存顺序、分值和足够的题目快照，历史试卷不能随 Question 编辑变化。

### R-EXAM-005 [P0] 计时

支持开始时间、截止时间/时长和自动交卷。

### R-EXAM-006 [P0] 考试隔离

考试期间默认不给答案和 AI 解题提示。

### R-EXAM-007 [P0] 评分

客观题可自动评分；主观题后续支持人工/AI 辅助评分，但最终规则由服务端控制。

### R-EXAM-008 [P0] Result

考试完成生成总分、正确率和逐题结果。

### R-EXAM-009 [P0] Diagnosis

考试后按 KnowledgePoint/KnowledgeCategory 等维度生成能力诊断。

### R-EXAM-010 [P0] 反馈学习状态

ExamDiagnosis 必须能参与 Mastery、ReviewTask 和 StudyPlan 调整。

### R-EXAM-011 [P1] ExamBlueprint

支持题型、题数、分值、知识范围和难度分布等组卷蓝图。

## 13. Mastery

### R-MASTERY-001 [P0] 当前状态

每个 user + space + knowledgePoint 维护当前 Mastery。

### R-MASTERY-002 [P0] 多证据

Practice、WrongQuestion、Review、Exam 均可影响 Mastery。

### R-MASTERY-003 [P0] 可解释

第一版 Mastery 不能只存一个无法解释的 AI 分数；至少保留足够证据统计。

### R-MASTERY-004 [P1] 算法升级

Mastery 算法允许以后升级，不把第一版公式写死到客户端。

## 14. StudyPlan

### R-PLAN-001 [P0] 计划

每个 LearningSpace 可形成用户 StudyPlan。

### R-PLAN-002 [P0] Task Type

至少支持 LEARN / PRACTICE / REVIEW / EXAM。

### R-PLAN-003 [P0] 用户控制

用户可以完成、跳过、调整任务；AI 计划只是建议。

### R-PLAN-004 [P0] 状态反馈

Mastery、Review、ExamDiagnosis 可改变后续任务优先级。

## 15. Search

### R-SEARCH-001 [P1]

在当前 LearningSpace 内统一搜索 Source/Content、KnowledgePoint、Question、Note。

### R-SEARCH-002 [P1]

搜索结果应尽可能支持回到原始来源。

### R-SEARCH-003 [P1]

MySQL 中文搜索能力必须用真实中文数据验证后再确定最终实现。

## 16. Admin Web

### R-ADMIN-001 [P0]

Admin 管理用户和 LearningSpace。

### R-ADMIN-002 [P0]

Admin 管理 Source/Ingestion/OCR/页面顺序/目录结构。

### R-ADMIN-003 [P0]

Admin 管理 KnowledgePoint/Question。

### R-ADMIN-004 [P0]

Admin 管理 Exam/ExamBlueprint。

### R-ADMIN-005 [P0]

Admin 查看 AI/Ingestion Job 的状态、失败原因和重试结果。

### R-ADMIN-006 [P0]

Admin 直接编辑不等于绕过业务规则；所有写操作仍走 Spring Boot API。

## 17. 数据与安全非功能需求

### NFR-DATA-001 来源不可破坏

RAW 文件不可因为重新 OCR/AI 处理而被覆盖。

### NFR-DATA-002 幂等/去重

上传时保存 sha256；相同文件的去重策略可以按空间/资料定义，但不得仅凭文件名判断相同内容。

### NFR-DATA-003 跨平台路径

数据库保存 storageKey，不保存 Windows 绝对路径。

### NFR-SEC-001 Space Authorization

所有 space-scoped Endpoint 必须在服务端进行访问验证。

### NFR-SEC-002 文件安全

上传限制大小/MIME，存储文件名由服务端生成，防止 path traversal。

### NFR-SEC-003 Secrets

AI Key、数据库密码、JWT signing key 不进入前端 bundle 和 Git。

### NFR-TEST-001 测试数据库

自动测试禁止访问真实业务 MySQL 数据库。

### NFR-TRACE-001 可追溯

核心派生数据应保存 originType、source relation 或生成任务信息，以支持回溯。

### NFR-OBS-001 任务可观测

Ingestion/AI 长任务必须有 status、progress、error、request/job id。

## 18. 第一版验收场景

必须用至少两个 LearningSpace 做隔离验证，并使用《数据库系统工程师教程》作为第一套真实 Source ingestion 数据集完成：

```text
ZIP
→ RAW
→ Page ordering
→ OCR
→ Outline/Content
→ Review/Publish
→ KnowledgePoint
→ Question
→ Practice/Wrong
→ Review
→ Exam/Diagnosis
→ Mastery
→ StudyPlan
```
