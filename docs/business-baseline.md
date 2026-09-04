# 业务基线（Business Baseline）

> 状态：**FINAL BUSINESS BASELINE / Accepted**
>
> 本文是当前产品业务定义的总入口。技术 ADR 仍以 `decisions.md` 为唯一 Source of Truth；本文用于回答“这个产品到底做什么、核心业务对象是什么、业务闭环是什么”。

## 1. 一句话产品定位

本产品是一套 **个人 AI 学习工作台（AI Study Workspace）**：用户把教材、PDF、图片、ZIP、Markdown、TXT、管理员录入内容等真实学习资料导入某个独立的学习空间，系统完成资料保存、解析/OCR、结构化、知识点提取、审核发布，并围绕这些知识形成学习、AI 辅导、笔记、练习、错题、复习、考试、掌握度、学习计划与能力诊断闭环。

## 2. 核心业务边界

### 2.1 LearningSpace 是一级隔离边界

UI 可以称为“学习空间”或“学习分类”，代码和数据模型统一使用 `LearningSpace`。

示例：

```text
我的学习
├── 数据库管理
├── Java 学习
├── 软件设计师
└── 其他空间
```

用户进入系统后先选择 LearningSpace。此后默认所有学习数据均限制在当前空间内：

- Source / SourceDocument
- Content / Outline
- KnowledgePoint
- Note
- Question
- Practice
- WrongQuestion
- Review
- Exam
- Mastery
- StudyPlan
- Statistics
- AI Conversation / Retrieval

`LearningSpace` 不是普通 tag。它是服务端、数据库查询、AI 检索、考试组卷、统计与权限控制共同遵守的一级数据作用域。

### 2.2 空间内部还有目录/分类

LearningSpace 解决“大领域隔离”，空间内部仍允许使用层级结构组织内容。

例如：

```text
LearningSpace：数据库管理
├── 数据库基础
├── SQL
├── 关系模型
├── 数据库设计
├── 事务与并发
└── 性能优化
```

需要区分两种层级：

1. `SourceOutlineNode`：忠实表示某一本书/PDF/资料自身的目录结构。
2. `KnowledgeCategory`：用户或管理员在 LearningSpace 内维护的学习分类结构。

两者不能混为一个概念，因为多个资料可以映射到同一个学习分类。

## 3. 数据来源

V1 正式支持的数据来源方向：

- Desktop 本地文件选择/拖放
- Desktop 本地文件夹导入
- ZIP
- PDF
- JPG / PNG / WebP 图片
- Markdown
- TXT
- Admin Web 文件上传
- Admin Web 手工录入原始资料
- Admin Web 直接维护结构化知识点/题目

后续可扩展：

- DOCX
- 网页
- 剪贴板
- Android 拍照
- 音频/视频字幕

所有来源最终进入同一套 Source / Content / Derived 数据体系，不为不同入口复制一套业务模型。

## 4. 内容摄取流水线

所有“原始资料”默认经过：

```text
IMPORTING
   ↓
PARSING / EXTRACTING
   ↓
STRUCTURING
   ↓
AI_PROCESSING（可选/按任务）
   ↓
NEEDS_REVIEW
   ↓
PUBLISHED
```

完整数据层次：

```text
RAW
原始文件 / 原始图片 / 原始 ZIP / 原始 PDF
   ↓
EXTRACTED
OCR 文本 / PDF 文本 / 页面 / 内容块 / 目录结构
   ↓
DERIVED
知识点 / 题目 / 总结 / AI 解释 / 诊断等派生数据
```

核心原则：

- RAW 原始资料保留，不因 OCR/AI 结果而被覆盖。
- EXTRACTED 与 DERIVED 可重新生成。
- AI 生成内容不能伪装成原始资料。
- 正式发布的知识点、题目等必须能追溯来源；管理员人工整理的内容必须明确标记 `ADMIN_CURATED`。
- 低置信度页面顺序、OCR、目录识别等必须进入 Review，而不是静默发布。

## 5. 数据录入方式

### 5.1 Desktop 导入

Desktop 负责选择用户本机文件、文件夹、ZIP/PDF/图片等，并将文件内容上传/流式发送给 Spring Boot。

