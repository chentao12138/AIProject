# Frontend Current Task

## Objective

FE-002B-PRE-COMMIT-FINALIZE-01（已完成，待 commit）
+ AI Provider Settings 架构预研（等 AI-009 contract）

## Baseline (post-merge)

- Branch: feat/fe-001 @ `2bc86b3` Merge ec070c9 (Batch C)
- api-client from stable Batch C committed revision
- Windows Node v24.11.1 / npm 11.6.2 / win32

## Contract Verification

- SourceAsset / IngestionJob / SourcePage / ContentBlock signatures unchanged
- KnowledgePointSourceResponse: **only contentBlockId** — confirmed no sourceId/sourcePageId
- BACKEND CONTRACT REQUEST recorded (see below)

## Critical Test Coverage (replaces deleted EdgeCases/Polling)

SourceWorkbenchCritical.test.tsx (16 tests):
- Source get 401/403/404/5xx/network
- Invalid ids → no request
- Upload 401/403/500
- Polling stops on SUCCEEDED / FAILED
- Stale content prevention (PENDING + FAILED with old success)
- Latest asset/job deterministic (newest createdAt wins)
- Retry FAILED only
- Size precheck blocks submit

## Real Smoke Status

| Smoke | Status |
|-------|--------|
| Multi-asset A→B latest switch | **BLOCKED** — backend compile error |
| Failed-latest (fake PDF) | **BLOCKED** — same |
| PDF/PNG/JPEG basic | PASS (Day 1 evidence) |
| Production app:// | PASS (Electron alive 12s) |

**Backend compile error** (ec070c9):
`AiAnswerExplanationService.java:158` calls `snapshot.answerDataJson()`
but `AnswerDataCodec.SnapshotView` record has no such method.
This blocks E2eBackendHarness startup → blocks real multi-asset smoke.
Frontend code unaffected.

## BACKEND CONTRACT REQUEST

```
KNOWLEDGE PROVENANCE NAVIGATION
KnowledgePointSourceResponse exposes contentBlockId but NOT:
  sourceId
  sourcePageId
Frontend cannot safely navigate KnowledgePoint → Source → Page → Block
without reverse N+1 lookups.
Request: extend provenance response with sourceId + sourcePageId.
Not blocking FE-002B commit.
```

## Final Status

FE-002B MEDIA INGESTION BUSINESS FLOW IMPLEMENTED
STABLE BATCH C MERGED (2bc86b3)
CRITICAL REGRESSION COVERAGE RESTORED (16 tests)
PRODUCTION app:// SMOKE PASS
REAL MULTI-ASSET SMOKE BLOCKED (backend compile error in ec070c9)
PROVENANCE→SOURCE BLOCKED BY BACKEND CONTRACT
WINDOWS GATES VERIFIED (332 tests / 86.64% coverage)
AWAITING USER REVIEW AND COMMIT

- lint OK / typecheck OK / test:run 332/332 / coverage OK / build OK
- 26 test files / 332 tests
- Coverage: Statements 86.64% / Branches 82.42% / Functions 83.67%

## AI Provider Settings（架构预研，等 AI-009）

### 架构原则

**AI Key 由用户在前端输入，但不由前端保管。**
前端只是配置界面；真实 Key 交给后端安全存储；
所有 Tutor / Explanation / Study Coach 均由后端请求 StepFun。

### 前端职责

- 提供 AI Settings UI（Provider / Base URL / Model / API Key / Enabled）
- 调后端保存配置
- 调后端"测试连接"
- 展示 `apiKeyConfigured: true/false`
- 展示连接成功/失败状态

### 前端禁止

- 不存 localStorage / sessionStorage
- 不长期持久化 Key 于 renderer state
- 不直接从 renderer 请求 StepFun
- 不把 Key 打进日志
- 不复制后端 AI DTO
- 不要求 GET settings 返回真实 Key

### API Key UI 语义

- GET settings 只返回 `apiKeyConfigured: boolean`，绝不返回真实 Key
- 保存时若用户未重新输入 Key → **省略 `apiKey` 字段**（表示保留现有）
- 只有用户真的输入新 Key 时才发送 `apiKey`
- 绝不发送 `"********"` 占位符

### AI-009 Contract Spec（后端已实现，等 api-client 再生）

```text
GET    /api/v1/settings/ai                 → AiSettingsResponse
PUT    /api/v1/settings/ai                 → AiSettingsResponse
POST   /api/v1/settings/ai/test-connection → TestConnectionResponse
DELETE /api/v1/settings/ai/api-key         → AiSettingsResponse
```

配置模型：
```text
provider = OPENAI_COMPATIBLE
preset   = STEPFUN  →  baseUrl=https://api.stepfun.com/v1
                       model=step-3.5-flash（可覆盖）
```

test-connection 返回：`{success, provider, model, latencyMs}`

### 依赖

- **api-client 尚未再生 AI-009 方法**（已确认）
- 等 `packages/api-client` 再生后正式接入
- 后端状态：IMPLEMENTATION COMPLETE / VERIFICATION DEFERRED
- 后端下一步：test-compile → 自动化测试 → live OpenAPI / api-client → StepFun smoke

## Deferred

- Formal Auth / OCR / WebP / Question / Exam / AI Chat
- Provenance→source navigation (needs backend sourceId)
- AI Provider Settings UI（等 AI-009 contract）
