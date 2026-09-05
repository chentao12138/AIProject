# Current Task

> 状态：**PLATFORM SKELETON / CORE DEVELOPMENT STARTING**

## 已完成技术基线

- Git Baseline 已建立并推送至 GitHub Private Repository。
- SPIKE-001 Spring Boot + Java 21 + Maven Wrapper：COMPLETE。
- SPIKE-002 MySQL + MyBatis-Plus：COMPLETE。
- SPIKE-003 Flyway / Versioned SQL：COMPLETE。
- SPIKE-004 Auth + Space Authorization：Validation Complete；正式 Refresh Token、USER/ADMIN、production key management 等仍 Deferred。
- SPIKE-005 OpenAPI Contract：Validation Complete（Scope Adjusted）。

SPIKE-005 已真实验证：

- `GET /v3/api-docs` → HTTP `200` / `application/json` / OpenAPI `3.1.0`。
- `bearerAuth` = HTTP Bearer JWT；受保护 endpoint 带安全声明，`/health` 不带。
- typed Java response → 明确 OpenAPI schema。
- response media type = `application/json`。
- Focused regression 17/17 PASS。
- Full `mvnw.cmd clean test`：**31/31 PASS**，Failures=0，Errors=0，Skipped=0。

SPIKE-005 明确 Deferred：

- TypeScript client/types 实际生成。
- Desktop/Admin 共享 `api-client` package。
- generator 选型与 generated-code check-in 策略。

这些内容将在第一个真实 LearningSpace API 出现后基于真实业务 Contract 落地，不再使用 `Spike*` endpoint 继续延长前置验证。

## 当前唯一任务

**TASK-001：LearningSpace Vertical Slice**

从现在开始进入正式业务开发，不再新增无明确阻塞理由的前置 SPIKE。

最小目标：

- 创建正式 LearningSpace 数据模型与 Flyway migration。
- 实现正式 persistence / service / REST API。
- 最小 API：
  - `POST /api/v1/spaces`
  - `GET /api/v1/spaces`
  - `GET /api/v1/spaces/{spaceId}`
- 所有查询和写入必须基于当前用户身份做服务端隔离。
- 使用 integration test 证明：用户只能看到/访问自己的 LearningSpace。
- 让真实 LearningSpace API 出现在 `/v3/api-docs`。
- 在真实 Contract 出现后再落地 TypeScript shared client/types。

## 开发规则

- 当前已经进入正式业务实现；`Spike*` 表、类和临时鉴权实现不能直接当作 production model。
- LearningSpace schema / API / authorization 必须按正式模型设计。
- DB 结构变化必须使用新的 Flyway migration，不修改既有 migration。
- 不为了 TASK-001 引入 Redis / MQ / 微服务 / 新搜索引擎。
- 只有遇到明确技术不确定性且会阻塞当前业务任务时，才允许做短时、可丢弃的 just-in-time Spike。
- Git commit / push 仍由用户手动执行。

## 后续方向

```text
TASK-001 LearningSpace
→ Source
→ KnowledgePoint
→ Question / Practice / Wrong
→ Review / Mastery / StudyPlan
→ Exam
→ AI Tutor / Admin Governance
→ Statistics / Search / Polish
```

SPIKE-006 ~ SPIKE-014 不删除，但改为按对应功能进入实现时按需执行，不再串行阻塞业务开发。
