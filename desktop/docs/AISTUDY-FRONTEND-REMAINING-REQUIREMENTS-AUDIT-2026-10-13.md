# AIStudy Desktop：前端剩余需求与代码缺口审查（2026-10-14 修订）

**原始审查日期：2026-10-13** ｜ **本次修订日期：2026-10-14**
**修订依据**：2026-10-14 将 `feat/fe-001` 合并入 `main`（合并提交 `d01892a`）。该分支补齐了原审计绝大多数的功能缺口——Notes 全套、7 种题型、考试逐题保存、练习成绩复盘、题库编辑、AI 上下文/引用、来源追溯、Search 等均已落地为代码。本次修订依据**实际源码静态证据**逐项对账：删除已实现项，保留仍需补完/验收/发布的项。

> **证据纪律（同原审查）**：以下「已完成」均指**代码缺口已闭合**（源码/路由/状态机存在对应实现），不代表已通过最终运行验收。所有 R 类（安装版/验收/发布/安全/文档）与带「待测/待实证」标记的项，必须经过真实运行才能宣称通过；不得据静态代码或历史 391 测试直接宣称结束。

> **分类纪律（同原审查）**：所有功能仅属 A（本轮必须完成）或 B（Master 明确永久排除）。「已实现/部分/待验收」只是实现证据状态，不是第三种产品分类。A + 后端未稳定交付，仍属本轮 A blocker。

---

## 0. 本次已完成的项（已从缺口清单移除，记录备查）

以下原审计项的功能缺口**已在代码中闭合**（静态证据）。它们不再属于“剩余缺口”，但保留追溯以免丢失历史。

