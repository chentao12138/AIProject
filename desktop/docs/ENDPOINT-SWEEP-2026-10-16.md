# 接口全量扫描基线（2026-10-16）

工具：`scripts/endpoint-sweep.mjs`。它从已提交的 `packages/api-client/src/generated/openapi.json`
枚举 **240 个 operation**（97 GET / 113 POST / 16 PUT / 10 DELETE / 4 PATCH），按 schema 生成合法
请求（required + enum + 类型，`Content-Type: application/json; charset=utf-8`），逐个打真实服务，
把结果分成 `OK / CLIENT(4xx) / SERVER(5xx 或传输错误) / SKIPPED(拿不到输入)`。

```
AISTUDY_API=http://localhost:8081 AISTUDY_USER=dev AISTUDY_PASS=... \
AISTUDY_USER2=learner AISTUDY_PASS2=... node scripts/endpoint-sweep.mjs --mode=all
```

写模式**自己新建空间并在里面造数据**，不碰 R03/R04 证据依赖的 space 29。

## 本轮结果

| 轮次 | OK | 4xx | 5xx | SKIPPED |
|------|----|-----|-----|---------|
| `--mode=read`（97 个 GET，占位 body） | 40 | 6 | 6 | 45 |
| `--mode=all`（占位 body） | 82 | 32 | 13 | 113 |
| `--mode=all`（按端点用真形状） | **109** | 40 | **14** | 77 |

最后一轮的自建夹具：`space 35 → question 53 / note 10 / source 5 / knowledge-point 24 / exam 6 / practice-session 31 / ai-conversation 4`（`attemptId` 仍为 null，见下面待答问题 2）。

## 11 个 500（后端 bug，已发清单）

```
GET  /api/v1/spaces/{spaceId}/statistics/overview                  500 INTERNAL_ERROR
GET  /api/v1/spaces/{spaceId}/statistics/time-series                500 INTERNAL_ERROR
GET  /api/v1/spaces/{spaceId}/statistics/question-type-performance  500 INTERNAL_ERROR
GET  /api/v1/spaces/{spaceId}/statistics/kp-practice-trend          500 INTERNAL_ERROR
GET  /api/v1/admin/spaces                                           500 INTERNAL_ERROR
GET  /api/v1/admin/spaces/{spaceId}                                 500 INTERNAL_ERROR
POST /api/v1/admin/spaces/{spaceId}/archive                         500 INTERNAL_ERROR
POST /api/v1/admin/spaces/{spaceId}/restore                         500 INTERNAL_ERROR
POST /api/v1/admin/mastery/calibration                              500 INTERNAL_ERROR
POST /api/v1/spaces/{spaceId}/review-tasks                          500 INTERNAL_ERROR  ← 合法 body：{"targetType":"QUESTION","targetId":53}
PUT  /api/v1/spaces/{spaceId}/exams/{examId}/paper/questions/reorder 500 INTERNAL_ERROR
```

全部 8–135ms 返回，不像超时，像进去就抛。`statistics/*` 是桌面端学习看板要用的。

## 3 个 502（缺 AI provider 配置，非 bug，但语义待定）

```
POST /api/v1/settings/ai/test-connection                             502 AI provider rejected the request
POST /api/v1/spaces/{spaceId}/ai/study-coach                         502 AI provider rejected the request
POST /api/v1/practice-sessions/{s}/questions/{q}/variant             502 AI variant generation failed
```

问题：没配 key 时用 502，和"上游真挂了"撞在一起，前端无法分支。等后端给"未配置"的确定码。

## 已经确认修好的

`POST …/practice-sessions/{id}/answers` 现在返回 **`400 REQUEST_BODY_MALFORMED 请求体字段 "answer.matchingAnswer" 无法解析。`** —— 点名了字段。同一形态还欠两个端点：`PUT /api/v1/settings/ai`（`请求包含无法处理的参数。`）和 `POST /spaces/{id}/sources`（`Request validation failed`，英文且无字段名）。

## 合同空洞

`CreateSourceRequest.sourceType` 在合同里是裸 `string`，真实取值只存在于 `SourceService.java:40-43`
（`DESKTOP_UPLOAD` / `DESKTOP_FOLDER_IMPORT` / `ADMIN_UPLOAD` / `ADMIN_MANUAL`）。扫描器一开始因此
建不出 source，整条 source 子资源家族都成了 SKIPPED。同类：`CreateKnowledgePointRequest.difficulty`、
`CreateExamRequest.examType`。

## 待后端定性的两件事

1. **建得出来读不回来**：`POST /spaces/{n}/notes → 201 id=X`，随后 `GET /spaces/{n}/notes/X → 404 NOTE_NOT_FOUND`（notes 全部子资源同样 404）。这比 500 更严重：写入后任何乐观更新都会错。
2. **learner 把一张卷变可用的正确序列**：`POST /exams/{id}/sessions → 409 EXAM_NOT_AVAILABLE exam is not available: DRAFT`，而 `POST /exams/{id}/publish` 似乎没生效，导致 attempt 建不出来。合同里读不出该用哪个端点/角色。

另外：`GET /health` 带有效 token 返 401；`/api/v1/spike/*` 四个也 401（建议从合同剔除）；`study-plan` 与 `admin/mastery/calibration/active` 无数据时是 404 还是 200+null 待确认。

## 一致的好消息

learner token 打全部 **63 个 `/admin/` 路径**，直方图恒为 `{"403": 63}` —— 一致、无例外，桌面端可以按 403 分支。
