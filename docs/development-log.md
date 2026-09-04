# Development Log

> 架构决策以 `decisions.md` 为准，业务总定义以 `business-baseline.md` 为准。本文件只记录阶段事实。

## 2026-09 — Technical Baseline

完成从早期 Electron + SQLite 单机方案到正式 Client-Server 架构的收敛：

- Spring Boot 成为统一 Backend。
- MySQL + MyBatis-Plus。
- 单 Maven Project Modular Monolith。
- Electron 是客户端。
- Admin Web 独立工程。
- Future Android 复用 API。
- JWT Access + Opaque Refresh。
- StorageService + storageKey。
- OpenAPI Contract。
- Maven Wrapper 3.9.x，保留 Windows 全局 Maven 3.6.2。
- 不自研 migration engine。

## 2026-09 — BUSINESS-ALIGNMENT Complete

用户确认产品核心为个人 AI 学习工作台。

正式加入：

- LearningSpace 一级数据隔离。
- Source 真实资料作为数据源。
- Desktop 文件/文件夹/ZIP/PDF/图片导入。
- Admin Web 手工录入和内容治理。
- RAW → EXTRACTED → DERIVED 分层。
- OCR/结构化/Review/Publish。
- Source provenance / 查看原文。
- KnowledgePoint 核心学习单元。
- Note。
- Question Bank。
- Practice。
- WrongQuestion。
- Review。
- Exam 作为独立一级核心模块。
- ExamResult / ExamDiagnosis。
- Mastery。
- StudyPlan。
- AI Tutor + space-scoped retrieval/citation。

Practice 定义为训练；Exam 定义为测量。

考试结果必须反馈到 Mastery / Review / StudyPlan。

Admin Web 正式成为内容生产、审核、题库、考试和任务治理后台。

## 第一套真实数据源

确定 `数据库系统工程师教程.zip` 作为第一套真实摄取验证样本。

样本包含：

- 目录图片。
- 第一章教材图片。
- 数字文件名和 hash 风格文件名混合。

因此正式需求包含：

- page ordering suggestion。
- low-confidence review。
- manual drag/reorder。
- OCR。
- TOC/outline extraction。
- KnowledgePoint provenance。

## 当前状态

```text
Technical Baseline     COMPLETE
Business Alignment     COMPLETE
Code Development       NOT STARTED
Git Baseline           WAITING USER APPROVAL
Technical Spikes       NOT STARTED
```

## 下一步

1. Hermes 只读理解当前文档。
2. 用户最终 Review。
3. 用户手动建立 Git baseline 并推送 GitHub Private。
4. Technical Spikes。