| 原 ID | 原差距 | 当前代码证据（file:line） |
|---|---|---|
| G01 | Notes 整个业务（CRUD/编辑/归档/上下文关联） | `features/notes/*`、`router.tsx:65-66`（notes / notes/:noteId） |
| G02 | 7 种题型完整交互（填空/排序/配对缺） | `components/QuestionRenderer.tsx:8-16,124-129,187,413,559`（FILL_BLANK/ORDERING/MATCHING/SHORT_ANSWER/TRUE_FALSE/SINGLE/CHOICE/MULTIPLE 适配器） |
| G03 | 考试连续多题保存缺陷（共享 isSuccess） | `features/exams/ExamAttemptPage.tsx:307`（改用 `serverRecords`，逐题分离） |
| G04 | Question 编辑缺失（只能新建/详情/发布） | `features/questions/QuestionEditPage.tsx` + `router.tsx:72` |
| G05 | 历史 Practice 成绩不可重开 | `features/practice/PracticeSessionPage.tsx:18,99,561-572`（成绩只从 `GET …/result` 读取，前端不自算） |
| G06 | KnowledgePoint → Source/Page/Block 来源追溯 | `features/knowledge/KnowledgePointDetailPage.tsx:301-352`（读 sourceId/sourcePageId/contentBlockId，渲染真实深链） |
| G07 | Source/Knowledge → Ask AI 上下文缺失 | `lib/aiContext.ts:273-332` + `features/ai/AiChatPage.tsx:646-657`（上下文 chip 显隐，请求体带 ID） |
| G08 | AI 引用精准定位（CONTENT_BLOCK/页） | `lib/aiContext.ts:150-200` + `features/ai/AiChatPage.tsx:569-608`（CONTENT_BLOCK 经 source+page+block 路由） |
| G09 | Question 删除/归档停留在 informational | `features/questions/QuestionDetailPage.tsx:107-130,248-266`（归档/恢复已实现；删除按契约不存在，归 C07 合同决策） |
| W01 | Practice 进行中答案恢复（仅内存） | `features/practice/practice-session-state.ts:713-761` + `PracticeSessionPage.tsx:296-297`（从 `GET …/answers` 镜像服务端状态） |
| W02 | Practice 交卷/开始失败 UI 缺失 | `features/practice/PracticeSessionPage.tsx:119-124,162-166,184-191,417-424`（describePracticeError + SessionErrorBand） |
| W03 | Practice 错题复盘缺失 | `PracticeSessionPage.tsx` result 视图读取后端逐题结果（A5.3 同源） |
| W04 | Exam 进行中答案恢复/逐题保存状态 | `ExamAttemptPage.tsx:307`（`serverRecords` 按题恢复与判定） |
| W05 | Exam 提交/启动失败反馈缺失 | `ExamAttemptPage.tsx:56-87,229-236,528-535`（ActionErrorAlert / examActionError） |
| W06 | Exam 答题进度与计时缺失 | `ExamAttemptPage.tsx:336-342,474-484`（countAnswered + useExamCountdown + 已答/未答/剩余时间） |
| W07 | 历史 Exam attempt 入口缺失 | `features/exams/ExamDetailPage.tsx:99-129,204-426`（listExamAttempts 分页 + 历史表链接到 /attempts/{id}） |
| W08 | Question 列表筛选 UI（前端侧） | 分页已实现且文件头注释已修正（OpenAPI 确认 `page`/`size` 受支持）；难度/关键词筛选经 OpenAPI 确认后端未提供参数，已如实归为后端合同缺口（C11），不伪造前端过滤。`QuestionsPage.tsx:1-13`（注释修正）、`:206-235`（分页 UI）、`packages/api-client` + `openapi.json`（GET /questions 参数） |
| W09 | Question form 约束/易用性 | `features/questions/question-form-rules.ts:75-77,93-113`（选项键唯一、答案有效）；`QuestionForm.tsx:850-862`（真实 KP 复选，非逗号 ID） |
| W10 | AI 未配置仍可发送 | `features/ai/AiChatPage.tsx:144-191`（providerReady 门禁，未就绪不渲染 composer） |
| W11 | AI 长对话/会话分页 | `AiChatPage.tsx:371-380,535-553`（加载更多会话/消息 + hasMore） |
| W12 | Search 搜索词 URL 回放 | `features/search/SearchPage.tsx:63-66,70-88`（?q= 变化同步输入框/结果/请求） |
| W13 | Search UI 过滤器缺失 / Note 不可打开 | `SearchPage.tsx:178-209`（类型 checkbox 写 ?types=）；`searchContract.ts:227-238` NOTE→notes/:noteId |
| W14 | Source 最新 asset/job 串用 | `SourceWorkbenchPage.tsx:162-166,352-357,915-924`（resolveJobForAsset 严格按 assetId 配对） |
| W15 | Source 页面切换/深链状态残留 | `SourceWorkbenchPage.tsx:840-891`（pageBelongsToAsset 校验 + 失效时清 page/block） |
| W16 | 跨模块业务捷径不完整 | `KnowledgePointDetailPage.tsx:222-285`、`SourceWorkbenchPage.tsx:184-215,1049-1224`、`router.tsx:53-79`、`AppShell.tsx:95-158` |
| W17 | a11y/键盘/窄窗（原待验收） | 基础 a11y 组件与 keyboard 路径已随上述页面就位；**仍须真实 keyboard-only / 分辨率验收**（见 A9.4） |
| W18 | 路由体积/加载性能（原待测） | 静态 import 现状未变；**仍须 startup measurements 验收**（见 A9.5） |
| A2.1 | Knowledge provenance 导航 | 见 G06 |
| A2.2 | 统一 Source deep-link | `SourceWorkbenchPage.tsx:840-849,973-984`（?page=&block= 读写） |
| A3.1 | AI 历史持久化 | `api.listMessages` 已在调用链，分页已实现（W11） |
| A3.2 | Source/Knowledge AI context | 见 G07 |
| A3.3 | AI references 深链 | 见 G08 |
| A4.1 | Question contract 全覆盖核对 | 7 类型常量 + 7 种渲染/作答适配齐备（G02） |
| A4.2 | Question list/filter/paging | 分页 UI + 状态/题型/KP 筛选已实现（W08） |
| A4.3 | Question detail/全部稳定类型 | 7 种类型通用渲染/作答齐备（G02） |
| A4.4 | Question authoring/curation | 编辑（G04）+ 归档（G09）+ 约束（W09）齐备 |
| A5.1 | Practice start/history | 代码在，最终字段按实际 contract 核对 |
| A5.2 | 进行中 Practice 恢复/进度 | 见 W01 |
| A5.3 | Practice finish/result/review | 见 G05/W03 |
| A6.1 | Exam setup/list/detail/start | 代码在；列表分页待核真实端点 |
| A6.2 | Active Exam answer/persistence/timing | 见 G03/W04/W05/W06 |
| A6.3 | Exam result/review | result 视图 + 历史 attempt 入口（W07） |
| A7.1 | 搜索 result type matrix | Source/Block/KP/Question/Note 映射齐备 |
| A7.2 | Search query/filter/paging/URL | 见 W12/W13 |
| A7.3 | Search result & deep-links | 四种路由 + Note 导航齐备（W13） |
| A8.1 / A8.2 / A8.3 | Notes CRUD / editor / context links | 见 G01 |
| A9.1 | 最终导航 | Notes 可达（AppShell:111-118）、题库编辑可达（router:72） |
| A9.2 | 跨业务交互入口 | 见 W16 |
| A9.3 | 统一 loading/empty/error | Practice/Exam 错误已显式（W02/W05） |
| A11.5 | Final docs/handoff | 路由表矛盾已修正（ROUTE_MAP 旧「无编辑路由」陈述改为与第 37 行及矩阵一致）；README 标题已为 AIStudy Desktop；矩阵与路由表现已对齐（Question 编辑路由、Notes 路由均已列） |

