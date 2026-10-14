# AIStudy Desktop：前端剩余需求与代码缺口完整审查

**审查日期：2026-10-13**  
**依据：本轮用户上传 `electron.vite.config.zip`；其内部 `AISTUDY-FRONTEND-FINAL-COMPLETION-MASTER-CHECKLIST.md`、`docs/FINAL-FEATURE-MATRIX.md`、`docs/ROUTE_MAP.md`、`docs/SMOKE-INSTRUCTIONS.md`、`BACKEND-CONTRACT-REQUESTS.md` 和实际 `src/**`。**

## 读前说明

本报告是**源码/文档静态审查**，不是运行验收；ZIP 不包含 `server/**`、当前 `packages/api-client/**`、Git 元数据、已安装 Windows 可执行程序，也没有本次可复用的真实 E2E 结果。上次开发人员报告的“391 tests / green build”是历史报告，不能等同于本次已重新执行。当前 ZIP 的 51 个 `*.test.ts/tsx` 文件并非已执行测试数；不应把历史的 37 文件／391 测试直接当成当前快照的实测结论。

“最开始的需求”目前可直接核对的权威书面版本是 ZIP 中的 **A1–A11 最终前端总清单**。若最初另有未包含在 ZIP 内的完整 PRD/交互稿/后端最终功能表，则那些文档未能比较，本报告不声称覆盖未提供材料中的需求。

**分类纪律：所有下面的功能均仅属于 A（本轮必须完成），或 B（根据现有 Master Checklist 明确永久排除）。** “已做/部分/缺失/接口阻塞/待验收”只是**实现证据状态**，不是第三种产品分类。A + 缺后端接口，仍属于本轮必须解决的 A blocker。

## 1. 开发人员应最先处理的阻塞项（A 类）

| ID | P | A 能力及当前差距 | 当前代码证据 | 最终通过标准 |
|---|---|---|---|---|
| G01 | P0 | **Notes 整个业务**：列表、创建、详情、编辑、删除/归档、笔记编辑器、离开未保存保护、Source/Knowledge/Question 双向关联全缺 | `app/router.tsx:34-78` 无 notes routes；`features/` 无 notes；`BACKEND-CONTRACT-REQUESTS.md:22-95` 列五个端点需求 | 真实 Notes CRUD、保存重开、上下文双向跳转通过 |
| G02 | P0 | **填空 / 排序 / 配对题完整处理缺失**。后端已承认 7 种题型，前端只 author 4 种；通用 `QuestionRenderer` 对这三种无法提供类型适配的答案交互 | `lib/format.ts:34-56`；`components/QuestionRenderer.tsx:64-80,128-167,175-189`；`QuestionForm.tsx:39-75` | 7 种题型在创建、详情、练习作答、考试作答、回顾中都有正确交互和 payload；必要时后端补正式题型 DTO |
| G03 | P0 | **考试连续多题保存缺陷**：成功保存一道题后整个 mutation 的 `isSuccess` 保持 true，切到下一题仍把“保存答案”禁用 | `features/exams/ExamAttemptPage.tsx:163-181,265-267` | 在同一 attempt 中先后保存 2–3 道不同题都能成功；换题不继承前题按钮状态 |
| G04 | P0 | **Question 编辑缺失**：只能新建、详情、发布，不能修改已有草稿；也不能把 create 当 edit（会复制题目） | `features/questions/QuestionForm.tsx:1-8,54-85`；`app/router.tsx:64-66`；`BACKEND-CONTRACT-REQUESTS.md:223-243` | 经正式更新端点完成草稿编辑、保存、重新读取一致 |
| G05 | P0 | **历史 Practice 成绩不可重开**：`finish` 响应临时存组件 state，历史已完成会话无得分/正确数与逐题复盘 | `features/practice/PracticeSessionPage.tsx:43-47,70-86,328-366`；`BACKEND-CONTRACT-REQUESTS.md:196-221` | 已完成练习关闭、重启后仍可查看成绩、正确/错误及可用解析 |
| G06 | P0 | **KnowledgePoint → Source/Page/Block 来源追溯不完整**：实际 response 无 sourceId/sourcePageId 时只显示“Content block #”，不能点击 | `features/knowledge/KnowledgePointDetailPage.tsx:223-263`；`lib/types.ts:97-113` | 用 ownership-safe 真实 IDs 从知识点直达对应源、页与 block 高亮 |
| G07 | P0 | **Source/Knowledge → Ask AI 上下文缺失**：发送消息只传 `content`，没有 source/knowledge IDs，也没有有效上下文 chip/操作入口 | `features/ai/AiChatPage.tsx:214-216`；`BACKEND-CONTRACT-REQUESTS.md:128-153` | 从来源/知识点打开 AI，显示/移除真实上下文，后端基于 ID 使用对应材料回答 |
| G08 | P1 | **AI 引用精准定位缺失**：仅 SOURCE/KNOWLEDGE_POINT/QUESTION 用 ref.id 跳转，CONTENT_BLOCK 不能跳；SOURCE 引用不能定位页与 block | `features/ai/AiChatPage.tsx:328-365`；`BACKEND-CONTRACT-REQUESTS.md:155-181` | 所有正式支持引用类型导航到对应 Source/Page/Block/Knowledge/Question |
| G09 | P1 | **Question 删除/归档仍停留在“informational”**，违反 A/B 二选一；当前未看到入口 | `BACKEND-CONTRACT-REQUESTS.md:183-194`；`features/questions/QuestionDetailPage.tsx:106-171` | 当前按 A 缺口处理：提供操作并落实 UI；若用户正式决定永久不做，更新 Master 归 B，不能保留“信息性/以后再做” |

