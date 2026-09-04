# Current Task

> 状态：**BUSINESS-ALIGNMENT COMPLETE / WAITING USER BASELINE APPROVAL**

## 已完成

技术基线已确定：

```text
Electron Desktop + React
Admin Web + React/Vite
Future Android
        ↓
Spring Boot 3.5.x / Java 21
Modular Monolith
        ↓
MyBatis-Plus + MySQL
StorageService + AI Provider abstraction
```

核心业务已确定：

```text
LearningSpace
→ Source / Ingestion
→ Content / KnowledgePoint
→ Learn / AI / Note
→ Practice / WrongQuestion
→ Review
→ Exam / Diagnosis
→ Mastery
→ StudyPlan
```

Admin Web 正式定位为内容生产/审核/治理后台。

第一套真实摄取样本：`数据库系统工程师教程.zip`。

## 当前唯一任务

**不要写代码。不要修改文档。**

Hermes 只需要完整读取当前 README 和 docs，确认理解：

1. LearningSpace 隔离。
2. RAW / EXTRACTED / DERIVED。
3. Source ingestion + provenance。
4. KnowledgePoint。
5. Practice 与 Exam 分离。
6. Mastery / Review / StudyPlan。
7. Admin 内容治理。
8. Desktop 上传文件 bytes/stream，不把 D:\ path 当 Backend 协议。
9. Spring Boot 是唯一业务权威入口。
10. AI Retrieval 必须 space-scoped 并支持 citation。

然后停止，等待用户进行最终文档 Review / Git baseline。

## 用户确认之后

顺序固定：

```text
用户 Git baseline + GitHub Private
→ Technical Spikes
→ Platform Skeleton
→ Source→Knowledge Vertical Slice
→ Practice
→ Review/Mastery/Plan
→ Exam
→ AI/Admin polish
```

## 当前禁止

- 不开始 Spike。
- 不生成 Spring Boot/React/Electron 工程。
- 不创建真实 MySQL 业务 schema。
- 不 npm install。
- 不启动长期 MySQL 数据库。
- 不 commit/push。
- 不重新设计已经 Accepted 的业务。