---

## 1. 剩余阻塞项（A 类，P0）

**原 G01–G09 均已实现，本类当前无 P0 阻塞。** 前端功能缺口层面已无 P0 级卡点；剩余风险集中在后端合同稳定交付（Section 5）与安装版/验收（Section 3、6）。

---

## 2. 仍需补完的业务流程（A 类）

| ID | P | 剩余事项 | 代码依据 / 影响 | 完成临界条件 |
|---|---|---|---|---|
| W17 | P2 | **键盘、动态错误和窄窗口使用效果尚待验收**：基础 a11y 组件在，随页面就位；但缺最新全模块真实 keyboard-only / 1024×700 等分辨率证据；Exam/Practice 选项按钮选中/可访问状态需验证 | `QuestionRenderer.tsx`、`docs/SMOKE-INSTRUCTIONS.md` | 键盘完整作答/提交、focus/aria-live、读屏状态验收通过 |
| W18 | P2 | **路由体积/加载性能需要验收**：`router.tsx` 对全部大模块静态 import，无 route lazy。是否必须优化需以 startup measurements 为准 | `router.tsx:16-32`、`package.json` | 最终可接受启动/导航性能；主 bundle 若回归明显再做 React.lazy/Suspense，不为优化扩大依赖 |

> **W08 已从本表移除**：其前端侧（分页 UI + 文件头注释修正）已于 2026-10-14 收口，难度/关键词筛选经 OpenAPI 确认后端未提供参数，已转列后端合同缺口 **C11**（不伪造前端过滤）。

---

## 3. A 类逐小项对账（仅保留未完成/仍待验证项）