## 2. 已有页面但必须补完的业务流程（A 类）

| ID | P | 剩余事项 | 代码依据 / 影响 | 完成临界条件 |
|---|---|---|---|---|
| W01 | P1 | **Practice 进行中答案恢复**：当前输入和 `submittedIds` 只在组件内存；刷新/返回时已提交状态不能从持久信息重建 | `PracticeSessionPage.tsx:168-173,189-204,243-290` | 在已保存多题后刷新/重进，答案、已提交状态、当前位置/进度不错误重置；服务器状态为真相 |
| W02 | P1 | **Practice 交卷、开始失败 UI**：`startSession` / `finishSession` mutation 未在页面显示错误；用户可能只感到按钮没反应 | `PracticeSessionPage.tsx:55-87,115-149` | 401、409、网络、超时等返回安全错误和重试/回退动作 |
| W03 | P1 | **Practice 错题复盘**：当前完成页面仅展示 score/数量，不显示逐题答错项、正确答案与解析 | `PracticeSessionPage.tsx:328-379` | 提交后与历史查看均能依据后端提供的真实逐题结果回顾；不在前端自行评分 |
| W04 | P1 | **Exam 进行中答案恢复/逐题保存状态**：`answers` 是局部 state；重载后没有以服务端答案恢复，且 mutation 成功是全局单状态 | `ExamAttemptPage.tsx:155-187,220-290` | 已保存各题重进后可见，逐题保存状态独立，网络失败明确；第二题仍可提交 |
| W05 | P1 | **Exam 提交/启动失败反馈**：startAttempt、submitAttempt 未在操作页展示 mutation error | `ExamAttemptPage.tsx:49-77,105-137,226-234` | 交卷冲突、过期、409、网络/5xx 等有明确失败状态，不能沉默 |
| W06 | P1 | **Exam 答题进度与时间**：进行中只有“第 X/N 题”，没有 answered/unanswered 状态；`deadlineAt` 只显示在未开始页 | `ExamAttemptPage.tsx:105-114,236-292` | 已答/未答准确来自服务端；如后端提供权威 deadline，进行中显示剩余时间/过期处理，不能自造截止时间 |
| W07 | P1 | **Exam 旧 attempt 的入口与历史**：当前 Exams 列表展示考试本身，ExamDetail 只提供新建 attempt；没有可见的旧 attempt 历史 UI（需先审 backend 是否有列表端点） | `ExamsPage.tsx:165-209`、`ExamDetailPage.tsx:144-218` | 若 final backend 有 attempt history：可从试卷详情重进历史 attempt/成绩；无端点按 A 正式提出合同缺口，不假设已完成 |
| W08 | P1 | **Questions 列表分页/筛选**：只有状态/题型下拉；知识点仅 URL 参数可生效，没 UI；没有分页控件、keyword/difficulty 输入 | `QuestionsPage.tsx:32-54,80-111,123-157` | 满足 Master A4.2 要求的全部最终服务端支持筛选/分页，URL 与请求同源；不拉全量前端假分页 |
| W09 | P1 | **Question form 约束与易用性**：选项 key 可重复、正确答案缺失、知识点关联只能手输逗号 ID，校验/选择弱；待后端约束确认 | `QuestionForm.tsx:39-75,97-121,156-228,295-303` | 各题型有效性检验、真实 KP 选择、字段约束/错误、重复提交防护，通过端到端创建 |
| W10 | P1 | **AI 未配置状态不是操作门禁**：显示未配置/未启用警告，但仍渲染 ConversationList/ChatPanel，包括“新对话”和发送 | `AiChatPage.tsx:47-81,133-141,379-411` | 未配置时明确引导至 Settings，不能误发送；配置成功返回自动恢复，无假在线状态 |
| W11 | P2 | **AI 长对话/会话列表的分页**：已从后端 `listMessages` 恢复消息（旧的“仅内存消息”问题已修复），但只读取 `data.content`，没有 UI 翻页/加载更多；conversation 列表同样未处理页码 | `AiChatPage.tsx:98-104,131-154,205-213,301-365` | 后端如果分页，旧消息和旧会话全部可按页/滚动加载；refresh/deep link 可恢复阅读 |
| W12 | P1 | **Search 搜索词 URL 回放**：`useState(rawQuery)` 仅首次初始化；后续后退/前进/跳转修改 `?q=` 时，输入框与实际数据不一致 | `SearchPage.tsx:29-35,68-81,95-102` | URL 改变后输入框/结果/分页/筛选同步；back-forward 不错位 |
| W13 | P1 | **Search UI 过滤器缺失**：`types` 虽发给服务端，却只来自 URL；没有选择 result type 的界面；Note 等最终 result type 暂未有导航 | `SearchPage.tsx:32-56,85-108,125-149` | 用户可选最终类型并 URL 保持；每种真实 Search result 都能打开对应实体（含 Notes） |
| W14 | P1 | **Source 最新 asset/job 对应关系**：找不到最新 asset 的 job 时，代码回退至整个 Source 的最新 job，可能属于另一 asset，误显示状态 | `SourceWorkbenchPage.tsx:138-141,154-180`；`latest-selection.ts:71-126` | Asset B 无 job/失败/排队时，永远不会把 A job 的 SUCCEEDED/FAILED 当 B 的状态；做多资产真实集成 |
| W15 | P2 | **Source 页面切换 / 深链状态残留**：旧 selectedPageId 在新 asset pages 中不存在时，只 fallback 显示 pages[0]，但内部选中状态和 URL 可能仍指旧页 | `SourceWorkbenchPage.tsx:746-758,821-835,875-890` | 上传 B 后页选择和 URL 均切换 B；back/forward/refresh 正确；高亮不残留于另一 asset |
| W16 | P2 | **跨模块业务捷径不完整**：Sources/Knowledge→Ask AI、Source/Knowledge/Question→Add Note、完整 Knowledge→Question→Practice→Exam 路径都未闭环 | `router.tsx:47-75`、`AppShell.tsx:93-163`、Knowledge/Source/AI pages | 用户从所处实体可直接触发相关学习动作，传合法 ID，无隐藏只能手输 URL 的业务路径 |
| W17 | P2 | **键盘、动态错误和窄窗口使用效果尚待验收**：基础 a11y 组件在，但没有最新的全模块真实 keyboard-only/分辨率证据；Exam/Practice 选项按钮还需验证选中/可访问状态 | `QuestionRenderer.tsx:91-194`、`docs/SMOKE-INSTRUCTIONS.md` | 1024×700 等窗口、键盘完整作答/提交、focus/aria-live、读屏状态验收通过 |
| W18 | P2 | **路由体积/加载性能需要验收**：`router.tsx` 对全部大模块静态 import，无 route lazy。是否必须优化需以 startup measurements 为准 | `router.tsx:16-32`、`package.json` | 最终可接受启动/导航性能；如果主 bundle 回归明显再做 React.lazy/Suspense，不能为优化而扩大依赖 |

