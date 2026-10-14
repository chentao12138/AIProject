# 后端交付清单（前端 → 后端 Handoff）

> **用途**：前端代码已全部写好并接线。下列项需后端稳定交付后，前端重新生成 `packages/api-client` 即可闭环。**所有项属本轮 A 类，不是"下版本"。**
> **测试基准**：前端在 `:8081` 实测（`:8080` 属另一项目）。详细证据见同目录 `BACKEND-CONTRACT-REQUESTS.md` 与本仓库 `docs/AISTUDY-FRONTEND-REMAINING-REQUIREMENTS-AUDIT-2026-10-13.md`。
> **铁律**：合同交付须经真实源码、live OpenAPI、`packages/api-client` regenerate/typecheck 和稳定 commit；前端从正式版本同步，不从 dirty backend 猜字段。

---

## 0. 已交付、请勿重复实现（`:8081` 已上线，仅待前端 regenerate 接入）

这些已在 live 服务落地（2026-10-14 验证），前端只需把提交进仓库的规格重新生成即可，**后端无需重做**：

- **C01 Notes**：`/notes` GET(page,size,keyword)+POST、`/{noteId}` GET+PUT+DELETE、`/{noteId}/archive` POST(204)、knowledge-points / source-blocks 子资源
- **C02 Provenance**：`KnowledgePointSourceResponse` 已带 `sourceId`/`sourcePageId`/`contentBlockId`
- **C03 AI context**：`SendMessageRequest` 已带 `sourceId`/`sourcePageId`/`contentBlockId`/`knowledgePointId`
- **C04 AI citations**：`ContextReference`/`AIReference` 已带结构化导航 ID（`locator` 仅展示用）
- **C05 Question update**：`PUT /questions/{id}` 已存在（范围窄：仅 `stem`/`explanation`/`difficulty`）
- **C06 Practice result**：`GET …/result` + `GET …/answers`（逐题回顾）已上线
- **C07 Question archive/restore**：`POST …/archive`、`POST …/restore`
- **C09 写入侧**：`CreateQuestionRequest.correctOrder`/`matches`、`AnswerPayloadView.orderingAnswer`/`matchingAnswer`
- **C10 Exam answer read-back**：`AttemptQuestionView` 已带 `answer`/`answered`
- **C11 Learner attempt list**：`GET /exam-attempts?examId&status&page&size`
- **C-Paging**：`GET /questions` 已分页（`QuestionPageResponse`）
- **C-Search NOTE**：`SearchResult.type` 含 `NOTE` 且带 `noteId`

> 例外：本桌面应用**不含管理后台**，admin-web 专属的 `…/grade`、`grading-suggestion`、对应错误码（`EXAM_GRADING_INCOMPLETE` 等）**不接入**桌面客户端，无需为桌面端补充这些码。

---

## 1. 仍需后端交付 / 修复（Open Asks）

### P0 —— 阻断端到端

- **[B1] 考试 ORDERING 答案存储为空 → 无法交卷（measured 2026-10-14）**
  - 复现：`POST /exam-attempts/{id}/answers` 带 `{answer:{orderingAnswer:[1,2]}}` → **200**，但回显 `answer.orderingAnswer=null`（整段 payload 全 null）；`GET` 仍全 null（服务器承认已答却丢了内容）；`submit` → **400** `{code:"VALIDATION_ERROR", detail:"请求包含无法处理的参数。"}`，attempt 卡 `IN_PROGRESS`。
  - 对照：同一 payload 在 **practice** 路径可正常评分（`isCorrect:true`），故缺陷在**考试写入/快照分支**，非 payload 形态。
  - 要求：① 修复考试路径 ORDERING 存储；② 给出**可定位的 400 错误码**（当前文案不可定位，UI 只能显示"校验未通过"+原句，学习者不知哪题丢答案）。
  - 影响：含 ORDERING 题的试卷无法交卷，阻断 A6.2/A6.3 全链路。

- **[B2] 提交并更新 OpenAPI 规格（gating 所有前端接入）**
  - 前端 `packages/api-client` 仍是 **67-path** 旧快照；后端 live 已到 **195/197 path**。
  - 要求：把更新后的 `openapi.json` **提交进仓库**（或保持可访问的 stable 服务地址 + 版本），让前端执行 `npm run api:generate` 重生成 `api.d.ts` 并 `typecheck` 通过。
  - 说明：一旦 `api.d.ts` 反映合同且 typecheck 通过，前端在同一次提交内实现对应 UI，不再下版本。

- **[B3] `explanation` 恒为 null 缺陷（后端已确认是 bug）**
  - `GET …/practice-sessions/{id}/answers` 的 `explanation` 恒为 `null`，错题回顾无法显示解析。
  - 要求：修复返回真实解析。