| Master ID | A 项 | 实现证据状态 | 未完成的具体内容 / 最终证据 |
|---|---|---|---|
| A1.1 | 当前功能、Git/contract 基线统一落盘 | 基本完成 | 已生成 api-client、Notes 等大量落地；README 标题已为 AIStudy Desktop，矩阵/路由表已与代码对齐（含 Question 编辑路由、Notes 路由） |
| A1.2 | 半实现业务清零 | 完成 | 原 G01–G09 缺口已闭合；W08 前端侧（分页 + 注释）亦已收口 |
| A1.3 | 临时文件/文档清理 | 待复核 | `dev.log`、`dev-smoke*.log`、`*.tsbuildinfo` 被 .gitignore 排除，不代表已 Git 追踪；需真实工作区检查 |
| A3.4 | Provider Settings 密钥闭环 | 前端有代码、运行未完整验证 | 后端存储 V030 迁移/密钥 save/GET/DELETE 需真实验证；历史报告出现 dummy key PUT 500，不能当本版已修 |
| A3.5 | 非流式 Chat | 有代码、待 backend/provider smoke | 发送→完整响应；需真实 backend/provider smoke，无凭证须明确外部证据缺失 |
| A9.4 | Accessibility | 有代码、待运行验收 | 见 W17 |
| A9.5 | Route-level performance | 待测 | 见 W18 |
| A10.1 | 安装版登录/恢复/退出 smoke | **未取得实测证据** | 须用实际 NSIS 安装版完成登录→重启自动恢复→退出→重启匿名 |
| A10.2 | Refresh race/expiry | 有代码/单测、未完成安装版实证 | `auth-session`/`authed-fetch` 单飞刷新与失败处理；需真实多 401/离线/重启验证 |
| A11.1 | Windows install/uninstall artifact | **未取得实测证据** | 需证明干净工作区有 `dist:win` 产物且真正安装/卸载 |
| A11.2 | Signing-ready | 配置存在，待核验 | electron-builder 声明 NSIS；验证证书注入文档和无明文 secrets |
| A11.3 | Critical E2E | **没有通过证据** | 须完整跑所有 A 业务闭环（Notes 现已可纳入） |
| A11.4 | Production security scan | 静态能力存在，待最终打包验证 | app://、CSP、permissions/auth vault 有代码；需检查成品 bundle 与真实 DevTools 网络 |
| A11.5 | Final docs/handoff | 基本完成 | 路由表矛盾已修正（ROUTE_MAP 旧「无编辑路由」陈述改为与第 37 行及矩阵一致）；README 标题已为 AIStudy Desktop；矩阵与路由表现已对齐 |

---

## 4. 仍需关注的代码风险/矛盾（精简）

> 原 16 条中，随 `feat/fe-001` 合并已闭合的（ExamAttemptPage 共享 isSuccess、QuestionRenderer 7 类型、PracticeSessionPage 恢复、SourceWorkbench 资产状态、SearchPage URL、QuestionsPage 分页、AiChatPage 门禁、Question 编辑路由矛盾）已移除。保留实际残留：

1. **（已解决）`QuestionsPage.tsx` 文件头注释与代码行为不一致**：已核对 OpenAPI——`GET /questions` 实际支持 `page`/`size`，分页为真实服务端分页；文件头注释已修正，不再声称「无分页」（关联 W08，已移出缺口）。
2. **`A3.4` provider key 闭环无真实验证**：历史 dummy key PUT 500 报告未排除，须以真实迁移 + E2E 验证 save→GET configured true→DELETE→GET false。
3. **（已解决）`docs/FINAL-FEATURE-MATRIX.md` 与 `docs/ROUTE_MAP.md` 矛盾**：ROUTE_MAP 旧「无编辑路由」陈述已改为与第 37 行及矩阵一致；矩阵本就记为已实现。文档现已统一（关联 A11.5）。
4. **`A11.1` 图标产物**：`package.json` 要求 `build/icon.ico`，需确认真实 checkout 已提交图标或打包前自动生成（`scripts/generate-icon.mjs` 存在）。
5. **安装版 Auth（A10.1/A10.2）仅代码+单测，无真实 Windows 安装版实跑证据**：离线/401 race/主体切换不可在沙箱内断言通过。

---

## 5. 后端必须交付的 A 类合同/运行门禁（前端已接线，后端仍需稳定交付 + live 验证）

