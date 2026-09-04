# 用户场景

> 状态：**FINAL BUSINESS BASELINE / Accepted**

## 1. 角色

### USER

普通学习者。拥有自己的 LearningSpace，使用 Desktop 为主，未来也可以使用 Android。

### ADMIN

系统管理员/内容管理员。通过 Admin Web 管理用户、学习空间、资料、知识点、题目、考试、AI/解析任务和系统配置。

第一版不引入企业组织、多租户和复杂 RBAC。

## 2. 场景 S-001：创建学习空间

用户第一次进入 Desktop：

```text
登录
→ 创建学习空间
→ 名称：数据库管理
→ 进入空间首页
```

系统创建 LearningSpace，并将当前用户设为 owner。

随后用户再创建：

```text
Java 学习
```

两个空间的数据完全隔离。

验收重点：

- 切换空间时 Dashboard、资料、知识点、题库、错题、考试、掌握度全部切换。
- 修改 API 请求中的 `spaceId` 也不能越权读取其他用户的空间。

## 3. 场景 S-002：Desktop 导入 ZIP 教材图片

用户当前位于“数据库管理”：

```text
资料
→ 导入
→ 选择 数据库系统工程师教程.zip
```

Desktop 读取文件并上传给 Spring Boot，而不是把 `D:\...` 路径交给后端。

后端：

```text
保存原始 ZIP
→ 展开文件清单
→ 建立 SourceDocument / SourceAsset / SourcePage
→ 开始解析任务
```

用户可在任务页面看到：

- 文件数量
- 当前处理阶段
- 成功/失败数量
- 低置信度问题

## 4. 场景 S-003：修正页面顺序

导入的图片既有数字文件名，也有 hash 文件名，系统不能保证排序完全正确。

系统综合：

- 文件夹结构
- 数字文件名
- 印刷页码
- 标题编号
- 文本连续性
- AI/规则推断

产生建议顺序和置信度。

用户在 Desktop 或 Admin Web 中看到缩略图列表，并可拖拽修正。

低置信度页面在发布前必须被确认或显式忽略。

## 5. 场景 S-004：OCR 与目录结构审核

系统对教材图片执行文字提取，识别：

```text
第1章 计算机系统知识
  1.1 计算机硬件基础知识
    1.1.1 中央处理单元
```

用户/管理员可以：

- 对照原始图片检查 OCR
- 修改识别文本
- 调整章节层级
- 合并/拆分 ContentBlock
- 标记处理异常

修改不会覆盖原始图片。

## 6. 场景 S-005：发布知识内容

系统从已结构化 Content 中提取候选 KnowledgePoint。

例如：

```text
CPU 的主要功能
CPU 的基本组成
```

每个候选知识点显示：

- 标题
- 内容
- 来源页/ContentBlock
- AI 置信度/来源类型

审核通过后状态变为 `PUBLISHED`，进入正式学习库。

## 7. 场景 S-006：Admin 手工录入原始资料

管理员：

```text
Admin Web
→ 数据库管理
→ 原始资料
→ 新建
```

输入：

- 标题
- 所属学习空间
- 正文
- 可选章节/分类

保存后生成 `SourceDocument(sourceType = ADMIN_MANUAL)`，仍可继续抽取知识点和题目。

## 8. 场景 S-007：Admin 直接创建知识点

管理员明确知道要录入的知识，不需要先伪造一篇 Source。

```text
知识点管理
→ 新建
→ 事务 ACID 特性
```

系统保存：

```text
originType = ADMIN_CURATED
```

如果有参考资料，可以额外建立 SourceReference；如果没有，不伪造来源页。

## 9. 场景 S-008：学习一个知识点

用户进入“数据库管理”：

```text
知识
→ 事务与并发
→ ACID
```

页面展示：

- 知识点正文
- 关联来源
- 相关笔记
- 相关题目
- 当前 Mastery
- 最近 Practice/Exam 证据

点击来源可打开对应教材/PDF/图片页面。