## 3. 按原 Master Checklist A1–A11 逐小项对账（完整范围）

以下“有代码”不代表已通过最终运行验收。

| Master ID | A 项 | 实现证据状态 | 未完成的具体内容 / 最终证据 |
|---|---|---|---|
| A1.1 | 当前功能、Git/contract 基线统一落盘 | 部分 | ZIP 无 Git/后端 client 元数据；README 标题仍称 FE-001，当前代码与部分矩阵记录需核一致 |
| A1.2 | 半实现业务清零 | 部分 | AI 历史已从服务器读取；考试保存/练习恢复/题型支持仍不完整 |
| A1.3 | 临时文件/文档清理 | 待复核 | ZIP 包含 `dev.log`、`dev-smoke*.log`、`*.tsbuildinfo`（被 .gitignore 排除，不代表已 Git 追踪）；缺真实工作区检查 |
| A2.1 | Knowledge provenance 导航 | **缺口** | sourceId/sourcePageId 合同、真实 Source/Page/Block 深链未闭环（G06） |
| A2.2 | 统一 Source deep-link | 基本有代码 | Reader 支持 `?page=&block=`，但需修多资产页状态残留（W15） |
| A2.3 | Source/Ingestion 回归 | 有代码、待最新版实证 | TXT/MD/PDF/PNG/JPEG、multi-asset/history/retry 的本版集成验收及回归不能由 ZIP 代替 |
| A3.1 | AI 历史持久化 | 基本有代码 | `api.listMessages` 已存在于调用链，旧的“完全本地消息”说法**不再成立**；检查分页/完整历史（W11） |
| A3.2 | Source/Knowledge AI context | **缺口** | SendMessageRequest 无 context IDs，页面只发 content（G07） |
| A3.3 | AI references 深链 | 部分 | SOURCE/KNOWLEDGE_POINT/QUESTION 可跳，BLOCK/page 不完整（G08） |
| A3.4 | Provider Settings 密钥闭环 | 前端有代码、运行未完整验证 | 后端存储 V030 迁移/密钥 save/GET/DELETE 需要真实验证；历史报告出现 dummy key PUT 500，不能当本版已修 |
| A3.5 | 非流式 Chat | 有代码 | 发送→完整响应；不要求流式，需真实 backend/provider smoke |
| A4.1 | Question contract 全覆盖核对 | 未验证 | ZIP 无生成 client；7 类型常量存在，但创建只实现 4（G02） |
| A4.2 | Question list/filter/paging | 部分 | W08 |
| A4.3 | Question detail/全部稳定类型 | 部分 | 单选/多选/判断/简答有通用渲染；填空/排序/配对缺专用表达与答案交互（G02） |
| A4.4 | Question authoring/curation | 部分 | G02、G04、G09；delete/archive A/B 产品决议缺失（G09） |
| A5.1 | Practice start/history | 有代码 | 有 count+knowledgePointId 选择、history；其余最终字段和行为须按实际 contract 核对 |
| A5.2 | 进行中 Practice answer/progress/resume | 部分 | W01、W02，不能把刷新后空 local state 当完成恢复 |
| A5.3 | Practice finish/result/review | **缺口** | G05、W03 |
| A6.1 | Exam setup/list/detail/start | 有代码 | 题目选择列表可能没有分页；历史 attempt 可发现性需核 contract（W07） |
| A6.2 | Active Exam answer/persistence/timing | **缺陷** | G03、W04、W05、W06 |
| A6.3 | Exam result/review | 部分 | `getExamResult` 页面存在；交卷错误、历史 attempt 入口等待完善（W05/W07） |
| A7.1 | 搜索 result type matrix | 部分 | 已知映射 Source/Block/KP/Question；最终 Notes/其它类型缺 API source 对账 |
| A7.2 | Search query/filter/paging/URL | 部分 | W12、W13（分页已有；类型过滤 UI 缺失） |
| A7.3 | Search result & deep-links | 部分 | 已知四种路由已有；Note/其它 final result 类型缺正确导航（W13） |
| A8.1 | Notes CRUD | **全缺** | G01 |
| A8.2 | Notes editor/dirty | **全缺** | G01 |
| A8.3 | Notes context links | **全缺** | G01 |
| A9.1 | 最终导航 | 部分 | AppShell 现有全部主路由，但 Notes 不可达；题库编辑无路由 |
| A9.2 | 跨业务交互入口 | 部分 | W16 |
| A9.3 | 统一 loading/empty/error | 部分 | 通用 primitives 存在；Practice/Exam start/finish 错误无有效展示（W02/W05） |
| A9.4 | Accessibility | 有代码、待运行 | W17 |
| A9.5 | Route-level performance | 待测 | W18 |
| A10.1 | 安装版登录/恢复/退出 smoke | **未取得实测证据** | `LoginPage`/vault/RequireAuth 已有代码；必须用实际 NSIS 安装版完成登录→重启自动恢复→退出→重启匿名 |
| A10.2 | Refresh race/expiry | 有代码/单测、未完成安装版实证 | `auth-session`/`authed-fetch` 单飞刷新与失败处理；需真实多 401/离线/重启验证 |
| A11.1 | Windows install/uninstall artifact | **未取得实测证据** | `package.json` 有 `dist:win`，ZIP 无 `build/icon.ico`/`icon.png`；需证明干净工作区有产物且真正安装/卸载 |
| A11.2 | Signing-ready | 配置存在，待核验 | electron-builder 声明 NSIS；验证证书注入文档和无明文 secrets；正式签名取决于发布资源 |
| A11.3 | Critical E2E | **没有通过证据** | 只有 `docs/SMOKE-INSTRUCTIONS.md` 步骤；无本版完整执行报告/附件；必须完整跑所有 A 业务闭环（包括 Notes） |
| A11.4 | Production security scan | 静态能力存在，待最终打包验证 | app://、CSP、permissions/auth vault 有代码；需检查成品 bundle 与真实 DevTools 网络 |
| A11.5 | Final docs/handoff | 部分 | 功能矩阵称 Q edit route 存在，ROUTE_MAP 否认；Master 把安装版 Auth 打勾同时要求 smoke pending；文档需统一 |

