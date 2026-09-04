# AIProject

> 当前状态：**TECHNICAL SPIKES IN PROGRESS**
>
> 技术架构和核心学习业务均已完成对齐。SPIKE-001 / SPIKE-002 / SPIKE-003 已完成；SPIKE-004 待启动。

## 1. 产品定位

AIProject 是一个个人 AI 学习工作台：

```text
真实学习资料
→ 导入/解析/OCR
→ 结构化内容
→ KnowledgePoint
→ 学习 / AI / 笔记
→ Practice
→ WrongQuestion / Review
→ Exam / Diagnosis
→ Mastery
→ StudyPlan
→ 下一轮学习
```

用户通过 `LearningSpace` 隔离不同学习主题，例如：

```text
数据库管理
Java 学习
```

不同空间的资料、AI 检索、题库、练习、考试、错题、掌握度和计划默认互不混合。

业务总入口：`docs/business-baseline.md`。

## 2. 数据来源

V1 主要数据入口：

- Desktop 本地文件/文件夹
- ZIP
- PDF
- JPG/PNG/WebP
- Markdown/TXT
- Admin Web 上传
- Admin Web 手工录入

数据层次：

```text
RAW → EXTRACTED → DERIVED
```

原始文件永久保留；OCR/AI 派生结果可重新生成。

第一套真实验证数据：`数据库系统工程师教程.zip`。

## 3. 系统架构

```text
Desktop (Electron + React + TypeScript) ─────┐
                                             │
Admin Web (React + TypeScript + Vite) ──────┼── REST ──> Spring Boot 3.5.x
                                             │            Java 21
Future Android ──────────────────────────────┘            Modular Monolith
                                                          │
                                                          ├─ Spring Security
                                                          ├─ OpenAPI
                                                          ├─ MyBatis-Plus
                                                          ├─ StorageService
                                                          ├─ Content Extraction
                                                          └─ AI Provider
                                                          │
                                                          ▼
                                                        MySQL 8.x
```

当前全部本地开发；未来 Spring Boot/Admin/MySQL/Storage 可迁 Linux Server。

## 4. 业务模块

```text
auth / user
space
ingestion / content
knowledge / note
question / practice
review
exam
mastery
plan
ai / search
resource
admin
system
```

这是一个 Spring Boot Maven Project 的 package 模块化单体，不是微服务。

## 5. 核心原则

1. Spring Boot 是唯一业务权威入口。
2. LearningSpace 是一级业务隔离边界。
3. Desktop/Admin/Android 不直连 MySQL。
4. RAW 原始资料不被 AI/OCR 覆盖。
5. KnowledgePoint/Question 尽可能可追溯到原资料。
6. Practice = 训练；Exam = 测量。
7. ExamQuestion 保存历史快照。
8. Mastery 是当前能力状态，历史事实保存在作答/考试/复习记录中。
9. AI Retrieval 必须 space-scoped；批量生成内容默认先审核。
10. Storage 只在 DB 保存 storageKey。
11. Hermes 未经用户明确授权不 commit/push。

## 6. 工程目录

```text
D:\AIProject\
├── desktop\
├── admin-web\
├── server\
├── deploy\
├── docs\
├── .gitignore
└── README.md
```

真实运行数据：

```text
D:\AIStudyData\
├── resources\
├── backups\
├── exports\
├── logs\
└── temp\
```

真实用户数据和秘密不进入 Git。

## 7. 工具链

- Windows JDK：Java 21。
- Windows 全局 Maven：3.6.2，不修改。
- `server/`：Maven Wrapper 固定 3.9.x。
- Desktop/Admin npm 生命周期：Windows Node。
- Windows/WSL 不混用同一个 node_modules。
- Hermes 位于 WSL，可调度 Windows PowerShell/CMD。

## 8. 文档入口

| 文档 | 职责 |
|---|---|
| `docs/business-baseline.md` | 业务总定义 |
| `docs/decisions.md` | ADR 唯一 Source of Truth |
| `docs/architecture.md` | 系统边界/模块/运行架构 |
| `docs/product-vision.md` | 产品定位和范围 |
| `docs/user-scenarios.md` | 真实用户流程 |
| `docs/requirements.md` | P0/P1 需求 |
| `docs/feature-map.md` | 功能地图和客户端分工 |
| `docs/data-model.md` | 概念数据模型 |
| `docs/content-ingestion.md` | 数据录入/OCR/排序/审核 |
| `docs/learning-engine.md` | Practice/Review/Exam/Mastery/Plan |
| `docs/api-guidelines.md` | API/Auth/Space scope |
| `docs/technology-selection.md` | 技术选型 |
| `docs/deployment-strategy.md` | Local → Linux Server |
| `docs/development-plan.md` | Spike 和开发顺序 |
| `docs/current-task.md` | 当前唯一动作 |
| `docs/development-log.md` | 阶段事实记录 |

## 9. 当前动作

当前不要直接写业务代码。

流程：

```text
Hermes 只读理解文档
→ 用户最终 Review
→ 用户建立 Git baseline / GitHub Private     ✅
→ Technical Spikes                            ← 当前阶段
   SPIKE-001 (Spring Boot + Java 21 + Maven Wrapper)      ✅ Complete
   SPIKE-002 (MySQL + MyBatis-Plus)                       ✅ Complete
   SPIKE-003 (Flyway / Versioned SQL)                     ✅ Complete
   SPIKE-004+ ...
→ Platform Skeleton
→ 正式 Vertical Slice 开发
```