### P1

- **[B4] C09 读取侧 + 枚举**：`QuestionAnswerView` 需带回 `correctOrder`/`matches`（或脱敏视图），否则详情页无法展示、编辑器无法预填标准顺序/配对；`matchingAnswer` 改为**结构化配对**（而非 JSON 字符串）；合同里 `questionType`/`status` 等仍是 `string`，请给出**真实枚举**（这也是 `backend-vocabulary-contract.test.ts` 必须读 Java 常量防猜测的根因）。

- **[B5] C12 可区分错误码**：考试冲突仍塌缩为 `code:"CONFLICT"` + 自由文本 `detail`；两个新 practice-result 码（`PRACTICE_SESSION_NOT_SUBMITTED`、`PRACTICE_RESULT_NOT_RECORDED`）仅存在于聊天记录，本仓 `server/` 副本迁移停在 V057。要求：把这些码**落进合同**（`@ApiResponse` 或结构化 4xx+code），使前端命名常量成为受检事实而非聊天引用。

- **[B6] C05 前置条件确认**：运行中的服务对 **PUBLISHED** 题目 `PUT` 是接受还是拒绝、用哪个码？`QuestionService.update()` 实际只拒绝 ARCHIVED→404，生成的操作无描述。要求：把真实行为写进合同（description 或结构化 409+code），前端据此决定编辑页是否对 PUBLISHED 开放。

- **[B7] `AttemptSummaryView` 补齐 `gradingStatus` 与 `deadlineAt`**：历史行无法区分"已提交待评分"与"已评分"（当前只能用 `score` 是否为数字推断，若未评分却返回 `score:0` 会误判）；也缺 `deadlineAt`，历史行无法显示过期。前端切换点集中在 `features/exams/exam-attempt-history.ts`。

- **[B8] 结果页"部分成绩"提示**：`ExamResultView`/`ExamAttemptView` 缺 `gradingStatus`，`PARTIALLY_GRADED` 的 learner 看到 `score`/`maxScore` 与 `isCorrect:null` 却无"成绩未定稿"提示。要求：结果读模型带 `gradingStatus`（最好加 `pendingGradeCount`），UI 可显示"当前为部分成绩，N 题待人工评分"。

### P2

- **[B9] C08 AI Settings 测试 schema**：dummy new-key `PUT` 500 / V030 迁移缺失，需核对最新迁移与 E2E（save→GET configured true→DELETE→GET false）。

- **[B10] 会话/消息分页排序文档化**：conversations 按 `COALESCE(last_message_at, created_at) DESC`、messages 按 `created_at ASC`（page 0 = 最旧消息），需把排序写进合同，前端据此实现翻页/加载更多。

- **[B11] 一个能稳定存活的登录账号（验证前置）**：Flyway 在共享 `aistudy_flyway_test` 跑（最近到 V066）；`dev`/`devpassword123` 已被清两次。要求：重新 seed 并约定一个不被集成测试清掉的**稳定窗口**，以便跑通 R03–R05、practice 评分、exam `deadlineAt` 等 live 验证。

---

## 2. 后端完成后应交付给前端的内容（Checklist）

- [ ] 更新并提交 `openapi.json`（或提供可访问的 stable 服务地址 + 版本号）。
- [ ] 前端据此重生成的 `packages/api-client/src/generated/{api.d.ts, openapi.json, manifest.json}`，且 `npm run typecheck` 通过（需覆盖 B4–B8 的字段/枚举/码）。
- [ ] 后端 **stable commit / tag**（便于前端 pin 版本）。
- [ ] `npm run api:live`（`packages/api-client/scripts/check-api.mjs --live`）对运行中服务**通过**：即提交的快照 == 运行服务文档。
- [ ] `scripts/live-journey.mjs` 跑绿，尤其：ORDERING 答案回显非 null、exam 含 ORDERING 可提交、`explanation` 非 null、混合卷 `PARTIALLY_GRADED` 与重判幂等。
- [ ] B5 中被命名的错误码已落进合同（前端 `exam-error.ts`/practice 常量成为受检事实，不再依赖聊天记录）。
- [ ] 书面确认：explanation 修复、ORDERING 存储修复 + 对应 400 码、`AttemptSummaryView` 的 `gradingStatus`/`deadlineAt`、`ExamResult` 的 `gradingStatus`/`pendingGradeCount`。

---

## 3. 参考

- 详细合同与实测证据：`BACKEND-CONTRACT-REQUESTS.md`（本仓库根）
- 前端剩余缺口审计：`docs/AISTUDY-FRONTEND-REMAINING-REQUIREMENTS-AUDIT-2026-10-13.md`（C01–C11 节 + R01–R08 验收）