## 4. 必须另行解决的代码风险/矛盾

1. **`ExamAttemptPage.tsx:265–267` P0**：整套答案共用一次 `submitAnswer.isSuccess`，会阻塞第二题保存。
2. **`QuestionRenderer.tsx:64–80,128–167` P0**：未实现填空/排序/配对专用交互；`format.ts:46–56` 明确承认无 editor，和 Master 中“all stable types”勾选矛盾。
3. **`PracticeSessionPage.tsx:168–173,189–204` P1**：本地 submittedIds/answers 未根据持久服务端数据初始化，已回答题可能重复作答、刷新丢当前状态。
4. **`ExamAttemptPage.tsx:155–187,265–268` P1**：考试本地答案、逐题保存状态未按 questionId 分离/恢复。
5. **`SourceWorkbenchPage.tsx:138–141` P1**：最新 asset 无对应 job 时回退全局最新 job，跨资产状态串用。
6. **`SourceWorkbenchPage.tsx:746–758,821–833` P2**：selectedPageId 不在新 asset page list 时未重置，URL/aria-current 可能落后于可见页。
7. **`AiChatPage.tsx:55–81` P1**：未配置 provider 仍可创建 conversation 和发送内容；提示不等于完成 gated workflow。
8. **`SearchPage.tsx:32–35,95–102` P1**：rawQuery 改变不会同步 localQuery；back/forward search UI 可能错位。
9. **`QuestionsPage.tsx:80–111` P1**：URL 支持 KP filter 但 UI 无选择；无分页 UI。依赖端点真实分页能力。
10. **`QuestionForm.tsx:97–121,156–228,295–303` P2**：选项 key 唯一性、答案有效性/知识点 ID 输入体验不完整。
11. **`PracticeSessionPage.tsx:55–87` / `ExamAttemptPage.tsx:49–77` P1**：start/finish/submit mutation error 不显示。
12. **`docs/FINAL-FEATURE-MATRIX.md:12` vs `docs/ROUTE_MAP.md:31`**：前者写 Question edit route implemented，后者明确没有；文档互相矛盾。
13. **`AISTUDY-FRONTEND-FINAL-COMPLETION-MASTER-CHECKLIST.md:1864–1870,1791`**：Auth 安装版打勾但实际 smoke pending；需以实际验收为准。
14. **`docs/SMOKE-INSTRUCTIONS.md`** 只有手工步骤，并非 PASS 证据；且 Notes 项被跳过。
15. **`package.json` build 配置**要求 `build/icon.ico`，但 ZIP 内未包括图标文件；`scripts/generate-icon.mjs` 存在。需在真实仓库检查 `icon.ico` 是否被提交，或确保打包前自动生成。不能据 ZIP 断言真实 checkout 必然缺少图标。
16. ZIP 内还出现 `*.log`/`*.tsbuildinfo`，它们被 `.gitignore` 忽略，说明分享/审查包需排除，不代表进入提交。

