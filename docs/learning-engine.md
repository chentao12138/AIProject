# 学习引擎设计

> 状态：**FINAL BUSINESS BASELINE / Accepted**
>
> 本文定义 KnowledgePoint 之后的学习闭环：学习、题库、Practice、WrongQuestion、Review、Exam、Mastery、StudyPlan。

## 1. 核心闭环

```text
KnowledgePoint
   ↓
学习 / AI辅导 / Note
   ↓
Practice
   ↓
WrongQuestion
   ↓
Review
   ↓
Exam
   ↓
ExamDiagnosis
   ↓
Mastery
   ↓
StudyPlan
   └────────────→ 下一轮学习
```

实际运行不是严格单向流水线；Practice/Exam/Review 都可以直接产生 Mastery evidence，并影响 StudyPlan。

## 2. KnowledgePoint 是学习单位

KnowledgePoint 必须：

- 属于 LearningSpace。
- 可属于 KnowledgeCategory。
- 可关联多个来源。
- 可关联多个 Question。
- 有发布状态。

KnowledgePoint 不保存“用户是否掌握”；用户状态放 Mastery。

## 3. Question

Question 是内容层题库资源，不是某个用户的一次答题记录。

核心关系：

```text
Question M:N KnowledgePoint
Question M:N ContentBlock/Source via QuestionSource
```

Question 可来自：

- ADMIN_CURATED
- SOURCE_DERIVED
- AI_DERIVED

AI 生成题默认待审核。

## 4. Practice

Practice 目标是训练。

### PracticeSession

记录：

- userId
- spaceId
- scope/filter
- startedAt / finishedAt
- status

### PracticeSessionQuestion

显式关联表，不使用 `questionIds JSON`。

至少：

- sessionId
- questionId
- sortOrder
- questionSnapshot（至少保存作答时必要内容）

### PracticeAnswer

保存：

- answer
- correctness/score
- submittedAt
- duration
- optional feedback

Practice 可以立即显示答案、解析和 AI 辅导。

## 5. WrongQuestion

WrongQuestion 是用户级派生状态：

```text
(userId, spaceId, questionId) unique
```

至少：

- firstWrongAt
- lastWrongAt
- wrongCount
- lastCorrectAt
- status

Question 的知识点关系用于推导薄弱点，不再在 WrongQuestion 中塞单个 knowledgePointId。

## 6. Review

### ReviewTask

表示“未来要做的一次复习任务”。

可关联：

- KnowledgePoint
- Question/WrongQuestion
- Source Content

触发原因：

- WRONG_ANSWER
- LOW_MASTERY
- EXAM_DIAGNOSIS
- MANUAL
- SCHEDULED

### ReviewRecord

完成一次复习后保存结果，为 Mastery 提供证据。

当前不锁死 SM-2。ReviewPolicy 由服务端实现并允许以后更换。

## 7. Exam 与 Practice 的边界

Practice：

- 可即时反馈。
- 可调用 AI 辅导。
- 允许边做边学。

Exam：

- 固定开始/结束。
- 具备时间限制。
- 试卷明确。
- 考试中默认不给答案和 AI 解题提示。
- 交卷后统一评分和诊断。

因此两者共享 Question，但 Session/Attempt 模型独立。

## 8. Exam 模型

### ExamBlueprint

定义组卷规则，P1 可增强。

可表达：

- 题型
- 题数
- 总分
- 时间
- 知识范围
- 难度分布

### Exam

表示一个可参加的考试定义：

- spaceId
- title
- type
- timeLimit
- totalScore
- status
- blueprintId optional

### ExamPaper

表示一份实际试卷，可由管理员手工创建或根据 Blueprint 生成。

### ExamQuestion

显式关联：

- examPaperId
- questionId
- sortOrder
- score
- questionSnapshot

历史 Paper 不能随 Question 编辑变化。

### ExamAttempt

用户的一次考试：

- userId
- spaceId
- examPaperId
- startedAt
- deadlineAt
- submittedAt
- status

### ExamAnswer

逐题作答。

### ExamResult

保存：

- score
- maxScore
- correctCount
- wrongCount
- duration

### ExamDiagnosis

诊断可以按：

- KnowledgePoint
- KnowledgeCategory
- QuestionType
- Difficulty

输出，并为 Mastery/Review/Plan 产生结构化证据。

## 9. Mastery

Mastery 是“当前状态”，不是完整历史。

建议唯一键：

```text
(userId, spaceId, knowledgePointId)
```

至少保存：

- masteryScore
- confidence
- practiceEvidenceCount
- examEvidenceCount
- reviewEvidenceCount
- lastEvidenceAt
- updatedAt

历史事实来自 PracticeAnswer / ExamAnswer / ReviewRecord 等；如以后需要单独算法事件流，可增加 MasteryEvent。

第一版不要用一个 AI 主观分数直接覆盖 Mastery。

## 10. Mastery 更新原则

MasteryService 接收结构化 Evidence：

```text
PracticeAnswer
ExamAnswer / Diagnosis
ReviewRecord
Manual override（future）
```

算法由服务端控制。

第一版可采用简单可解释权重，后续通过真实学习数据调优。

## 11. StudyPlan

### StudyPlan

用户在一个 LearningSpace 的阶段计划。

### StudyTask

任务类型：

- LEARN
- PRACTICE
- REVIEW
- EXAM

字段方向：

- planId
- taskType
- targetType / targetId
- dueAt
- priority
- status
- reason

允许用户调整、跳过、完成。

AI 可以提出建议，但服务端规则和用户选择决定最终任务。

## 12. 统计

第一版至少支持：

- 学习时长
- Practice 正确率
- WrongQuestion 数量/趋势
- Review 完成率
- Exam 成绩历史
- KnowledgePoint Mastery 分布
- LearningSpace 总览

跨 LearningSpace 统计只能在专门全局页面做，默认空间 Dashboard 不混合其他空间。

## 13. 学习状态隔离

以下用户状态必须带 `userId + spaceId`：

- Note
- PracticeSession/Answer
- WrongQuestion
- ReviewTask/Record
- Mastery
- StudyPlan/Task
- ExamAttempt/Answer/Result/Diagnosis
- AI Conversation

内容资源可只通过 space 归属：

- Source
- KnowledgePoint
- Question
- Exam definition/paper

## 14. AI 与考试安全边界

考试进行中：

- 默认禁用“直接给出本题答案”的 AI Tutor。
- 不能通过同一 Exam 页面偷偷调用正常 Practice 解题接口绕过规则。
- 服务器根据 ExamAttempt 状态判定可用能力。

考试结束后可以恢复：

- AI 错因解释
- 相关知识回顾
- 变式题
- 学习建议