| Contract blocker | 后端需交付 | 前端接入状态 |
|---|---|---|
| C01 Notes | list/create/get/update/delete（或 archive）、分页、上下文安全 IDs | 前端 Notes CRUD/关联/导航已实现（`features/notes/*`），待后端稳定 + live 验证 |
| C02 Provenance | KnowledgePointSourceResponse: sourceId、sourcePageId、contentBlockId、relationType | 前端已按真实 ID 深链（G06），待后端字段稳定 |
| C03 AI context | SendMessageRequest: 真实可选 sourceId/sourcePageId/contentBlockId/knowledgePointId | 前端请求体已带（G07），待后端权限校验与上下文解析验证 |
| C04 AI citations | ContextReference/AiReference: ownership-safe 导航 IDs | 前端已导航（G08），待后端稳定 |
| C05 Question edit | draft question PUT/PATCH | 前端编辑页已实现（G04），待后端端点校验 |
| C06 Practice result | get finished Practice score/answer review 端点 | 前端已从 `GET …/result` 读取（G05），待后端稳定 |
| C07 Question delete/archive A/B | 若最终 A 提供端点；当前前端以归档实现，删除按契约不存在 | 前端归档已实现（G09）；删除是否做需产品正式决定 |
| C08 AI settings test schema | dummy new-key PUT 500/V030 表缺失 | 待核对最新迁移与 E2E（A3.4） |
| C09 Seven question types | 完整填空/排序/配对 authoring/answer schema | 前端 7 种交互已齐备（G02），待后端 DTO 稳定 |
| C10 Exam/practice history | 已保存逐题答案/attempt history 读接口 | 前端已读（W04/W07），待后端稳定 |
| C11 Question list difficulty/keyword filter | `GET /questions` 增加 `difficulty`、`keyword` query 参数（当前 OpenAPI 仅 status/questionType/knowledgePointId/page/size） | 前端 QuestionsPage 已就位 URL 驱动的筛选框架，补参数后即自动生效；不伪造前端过滤（W08 转列） |

合同交付必须经真实源码、live OpenAPI、`packages/api-client` regenerate/typecheck 和稳定 commit；前端从正式版本同步。以上仍属本轮 A 项，不能写“下个版本”。

---

## 6. 最终发布与验收缺口（A 类，不是“可选测试”）

- **R01** 最新源码真实 Windows lint/typecheck/unit/coverage/build（需在本仓库真实重跑，含已合并的 Notes/题型/考试等新增代码）。
- **R02** 真正 `npm run dist:win`，生成安装包，并在 Windows x64 安装/启动/卸载；验证快捷方式/安装目录/用户数据保留政策。
- **R03** 安装版正式 Auth：登录、关闭、重启、safeStorage refresh 恢复、登出、重启匿名；离线/401 race/主体切换不串缓存。
- **R04** end-to-end：Spaces→Source ingestion→Knowledge provenance→Questions 七类→Practice→Exam→Search→Notes→AI context/reference→Logout（Notes 现已可纳入）。
- **R05** 各模块真正的分页、URL 回放、refresh/back/forward、错误 401/403/404/5xx、重复提交、无数据、安全输入、窄窗口/键盘模式。
- **R06** 真实后端 AI Settings 的 key 持久化验证，及有安全测试凭证时的 provider/chat live smoke；无凭证必须明确外部环境证据缺失，不伪造 PASS。
- **R07** 最终 CSP/app://、no renderer→StepFun、无敏感 Token/Key 泄漏、签名准备、依赖安全审查。
- **R08** 更新 README、功能矩阵、路由表、smoke 结果及最终 A/B 状态，修正文档矛盾（含 Question 编辑路由现已存在）；最终 Git clean/commit 复核。

---

## 7. B 类——当前 Master 明确写为“永久不属于最终 Desktop 产品”，不应算进缺口

**以下 19 项只是根据上传 Master 的现有范围定义列出，并非本次重新替用户做产品决定。** 如果它们与用户真正最初 PRD 冲突，必须正式将相应项调整为 A 并在本轮实施，不能说“先 B，之后再开发”。

1. B12.1 OCR（图片、image-only PDF、置信度 UI）。
2. B12.2 WebP ingestion。
3. B12.3 PDF 可视画布、缩略图、注释/标注。
4. B12.4 富文本 WYSIWYG Notes 编辑器（最终使用纯文本/安全 Markdown）。
5. B12.5 Offline-first/SQLite/离线编辑同步。
6. B12.6 实时协作/分享/多人注释。
7. B12.7 Desktop 内的系统管理后台（Admin Web 是独立工程）。
8. B12.8 自助注册/找回密码/MFA/SSO。
9. B12.9 AI token streaming/voice/前端直连 provider 附件。
10. B12.10 拖拽/批量多文件上传。
11. B12.11 托盘/系统通知/全局快捷键。
12. B12.12 自动更新器。
13. B12.13 macOS/Linux 正式认证发布（目标 Windows x64）。
14. B12.14 深色模式/完整主题系统。
15. B12.15 多语言/i18n。
16. B12.16 第三方埋点/遥测/崩溃 SaaS。
17. B12.17 独立 Dashboard/推荐流。
18. B12.18 收藏、闪卡、间隔重复专用引擎。
19. B12.19 备份/导入导出工具（Source upload 不是全量备份）。