## 5. 后端必须交付的 A 类合同/运行门禁（前端人员不改后端）

| Contract blocker | 后端需交付 | 前端接入完成条件 |
|---|---|---|
| C01 Notes | list/create/get/update/delete（或 archive）、分页、上下文安全 IDs | Notes CRUD/关联/导航和真实 E2E |
| C02 Provenance | KnowledgePointSourceResponse: sourceId、sourcePageId、contentBlockId、relationType | KP→Source/Page/Block |
| C03 AI context | SendMessageRequest: 真实可选 sourceId/sourcePageId/contentBlockId/knowledgePointId，后端权限校验与上下文解析 | Source/KP→Ask AI |
| C04 AI citations | ContextReference/AiReference: ownership-safe 导航 IDs | AI citation 精确 deep link |
| C05 Question edit | draft question PUT/PATCH，完整请求/响应校验 | 题库现有草稿真正修改而非复制 |
| C06 Practice result | get finished Practice score/answer review endpoint 或现有 detail 扩展 | 任意时刻重开历史练习可读成绩和错题 |
| C07 Question delete/archive A/B | 若最终 A，则提供对应稳定端点；若用户明确选永久 B，则在需求中归 B | 不得再保留“informational” |
| C08 AI settings test schema | 已报告 dummy new-key PUT 500/V030 表缺失；需核对最新数据库迁移与 E2E | save dummy key→GET configured true→DELETE→GET false |
| C09 Seven question types | 如果当前 API 无完整填空/排序/配对 authoring/answer schema，则需补精确 DTO | 三种题型能 create/answer/grade/review |
| C10 Exam/practice history | 若最终 backend 不返回已保存逐题答案/attempt history，需要补读接口/字段 | refresh 后恢复用户已提交内容；可打开既有 attempt |