## 10. 场景 S-009：AI 基于当前资料辅导

用户在“数据库管理”中问：

```text
事务隔离级别为什么会产生不可重复读？
```

AI 检索只能读取当前 LearningSpace 的已发布资料/知识。

回答应尽可能展示来源，例如：

```text
来源：某教材 > 第X章 > 第X节 > P123
```

如果使用模型的一般背景知识，应允许和“资料内依据”区分。

切换到“Java 学习”后，同样问题默认不检索数据库空间资料。

## 11. 场景 S-010：做针对性练习

用户选择：

```text
Practice
→ 范围：事务与并发
→ 10题
```

Practice 允许：

- 逐题提交
- 即时对错
- 查看解析
- AI 讲解
- 基于错误生成变式题

每次作答保存证据，并更新 WrongQuestion / Mastery。

## 12. 场景 S-011：错题处理

用户答错后：

```text
Question
→ WrongQuestion state
→ 关联 KnowledgePoint
→ AI 错因讲解
→ 变式题
→ ReviewTask
```

WrongQuestion 不复制题目正文，而是记录用户对原 Question 的错误状态。

## 13. 场景 S-012：复习

用户进入：

```text
今日复习
```

系统按 ReviewTask 给出：

- 到期知识点
- 错题复习
- 长期未接触内容
- 考试暴露的薄弱点

用户完成后产生 ReviewRecord / 新的学习证据并更新 Mastery。

第一版不锁死 SM-2。

## 14. 场景 S-013：章节测试

用户选择：

```text
考试
→ 第1章测试
```

考试开始后：

- 固定计时
- 不显示答案
- 默认不提供 AI 提示
- 支持自动交卷

交卷后生成：

- 分数
- 逐题结果
- 知识点覆盖与正确率
- 薄弱项
- 推荐复习内容

## 15. 场景 S-014：模拟考试

管理员/系统维护 ExamBlueprint，例如：

- 总分
- 时长
- 题型结构
- 知识范围
- 难度分布

系统生成 ExamPaper，并将题目内容、选项、答案判定上下文等必要信息快照到 ExamQuestion。

即使题库随后被编辑，历史考试仍能准确还原。

## 16. 场景 S-015：考试驱动下一轮学习

考试完成：

```text
ExamResult
→ ExamDiagnosis
→ KnowledgePoint evidence
→ Mastery update
→ ReviewTask
→ StudyPlan adjustment
```

用户不只看到“72 分”，还看到：

- 哪些知识点弱
- 哪些内容应该重学
- 哪些错题要复习
- 下一次练习/考试建议

## 17. 场景 S-016：学习计划

系统为当前 LearningSpace 提供今日计划：

```text
LEARN    新内容
PRACTICE 专项练习
REVIEW   到期复习
EXAM     阶段检测
```

用户可以调整/跳过任务。计划规则必须可解释，不把 AI 建议当不可更改的命令。

## 18. 场景 S-017：Admin 维护题库

管理员可：

- 新建/编辑 Question
- 配置题型/答案/解析/难度
- 关联 KnowledgePoint
- 关联 SourceReference
- 审核 AI 生成题
- 下架错误题目

所有题目必须属于明确 LearningSpace。

## 19. 场景 S-018：Admin 管理考试

管理员可：

- 创建 ExamBlueprint
- 手工组卷
- AI/规则辅助组卷
- 检查知识点覆盖
- 设置时长/分值
- 发布/下架考试

## 20. 场景 S-019：搜索

用户在当前 LearningSpace 搜索：

```text
MVCC
```

结果只来自该空间，并按类型展示：

- 原始/结构化资料
- KnowledgePoint
- Question
- Note

结果可回到对应来源。

## 21. 场景 S-020：未来迁移服务器

当前 Desktop 将文件上传给 localhost Spring Boot。

未来 Backend 上服务器后，只切换 API Base URL；业务协议不因为 D 盘路径而重写。