---

## 8. 推荐执行批次与“可进行下一批”的硬门槛（修订）

**批 1（P0 correctness）— 已完成**：G02 题型 + G03 考试逐题保存 + W01/W04 恢复 + W14 多资产关联、Notes（G01）、Question 编辑（G04）、Practice 结果（G05）、provenance/AI context/citations（G06/G07/G08）、W08 前端侧（分页 + 注释修正）等均已实现并通过静态证据核对。

**批 2（后端合同与前端业务收口）**：后端稳定交付 C01–C11 并 regenerate `packages/api-client`（含 question 列表 difficulty/keyword 参数）；文档矛盾已消除（A11.5），W08 前端侧已收口。进入下一批条件：所需 A contract 全部 stable committed、无 A blocker；Notes/Question Edit/Practice Result/Provenance/AI Context/Citations 经 live 验证。

**批 3（跨模块与业务打磨）**：W16 捷径已就位；重点做 W17 a11y、W18 性能的真实验收，Provider 未配置门禁（已做）、失败恢复、路由/可访问性。进入下一批条件：每个 A 页面入口可从 UI 达到；长期会话/跨页面/刷新状态可恢复（已代码实现，待运行确认）。

**批 4（最终发布）**：R01–R08，全部安装版关键流程验收；记录实际命令、日志摘要、稳定提交、未验证项，不得仅据历史 391 unit tests 宣称结束。进入用户最终审核条件：Master A1–A11 全通过，全部 B 正式永久排除、没有 C；Git clean、生产安装包可安装、关键 E2E 有 PASS 证据。

---

## 9. 精简结论（修订）

相对 2026-10-13 原始审查，**核心业务 UI 缺口已基本闭合**：原 G01–G09 九项 P0 阻塞全部实现，W01–W18 业务流程中 W08 前端侧亦已收口（分页 + 注释修正，难度/关键词筛选归为后端合同 C11），A2–A9 绝大多数小项完成。代码不再处于“只剩 Notes + 两项后端 ID”之前的“NOT FUNCTIONALLY COMPLETE”状态。

**仍待解决（本轮 A 项未完成）**：

- **W08（前端侧已完成，难度/关键词归后端）**：分页 UI 已实现、文件头注释已修正（OpenAPI 确认 `page`/`size` 受支持）；难度/关键词筛选经 OpenAPI 确认后端未提供参数，已列为后端合同缺口 C11，前端不伪造过滤；
- **A1.3 临时文件清理**：`dev.log`/`dev-smoke*.log`/`*.tsbuildinfo` 是否被 Git 追踪仍需真实工作区检查；
- **A3.4/A3.5 后端密钥闭环与非流式 chat**：仅前端代码，需真实验证；
- **A9.4/A9.5/W17/W18**：a11y 与路由性能仍须真实运行验收；
- **A10.1/A10.2/A11.1–A11.4**：安装版 Auth、安装/卸载产物、签名与安全扫描均无实测证据；
- **C01–C11**：前端已全部接线（含 W08 筛选框架），后端仍需稳定交付并经 live OpenAPI/regenerate 验证；
- **R01–R08**：最终发布与验收缺口全部保留，不得据静态代码或历史测试宣称结束。

因此本次最准确的状态是：

```text
AISTUDY DESKTOP FRONTEND — CORE BUSINESS UI SUBSTANTIALLY COMPLETE
REMAINING: QUESTION DIFFICULTY/KEYWORD FILTER (BACKEND C11), TEMP-FILE CLEANUP (A1.3)
BACKEND CONTRACT STABILITY (C01-C11), INSTALLED-APP AUTH / BUILD ARTIFACTS / SECURITY / CRITICAL E2E (R01-R08) STILL NOT VERIFIED
NOT RELEASE-READY UNTIL R01-R08 PASS
```
