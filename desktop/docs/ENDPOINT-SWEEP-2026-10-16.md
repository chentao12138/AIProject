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

## 第二轮（同一天，后端边修边测）

扫描器补了三件事：按端点的真形状、multipart 上传（`assetId` 拿到了）、以及从列表接口回捞
create 没返回的 id（`subject` 拿到了）。分类也改了：**503 "not configured" 记为 CONFIG，不进 bug 列**。

| 轮次 | OK | 4xx | 5xx | CONFIG | SKIPPED |
|------|----|-----|-----|--------|---------|
| `--mode=all`（占位 body） | 82 | 32 | 13 | — | 113 |
| `--mode=all`（真形状） | 109 | 40 | 14 | — | 77 |
| `--mode=all`（+上传/列表回捞/CONFIG 分桶） | **125** | 43 | **5** | 4 | 63 |

**已被后端修掉、我这轮确认消失的 5xx（共 13 个）**：`statistics/*` 四个、`admin/spaces` 两个 GET、
`admin/spaces/{id}/archive` 与 `…/restore`、以及三个原先报 502 的 AI 端点——它们现在返回
**503 SERVICE_UNAVAILABLE "AI tutor is not configured"**，正是我要的可区分信号（`POST …/variant`
仍把 503 包成 502，但 detail 里带着 not configured，扫描器已按 CONFIG 处理）。

**仍在的 5 个 500**：

```
PUT  /api/v1/spaces/{spaceId}/exams/{examId}/paper/questions/reorder  → 500 INTERNAL_ERROR (33ms)
POST /api/v1/spaces/{spaceId}/review-tasks                            → 500 INTERNAL_ERROR (27ms)  body={"targetType":"QUESTION","targetId":58}
POST /api/v1/admin/mastery/calibration                                → 500 INTERNAL_ERROR (25ms)
POST /api/v1/admin/knowledge/categories/{categoryId}/delete           → 500 INTERNAL_ERROR (12ms)  categoryId 是刚建好的
GET  /api/v1/admin/knowledge/categories/{categoryId}                  → 500 INTERNAL_ERROR (10ms)
```

后两个是**新暴露的**：上一轮我没有 categoryId，所以它们一直是 SKIPPED。这轮反复验证的一件事是
——SKIPPED 不是安全，SKIPPED 里藏着 5xx。

**还堵着 63 个 SKIPPED 的，是三个我拿不到、也猜不出的输入**：`attemptId`（考卷怎么从 DRAFT 变可作答，
合同里读不出来）、`blueprintId`（`POST /exam-blueprints` 不返回 id，列表里也没有）、`pageId`/`contentBlockId`/
`jobId`（上传返回了 asset，但 pages/ingestion-jobs 都还是空）。这三个都需要后端给结论，不是我能绕的。

## 第三轮（服务重启后，扫描器再进一层）

`--mode=all`：**OK 128 / 4xx 48 / 5xx 3 / CONFIG 4 / SKIPPED 57**（上一轮 127/49/3/4/57 的 5xx 从 5 降到 3）。

**后端本轮已修**：`review-tasks`、`paper/questions/reorder`、`admin/mastery/calibration`、
`admin/knowledge/categories/{id}` GET 与 `/delete` —— 5 个 500 全部消失。AI 那组从 502 改成
**`503 AI_NOT_CONFIGURED`**，有码可分支，扫描器据此把它们归入 CONFIG。

**新暴露 3 个 500**（因为我把 `{kpId}` 当成 `knowledgePointId` 的别名，它们第一次被真正调到）：

```
GET  /api/v1/admin/knowledge/points/{kpId}          → 500
POST /api/v1/admin/knowledge/points/{kpId}/publish  → 500
POST /api/v1/admin/knowledge/points/{kpId}/archive  → 500
```

**仍未修、且优先级最高的**：`POST /spaces/{n}/notes → 201 id=X` 之后，该 note 的 **7 个子资源端点全部
`404 NOTE_NOT_FOUND`**（本轮 note 31 / space 58，已连续三轮必现）。同类：`PUT /spaces/58/knowledge-points/42`
返 `KnowledgePoint not found`，而同一 id 在列表里能查到。

**扫描器自身的三轮演进**（目的都是：剩下的 4xx 必须是服务器的答案，不是我的噪声）：
按端点真形状 → 别名与列表回捞 id → `correctOptionKey` / `practiceSessionQuestionId` / 真 sha256。
`SKIPPED` 现在按**缺失输入**分组打印，并落盘 `dist/endpoint-sweep.json` 供两轮逐行 diff。

**57 个 SKIPPED 全部卡在"怎么把它创建出来"**，已把清单发给后端：`attemptId`(11，考卷如何从 DRAFT 变可作答)、
`taskId`/`planId`(10，学习项如何产生)、`jobId`/`blockId`/`pageId`/`nodeId`/`issueId`(15，multipart 上传成功但
解析作业未入队)、`snapshotId`(6)、`blueprintId`(2)、`key`(2)、其余 3 个分别依赖上面三项。