合同交付必须经真实源码、live OpenAPI、`packages/api-client` regenerate/typecheck 和稳定 commit，前端从正式版本同步，不从 dirty backend 猜字段；**以上均仍属本轮 A 项，不能写“下个版本”**。

## 6. 最终发布与验收缺口（A 类，不是“可选测试”）

- **R01** 最新源码真实 Windows lint/typecheck/unit/coverage/build（ZIP 未包含可运行的 sibling api-client，无法在此次静态审查重跑）。
- **R02** 真正 `npm run dist:win`，生成安装包，并在 Windows x64 安装/启动/卸载；验证快捷方式/安装目录/用户数据保留政策。
- **R03** 安装版正式 Auth：登录、关闭、重启、safeStorage refresh 恢复、登出、重启匿名；离线/401 race/主体切换不串缓存。
- **R04** end-to-end：Spaces→Source ingestion→Knowledge provenance→Questions 七类→Practice→Exam→Search→Notes→AI context/reference→Logout。
- **R05** 各模块真正的分页、URL 回放、refresh/back/forward、错误 401/403/404/5xx、重复提交、无数据、安全输入、窄窗口/键盘模式。
- **R06** 真实后端 AI Settings 的 key 持久化验证，及有安全测试凭证时的 provider/chat live smoke；无凭证必须明确外部环境证据缺失，不伪造 PASS。
- **R07** 最终 CSP/app://、no renderer→StepFun、无敏感 Token/Key 泄漏、签名准备、依赖安全审查。
- **R08** 更新 README、功能矩阵、路由表、smoke 结果及最终 A/B 状态，修正文档矛盾；最终 Git clean/commit 复核。