正式协议不得依赖：

```text
D:\xxx\xxx.pdf
```

这样的客户端绝对路径，因为未来 Spring Boot 会部署到服务器。

正确模型：

```text
Desktop 读取本地文件
        ↓
HTTP multipart / upload stream
        ↓
Spring Boot
        ↓
StorageService
```

本地开发时只是上传到 `127.0.0.1`，未来自然切换到公网 API。

### 5.2 Admin Web 录入

Admin Web 是内容生产和治理后台，而不只是用户列表页面。

管理员可：

- 创建/管理 LearningSpace
- 上传 ZIP/PDF/图片/文本资料
- 手工创建 SourceDocument
- 编辑 OCR/解析文本
- 调整页面顺序
- 修正目录结构
- 审核/发布知识点
- 直接创建 `ADMIN_CURATED` 知识点
- 创建/维护题目
- 创建/维护考试与组卷规则
- 查看 AI/解析任务状态和异常

管理员手工创建的结构化内容必须记录来源类型，不伪造教材引用。

## 6. 第一套真实摄取验收样本

第一套真实测试资料：`数据库系统工程师教程.zip`。

当前样本特征：

- 含“目录”和“第一章”等文件夹结构。
- 目录图片可识别章节与层级标题。
- 正文是拍照图片。
- 文件名同时存在数字命名和 hash 风格命名。
- 不能仅通过文件名排序恢复全部页面顺序。

因此 V1 摄取链路必须支持：

1. ZIP 展开与原始包保留。
2. 图片作为 SourcePage。
3. 数字文件名/目录/印刷页码/文本连续性等多证据排序。
4. 低置信度页面人工拖拽排序。
5. OCR。
6. 目录/标题层级识别。
7. ContentBlock 结构化。
8. KnowledgePoint 提取。
9. 来源引用。
10. Review / Publish。

## 7. 正式学习闭环

```text
资料导入
  ↓
内容结构化
  ↓
知识点体系
  ↓
学习 + AI辅导 + 笔记
  ↓
练习
  ↓
错题分析
  ↓
复习
  ↓
考试
  ↓
成绩 + 能力诊断
  ↓
更新掌握度
  ↓
调整学习计划
  ↓
下一轮学习
```

定义：

- `Practice`：训练，目标是“学会”。可以即时反馈、AI 讲解、变式题。
- `Exam`：测量，目标是“真实检验”。考试期间默认不给答案/AI 提示，交卷后统一评分与诊断。
- `Mastery`：系统对用户在某个知识点上的当前能力状态估计。
- `Review`：根据错误、遗忘风险、掌握度等安排再学习。
- `StudyPlan`：决定下一阶段/今天优先学、练、复习、考什么。

## 8. KnowledgePoint

`KnowledgePoint` 正式成为核心学习单元，但不是原始数据，也不是整个系统唯一的数据中心。

正确关系：

```text
LearningSpace
   ↓
Source / Content
   ↓
KnowledgePoint
   ↓
Practice / Question / Review / Exam / Mastery / Plan
```

一个 KnowledgePoint：

- 必须属于一个 LearningSpace。
- 可以挂在一个 KnowledgeCategory 下。
- 可以来自多个 ContentBlock / SourcePage。
- 可以由管理员直接创建，此时 `originType = ADMIN_CURATED`。
- 可以关联多个 Question。
- 可以积累用户 Mastery / Review 状态。

## 9. AI 的业务职责

AI 是增强器，不是数据库事实来源。

AI 可用于：

- OCR 后的结构理解和标题识别
- 内容摘要
- 知识点提取
- 知识解释
- 基于资料的问答
- 题目生成和变式题
- 主观题辅助评分
- 错因分析
- 考试诊断
- 学习计划建议

对于“基于已导入资料”的 AI 回答：

- 检索必须限制在当前 LearningSpace。
- 尽可能提供具体来源。
- 用户应能从回答回到原资料/原页面。
- 若模型使用了资料之外的一般知识，UI/数据应允许与“资料内依据”区分。

## 10. 题库、练习与错题