## 7. B 类——当前 Master 明确写为“永久不属于最终 Desktop 产品”，不应算进缺口

**下面 19 项只是根据上传 Master 的现有范围定义列出，并非本次重新替用户做产品决定。** 如果它们与用户真正最初 PRD 冲突，必须正式将相应项调整为 A 并在本轮实施，不能说“先 B，之后再开发”。

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

## 8. 推荐执行批次与“可进行下一批”的硬门槛

**批 1（P0 correctness）：** G02 题型 + G03 考试逐题保存 + W01/W04 恢复 + W14 多资产关联，优先独立并行实现；对 `QuestionRenderer`/考试共享组件先协调避免文件冲突。进入下一批条件：七类题型真实创建/答题/评分基本路径通过；连续考试保存及重新打开不丢已存数据。

**批 2（后端合同与前端业务）：** 后端并行补 C01–C10，前端继续 W08/W09/W12/W13 等无需新合同项。进入下一批条件：所需 A contract 全部 stable committed，同步 `packages/api-client`、无 A blocker；Notes/Question Edit/Practice Result/Provenance/AI Context/Citations 功能完整。

**批 3（跨模块与业务打磨）：** Notes 与 Search/AI/Source/Knowledge 对接；Question/Practice/Exam 结果和历史、Provider 未配置门禁、失败恢复、路由/可访问性。进入下一批条件：每个 A 页面入口可从 UI 达到；使用真实 ID；长期会话/跨页面/刷新状态可恢复。

**批 4（最终发布）：** R01–R08，全部安装版关键流程验收；记录实际命令、日志摘要、稳定提交、未验证项，不得仅据 391 unit tests 宣称结束。进入用户最终审核条件：Master A1–A11 全通过，全部 B 正式永久排除、没有 C；Git clean、生产安装包可安装、关键 E2E 有 PASS 证据。

## 9. 精简结论

目前代码**不是“只剩 Notes + 两项后端 ID + E2E”**。至少还有：

- 整个 Notes；
- 三种已知题型（填空/排序/配对）；
- 考试逐题保存 bug 与保存状态恢复；
- Question 编辑；
- Practice 成绩历史及错题回顾；
- provenance/AI context/引用；
- question 筛选分页、Search URL/类型过滤、多资产 job 状态一致性；
- installed-app Auth/完整业务 E2E/发行产物；
- 以及依赖真实后端进一步核对的最终 API 覆盖。

因此本次最准确的状态是：

```text
AISTUDY DESKTOP FRONTEND — CORE BUSINESS UI PRESENT
FINAL-SCOPE A ITEMS STILL OPEN
SOURCE / QUESTION / PRACTICE / EXAM / AI FLOWS NEED FINAL CLOSURE
NOTES ABSENT
WINDOWS INSTALLED-APP AND CRITICAL E2E NOT VERIFIED IN THIS AUDIT
NOT FUNCTIONALLY COMPLETE
```