`Question` 是可复用题库实体，属于一个 LearningSpace，可关联多个 KnowledgePoint 与 SourceReference。

Practice：

- 可按知识点/分类/难度选择题目。
- 可立即展示答案和解析。
- 可触发 AI 辅导。
- 记录每次作答。

WrongQuestion：

- 是用户对某题的学习状态，而不是复制一份 Question。
- 记录首次/最近错误、错误次数、状态等。
- 通过 Question 的知识点关系定位薄弱知识。

## 11. Exam 是一级核心业务

考试不能作为 Practice 的一个模式简单替代。

正式模型包含：

- `ExamBlueprint`：组卷规则/考试蓝图。
- `Exam`：一次考试定义。
- `ExamPaper`：某次生成/发布的实际试卷。
- `ExamQuestion`：试卷题目、顺序、分值和题目快照。
- `ExamAttempt`：用户一次实际考试过程。
- `ExamAnswer`：逐题作答。
- `ExamResult`：总成绩与基础统计。
- `ExamDiagnosis`：按知识点/分类/题型的能力诊断。

考试题必须属于同一个 LearningSpace。历史 ExamQuestion 保存题目快照，避免题库后续修改导致历史试卷变化。

## 12. Mastery、Review 与 StudyPlan

### Mastery

每个用户在每个 LearningSpace 的每个 KnowledgePoint 上维护当前 Mastery 状态。

Mastery 由多种证据更新：

- Practice Answer
- WrongQuestion
- Review 结果
- Exam Answer / Exam Diagnosis
- 后续可能包括人工自评

第一版不锁死具体数学模型。

### Review

ReviewTask 可由：

- 错题
- 掌握度下降
- 长时间未接触
- 学习计划
- 用户手动添加

触发。

当前不锁死 SM-2；算法在真实数据验证后再定。

### StudyPlan

StudyPlan 用于安排：

- LEARN
- PRACTICE
- REVIEW
- EXAM

任务。

第一版先做可解释、可调整的规则，不追求一开始就做复杂 AI 调度器。

## 13. Search

搜索默认必须带 LearningSpace 作用域。

目标搜索对象：

- Source/Content
- KnowledgePoint
- Question
- Note

第一版优先验证 MySQL 的实际中文搜索能力。只有明确不满足后再决定专用搜索服务。

## 14. 客户端职责

### Desktop

主要面向学习者：

- 选择/切换 LearningSpace
- 导入本机学习资料
- Review 自己导入资料的解析结果
- 学习资料/知识点
- AI 辅导
- 笔记
- Practice
- WrongQuestion
- Review
- Exam
- StudyPlan
- Statistics

### Admin Web

主要面向内容生产/治理和系统管理：

- User / Role
- LearningSpace
- Source / Ingestion
- OCR/结构审核
- KnowledgePoint
- Question
- Exam
- AI Job
- System Config

### Future Android

后续优先复用学习端能力：

- 学习
- 复习
- 练习
- 考试
- AI 辅导
- 学习计划
- 手机拍照导入（后续）

不在当前阶段锁定 Android 技术栈。

## 15. V1 不做

当前不把以下内容塞入第一版：

- 微服务
- 多租户/企业组织
- 社交/社区
- 多人实时协作
- 公开内容市场
- 支付系统
- 复杂推荐商业化
- 强制云对象存储
- Elasticsearch/OpenSearch 起步
- Android 正式开发
- 音视频完整解析
- 自研数据库 migration framework

## 16. V1 成功标准

V1 至少能够完成一条真实端到端链路：

```text
创建“数据库管理” LearningSpace
→ 导入《数据库系统工程师教程》样本
→ 保留 RAW 图片
→ 页面排序/人工修正
→ OCR
→ 识别目录与第一章内容
→ 审核并发布
→ 形成可追溯 KnowledgePoint
→ 生成/维护 Question
→ 完成 Practice
→ 产生 WrongQuestion / Review
→ 完成一次 Exam
→ 产生成绩和知识点诊断
→ 更新 Mastery
→ 生成下一步 StudyPlan
```

同时再创建“Java 学习”空间，证明两者资料、AI 检索、题库、考试、错题、掌握度和计划互不串数据。
