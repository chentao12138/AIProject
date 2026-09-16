# AIStudy Desktop 前端最终开发总清单（Final Completion Master Plan）

> 目标：开发人员按本清单从上到下执行，执行完后不再存在“以后再做”的前端功能。
>
> 本清单基于当前 `src.zip` 的实际代码状态整理。当前代码已经包含：正式登录/refresh/safeStorage、Spaces、Sources、SourceAsset/TXT/Markdown/PDF/PNG/JPEG ingestion、Knowledge Catalog、AI Provider Settings、AI Tutor/Chat 基础、electron-builder 打包。
>
> **范围规则只有两种：**
>
> - **A = 本次最终开发必须实现**
> - **B = 明确永久不属于最终产品范围**
>
> 不允许出现第三种“以后再开发 / 暂时先不做”。

---

# 0. 总体执行规则

## 0.1 A 类能力的合同规则

任何标记为 **A** 的能力，如果当前 `packages/api-client` 缺少必要接口/字段：

1. 立即记录 `BACKEND CONTRACT REQUEST`；
2. 后端必须在本轮最终开发期间补齐 stable contract；
3. live OpenAPI regenerate；
4. `packages/api-client` regenerate/typecheck；
5. frontend sync stable commit；
6. 完成该 A 项；
7. **不得以“以后再接”为理由关闭任务。**

也就是说：

```text
A + backend contract missing
≠ deferred

A + backend contract missing
= 本轮 blocker，必须解决后再宣布前端完成
```

## 0.2 API/状态管理硬约束

全项目继续坚持：

- React 18 + TypeScript
- React Router HashRouter
- TanStack Query
- `@aistudy/api-client` 为唯一业务 API 边界
- Electron renderer 禁止 Node API
- 禁止 axios/direct business fetch
- 禁止 renderer 直连 AI provider
- access token 内存；refresh token main-process `safeStorage`
- 不新增 Redux/Zustand，除非出现经过证明的跨页面客户端状态问题；当前默认不用
- mutation 不自动 retry
- query key 必须按 `spaceId/entityId` 精确作用域
- 生产 `app://aistudy`
- `contextIsolation=true`
- `nodeIntegration=false`
- `sandbox=true`
- `webSecurity=true`

## 0.3 “前端开发完成”总门禁

只有全部满足才允许写：

```text
AISTUDY DESKTOP FRONTEND V1 FUNCTIONALLY COMPLETE
```

条件：

- 所有 A 项全部完成
- 所有 B 项均明确记录为永久 out-of-scope
- 不存在 `TODO later / future task / deferred`
- 不存在 stable user-facing backend endpoint 没有对应 UI，除非明确属于 Admin Web 或 B 范围
- 所有跨功能 deep-link 有真实 ID，不猜关系
- production build 可运行
- Windows installer 可安装/卸载
- Formal Auth 真实链路通过
- Source ingestion 真实链路通过
- Question → Practice → Exam 真实链路通过
- Search/Notes 真实链路通过
- AI 基本真实链路通过（真实 provider credential 若无法提供，只允许把“外部 provider live smoke”作为环境证据缺失；不得存在前端代码缺口）
- lint/typecheck/tests/build 通过
- critical E2E 通过
- README/开发文档与实际功能一致
- Git clean after final commit

---

# 1. 当前基线收口与真相校准

**分类：A**

这是所有后续业务开发的入口。不能在当前基础仍有不一致时继续堆页面。

## A1.1 当前未提交/未收尾功能统一落盘

### 应完成

确认并正式收口当前已经存在的：

- FE-002B media ingestion
- Formal Auth
- FE-AI-001
- electron-builder packaging
- 所有当前 branch merge 后的 stable `packages/api-client`

逐项核实实际 Git 状态与 commit history。

### 当前代码审查发现

当前 `src.zip` 已经存在：

- `features/auth/LoginPage.tsx`
- `auth-session.ts`
- `safeStorage` vault
- `features/ai/AiSettingsPage.tsx`
- `features/ai/AiChatPage.tsx`
- PDF/Image media policy
- electron-builder build config

但 README 的 Scope 段仍有旧文字，例如仍把 SourceAsset / AI Chat 写成“尚未实现”。

### 完成效果

代码、README、development log、current-task 对当前真实状态一致。

### 下一点临界条件

- Windows Git clean
- stable backend/client commit 已同步
- `lint/typecheck/test/build` 全绿
- README 不再含已过期“尚未实现”说明

---

## A1.2 清理当前源码中的“半实现”状态

### 应完成

重点检查所有“已有 UI 但业务链不完整”的功能。

当前源码明确存在一个高优先级问题：

`AiChatPage.tsx` 中 conversation messages 使用本地：

```text
useState<MessageItem[]>([])
```

当前没有在打开已有 conversation 时加载远端消息历史。

代码里甚至保留了注释：

```text
如果当前 api-client 没有 listMessages...
```

这意味着当前：

```text
打开旧 conversation
刷新
deep-link
```

可能只看到空消息区。

### 必须完成

- 使用 stable `getConversation` 或正式 message-list API
- 打开 conversation 时恢复服务器消息
- 发送成功后与服务器状态对齐
- refresh 后消息仍存在
- back/forward 不丢历史
- archive 当前 conversation 后路由回安全状态
- invalid conversation id 安全处理

如果 stable contract 无消息历史：

**这是 A 类 backend blocker，必须本轮补 contract，不得留到以后。**

### 完成效果

AI conversation 真正是服务器持久化对话，而不是当前窗口临时聊天。

### 下一点临界条件

刷新 `/spaces/:spaceId/ai/:conversationId` 后能恢复完整服务器消息。

---

## A1.3 文档/临时文件清理

### 应完成

最终 repository 不允许提交：

- `dev-smoke.log`
- `dev-smoke-err.log`
- `*.tsbuildinfo`
- TEMP token
- smoke fixture
- task plan
- build output
- dist output
- `.env`

README 必须准确描述最终功能，不再保留阶段编号造成的错误 scope。

### 下一点临界条件

`git status` 只包含真实功能变更；最终 commit 后 clean。

---

# 2. Source / Knowledge / Provenance 最终闭环

**分类：A**

Source/Ingestion 主体已基本完成，本点只做最终业务闭环，不再扩展新的媒体能力。

## A2.1 KnowledgePoint → Source provenance 导航

### 应完成

当前 KnowledgePoint detail 只显示：

```text
Content block #123
relationType
```

最终必须支持：

```text
KnowledgePoint
→ View Source
→ Source workbench
→ 对应 SourcePage
→ 对应 ContentBlock 高亮
```

### Backend contract 要求

provenance response 必须提供 ownership-safe：

- `sourceId`
- `sourcePageId`
- `contentBlockId`
- `relationType`

若当前仍只有 `contentBlockId`：

**A 类 blocker，后端本轮补齐。**

禁止 frontend reverse-N+1 猜 ownership。

### 技术

- React Router query params：
  - `?page=<sourcePageId>&block=<contentBlockId>`
- SourceWorkbench 已有 block highlight 基础，可复用
- invalid id fallback 到 Source detail

### 完成效果

知识点出处可一键回到真实原文位置。

### 下一点临界条件

至少一条真实 provenance 经 MySQL/backend 能完成 KnowledgePoint → Source/Page/Block 导航。

---

## A2.2 Source reader 作为统一 deep-link 目标

### 应完成

统一处理来自：

- Knowledge provenance
- Search
- Notes
- AI citation

的 Source/Page/Block deep-link。

保证：

- refresh 可恢复
- back/forward 正常
- invalid page/block 不 crash
- block highlight 不污染其它 page
- query param 与真实选中 page 同步

### 完成效果

后续 Search/Notes/AI 不需要各自重复 source-reader 逻辑。

### 下一点临界条件

统一 deep-link helper/route contract 稳定。

---

## A2.3 Source/Ingestion 最终回归

### 应完成

保持已完成能力：

- TXT
- Markdown
- PDF
- PNG
- JPEG
- multi-asset
- latest asset/job
- stale-content prevention
- retry FAILED
- ingestion history
- PDF no-text valid state
- image no-OCR valid state

只修回归，不再新增媒体格式。

### 下一点临界条件

真实 backend smoke 全绿。

---

# 3. AI 最终业务闭环

**分类：A**

Provider Settings / Chat 基础已完成；本点解决当前 contract/持久化缺口。

## A3.1 Conversation history 持久化

见 A1.2，必须完成。

### 效果

旧 conversation 打开、刷新、deep-link 后有真实历史消息。

---

## A3.2 Source / Knowledge → Ask AI 上下文

### 应完成

支持：

```text
Source Workbench
→ Ask AI

KnowledgePoint Detail
→ Ask AI
```

frontend 只传 stable identifiers，不把全文塞进 URL/renderer payload。

建议 stable request 支持：

- `sourceId`
- `sourcePageId`（可选）
- `contentBlockId`（可选）
- `knowledgePointId`（可选）

### Backend contract

当前若 `SendMessageRequest` 仍只有：

```text
content
```

则必须在本轮补 context contract。

### UI

进入 AI 时显示当前上下文 chip：

```text
Source: Chapter 3
Page: 4
Knowledge: Bayes theorem
```

用户可以移除 context。

### 完成效果

AI Tutor 真正能围绕用户当前学习材料工作，而不是纯通用聊天。

### 下一点临界条件

真实 context request 到 backend，AI response 使用对应材料。

---

## A3.3 AI reference/citation 可导航

### 应完成

AI response references 不只展示 title/snippet，还可以跳：

```text
reference
→ Source/Page/Block
```

或：

```text
reference
→ KnowledgePoint
```

### Backend contract

ContextReference 必须提供真实导航 ID。

不能解析 assistant 文本 `[1]` 猜 citation。

### 完成效果

用户可以验证 AI 回答依据。

### 下一点临界条件

至少一条真实 AI response reference 成功 deep-link。

---

## A3.4 AI Provider Settings 最终验收

### 应完成

保持并真实验证：

- GET 不返回真实 Key
- `apiKeyConfigured`
- unchanged key → omit
- new key → submit
- save 后清空 input
- real DELETE API
- test connection
- no renderer provider direct network

当前后端 E2E profile 若仍缺 AI secret migration：

本轮必须修 backend test/runtime profile，使真实 secret persistence smoke 能通过。

### 完成效果

Settings 不再存在“代码看起来正确但测试 profile 无法真实保存 Key”的洞。

### 下一点临界条件

安全测试 key 的 save → GET configured → delete → GET false 全链路通过。

---

## A3.5 非流式 Chat 明确收口

当前 stable contract 是 non-streaming。

### 应完成

最终 UI：

```text
Sending…
→ full response
```

不做 fake stream。

### 下一点临界条件

无任何 streaming TODO。

---

# 4. Question Bank（题库）

**分类：A**

这是当前最大的未实现业务域之一。

## A4.1 Question contract audit

### 应完成

审 `packages/api-client` 中所有非管理员 Question endpoint。

建立矩阵：

- list
- get
- create（若 final backend 支持）
- update（若支持）
- delete/archive（若支持）
- publish/activate（若支持）
- question types
- options
- answer model
- explanation
- difficulty
- knowledge/source provenance
- pagination/filter/sort

### 硬规则

**final backend 已提供给普通 Desktop 用户的每一个 Question endpoint，都必须有对应 UI 或明确归入 B。**

不能存在“API 有，但前端以后再接”。

### 下一点临界条件

Question contract matrix 完成且无未知字段。

---

## A4.2 Question List

### 功能

路由建议：

```text
/spaces/:spaceId/questions
```

实现：

- pagination
- keyword/filter（仅 backend contract 支持的字段）
- category/knowledge/difficulty/status filter
- question type
- loading/empty/error
- click detail
- URL 持久化 query/filter

### 技术

TanStack Query + URLSearchParams。

禁止一次拉全量在 renderer 过滤。

### 效果

用户能查找空间内题目。

### 下一点临界条件

真实 backend 至少两页数据的分页/筛选稳定。

---

## A4.3 Question Detail

### 功能

显示：

- stem
- type
- options
- correct-answer metadata（如果 final product允许浏览答案）
- explanation
- difficulty
- status
- linked KnowledgePoint
- provenance/source

对于 backend 支持的所有 question type，都必须有 renderer。

未知未来 type：

显示安全 unsupported state，不 crash。

### 效果

题目可被完整阅读和定位来源。

### 下一点临界条件

所有 stable question type 有 UI。

---

## A4.4 Question Authoring / Curation

### 分类

A。

### 功能

如果 final backend 对 Desktop 普通用户暴露 create/update/publish/archive：

全部实现。

至少：

- type-aware form
- options editor
- answer editor
- explanation
- difficulty
- knowledge link
- validation
- pending duplicate prevention

如果 backend 当前缺这些但产品要求用户维护题库：

必须本轮补 backend contract。

### 技术

React controlled form；不引入大型 form library，除非表单复杂度实测需要。

### 下一点临界条件

从 UI 创建/修改一条题目并从 list/detail 重新读回一致。

---

# 5. Practice（练习）

**分类：A**

Practice 必须依赖 Question renderer 完成。

## A5.1 Practice Start

### 功能

路由建议：

```text
/spaces/:spaceId/practice
```

根据 stable contract 支持：

- 选择范围（space/category/knowledge point）
- question count
- difficulty
- question types
- start

不得提供 backend 不支持的 fake option。

### 效果

用户能创建一轮练习 session。

### 下一点临界条件

真实 backend 创建 session 成功并返回稳定 session id/question set。

---

## A5.2 Active Practice Session

### 功能

建议路由：

```text
/spaces/:spaceId/practice/:sessionId
```

实现：

- 当前题号 / 总数
- reusable QuestionRenderer
- answer input
- submit answer
- pending
- immediate correctness/feedback（只有 backend contract 支持）
- explanation
- Next
- progress
- refresh/resume

### 技术

服务端 session 是 truth。

不要把完整 session 真相只放 React state。

### 效果

刷新 app 后仍能恢复进行中的练习。

### 下一点临界条件

真实 session 中途 refresh 后可继续。

---

## A5.3 Practice Completion

### 功能

- completed state
- score/accuracy（backend 提供才显示）
- correct/incorrect
- wrong-answer review
- explanation
- link back to Knowledge/Source
- restart/new session
- history（如果 final backend有 history endpoint）

### 效果

形成学习反馈闭环。

### 下一点临界条件

完整 real backend Practice E2E PASS。

---

# 6. Exam（考试）

**分类：A**

Exam 与 Practice 必须是不同业务行为。

## A6.1 Exam Setup / Start

### 功能

路由建议：

```text
/spaces/:spaceId/exams
```

实现 final backend 支持的：

- exam list
- create/start
- question count/scope
- time limit（仅 backend contract 有）
- attempt status

### 效果

用户可以进入正式 exam attempt。

### 下一点临界条件

真实 attempt 创建并有稳定 attempt id。

---

## A6.2 Active Exam Attempt

### 功能

- question navigation
- answer selection/input
- answered/unanswered indicator
- previous/next
- backend answer save
- refresh/resume
- final submit
- duplicate-submit prevention
- leave-page warning if需要

### Timer 技术要求

只有 backend 提供：

- deadline
- duration
- remaining/expired semantics

才显示 timer。

backend 是 authoritative；客户端 timer 只是显示。

不得前端自创考试截止时间。

### 效果

关掉页面再回来不会丢已保存答案。

### 下一点临界条件

refresh/resume + answer persistence PASS。

---

## A6.3 Exam Submit / Result / Review

### 功能

- submit confirmation
- terminal state
- score
- pass/fail（若 contract有）
- per-question review
- correct answer/explanation（只有考试提交后 backend允许）
- linked Knowledge/Source
- attempt history（若 contract有）

### 效果

完整“开始 → 作答 → 提交 → 查看结果”闭环。

### 下一点临界条件

真实 backend Exam E2E PASS。

---

# 7. Search（全局/空间内搜索）

**分类：A**

## A7.1 Search contract audit

### 应完成

最终 Search contract 所有 result types 必须映射。

目标结果域至少审查：

- Source
- SourcePage/ContentBlock
- KnowledgePoint
- Question
- Note

如果 final backend 还有其它 user-facing result type，也必须实现。

### 下一点临界条件

result-type matrix 完成。

---

## A7.2 Search Page

### 路由建议

```text
/spaces/:spaceId/search?q=...
```

### 功能

- query input
- debounce
- Enter search
- type filters
- pagination
- result count
- loading/empty/error
- query 保存在 URL
- back/forward 恢复

### 技术

服务器搜索。

禁止 renderer 把所有数据拉下来全文扫描。

### 下一点临界条件

真实 backend query + pagination PASS。

---

## A7.3 Search Result Renderers / Deep Links

### 功能

不同 result type 显示：

- type
- title
- safe snippet
- provenance/location

点击：

```text
Source result → Source reader
Knowledge → Knowledge detail
Question → Question detail
Note → Note detail
```

SourcePage/Block → 对应 page/block highlight。

### 安全

snippet highlight 用 React nodes，不用 `dangerouslySetInnerHTML`。

### 效果

搜索成为所有学习内容的统一入口。

### 下一点临界条件

所有 stable result type 均能打开真实目标。

---

# 8. Notes（笔记）

**分类：A**

## A8.1 Notes CRUD

### 路由建议

```text
/spaces/:spaceId/notes
/spaces/:spaceId/notes/:noteId
```

### 功能

最终必须有：

- list
- create
- detail
- edit
- delete/archive（根据 stable backend final contract）
- timestamps
- loading/empty/error

如果 backend final product 还没有完整 Notes CRUD：

本轮补 contract。

### 效果

笔记不是 demo，只能创建不能修改的状态不可接受。

### 下一点临界条件

CRUD real backend PASS。

---

## A8.2 Note Editor

### 功能

最终 V1 使用：

- plain text / Markdown source editor
- explicit save
- dirty state
- cancel
- save pending
- navigation-away protection（有未保存修改时）

### 安全

不使用 raw HTML/WYSIWYG。

Markdown 如果渲染：

必须安全 escape raw HTML。

### 效果

用户可稳定维护长文本笔记。

### 下一点临界条件

save/reload 内容一致；无 XSS。

---

## A8.3 Note Context / Linking

### 功能

根据 final contract 支持关联：

- Source
- SourcePage
- ContentBlock
- KnowledgePoint
- Question

至少要支持 backend 已暴露的所有关联。

从 Source/Knowledge/Question 页面提供：

```text
Add note
```

从 Note 可以：

```text
Open source/context
```

### 效果

Notes 与学习资料不是孤岛。

### 下一点临界条件

至少一种真实 context link 双向导航 PASS；所有 stable link type 均完成。

---

# 9. 全产品导航与跨业务工作流

**分类：A**

当 Question/Practice/Exam/Search/Notes 加入后，不能只把页面堆在 Router 里。

## A9.1 Final AppShell Navigation

### 必须包含

在当前 Space 下：

- Sources
- Knowledge
- Questions
- Practice
- Exams
- AI Tutor
- Search
- Notes

全局：

- Spaces
- AI Settings
- User / Logout

### 技术

仍使用 semantic `NavLink`。

保证 active state 唯一正确。

### 下一点临界条件

所有最终 A 类页面可从 UI 到达，无隐藏“只能输 URL”的页面。

---

## A9.2 Cross-feature entry points

### 功能

形成这些实际工作流：

```text
Source
→ Knowledge provenance
→ Question
→ Practice
→ Exam
```

以及：

```text
Source/Knowledge
→ Ask AI
```

```text
Source/Knowledge/Question
→ Add Note
```

```text
Search
→ any entity
```

### 效果

用户不需要频繁退回首页找目标。

### 下一点临界条件

所有 cross-link 使用真实 backend IDs。

---

## A9.3 Unified UX states

### 应统一

- loading
- empty
- 401
- 403
- 404
- network
- 5xx
- validation
- pending mutation
- retry

禁止每个新模块自己发明完全不同的错误系统。

### 下一点临界条件

所有最终路由都使用统一 error/loading patterns。

---

## A9.4 Accessibility

### 必须完成

- keyboard navigation
- visible focus
- semantic heading
- form labels
- `aria-current`
- dialogs focus trap
- live status for pending/error
- no color-only state
- button disabled/pending semantics

Practice/Exam answer controls尤其要可键盘操作。

### 下一点临界条件

核心流程 keyboard-only smoke PASS。

---

## A9.5 Route-level performance

随着最终模块增多：

### 应完成

如果 production renderer bundle 明显膨胀：

使用 `React.lazy` / route-level code splitting 分割：

- AI
- Practice
- Exam
- Search
- Notes

不需要为小页面过度切分。

### 效果

启动 shell 不必一次加载所有业务模块。

### 下一点临界条件

production startup 无明显 bundle regression；build 无 chunk warning/加载错误。

---

# 10. Formal Auth 最终验收

**分类：A（主体已实现，剩余是最终验收）**

当前代码已经有：

- `/login`
- login
- refresh rotation
- `/auth/me`
- logout
- safeStorage refresh vault
- auth cache boundary
- RequireAuth

## A10.1 Real installed-app Auth smoke

### 必须验证

安装后的 production app：

```text
login
→ close app
→ reopen
→ refresh token restore
→ /auth/me
→ authenticated
→ logout
→ reopen
→ anonymous
```

### 效果

不是只有 dev environment 能登录。

### 下一点临界条件

真实 packaged Windows app smoke PASS。

---

## A10.2 Refresh race / expiry

### 必须确认

- concurrent 401 single-flight refresh
- safe method one-time replay
- write mutation 不重复提交
- refresh family failure → login
- old principal cache clear

现有 unit tests 保留。

### 下一点临界条件

真实/集成 smoke + tests PASS。

---

# 11. Release / Packaging / Critical E2E

**分类：A**

electron-builder 已经存在，但“有 build script”不等于前端最终开发完成。

## A11.1 Windows release artifact

### 最终支持平台

**Windows x64**。

### 功能

- `npm run dist:win`
- NSIS install
- selectable install directory
- Start Menu/Desktop shortcut
- launch after install
- uninstall
- user data behavior符合设计

### 效果

得到普通用户可安装版本。

### 下一点临界条件

干净 Windows 环境安装/启动/卸载 smoke PASS。

---

## A11.2 Signing-ready configuration

### 应完成

代码/构建配置能够接受正式 Windows signing certificate。

证书本身属于发布运营资源，不是前端代码。

如果当前无证书：

frontend code 仍必须 signing-ready。

### 下一点临界条件

release documentation 说明 certificate injection 方式；无硬编码 secret。

---

## A11.3 Critical E2E

### 范围

不要写几百个 E2E。

只自动/半自动覆盖最终核心用户旅程：

1. Login
2. Create/Open Space
3. Source upload + ingestion
4. Knowledge provenance
5. Question
6. Practice
7. Exam
8. Search
9. Notes
10. AI Settings/Chat
11. Logout/restore

### 技术

优先使用已有 Electron CDP harness。

若团队需要可维护 runner，可使用 Playwright Electron；但不要为了迁移测试框架延迟业务完成。

### 下一点临界条件

以上旅程全部有可复现 PASS evidence。

---

## A11.4 Production security scan

### 必须确认

renderer bundle 无：

- Node import
- provider API key
- refresh token
- localStorage/sessionStorage token
- direct StepFun URL request
- generic IPC
- `webSecurity=false`
- `nodeIntegration=true`
- unsafe raw HTML path

### 下一点临界条件

security scan + app:// smoke PASS。

---

## A11.5 Final documentation

### 必须更新

- README
- frontend-current-task
- frontend-development-log
- route map
- final feature matrix
- backend contract dependency list
- packaging instructions
- smoke instructions

### 禁止

任何：

```text
TODO later
future phase
FE-00X next
deferred
```

除 B 项明确写：

```text
OUT OF FINAL PRODUCT SCOPE
```

### 下一点临界条件

文档与实际 code/routes 一致。

---

# 12. B 类：永久不属于最终产品范围

以下能力明确标记 **B**。开发人员不要创建 TODO，不要预留“下个版本”，不要为它们加依赖/路由。

## B12.1 OCR

**B**

永久不做：

- Image OCR
- image-only PDF OCR
- OCR confidence UI

最终行为：

- PNG/JPEG ingestion 成功但无 OCR 文本
- image-only PDF 无文本时显示正常 no-text state

---

## B12.2 WebP ingestion

**B**

最终只支持 backend stable allowlist：

- TXT
- Markdown
- PDF
- PNG
- JPEG

不增加 WebP UI。

---

## B12.3 PDF visual canvas / annotation

**B**

不引入：

- pdf.js
- PDF page canvas
- bounding-box overlay
- PDF annotation editor
- thumbnail strip

最终 PDF 是 extracted-text reader。

---

## B12.4 Rich WYSIWYG Notes

**B**

不引入：

- ProseMirror
- TipTap
- Slate
- Quill

Notes 最终为 plain text/安全 Markdown。

---

## B12.5 Offline-first / local database

**B**

不实现：

- SQLite renderer cache
- offline edit queue
- offline practice
- conflict sync

最终产品需要 backend online。

---

## B12.6 Real-time collaboration / sharing

**B**

不实现：

- shared space collaboration
- live cursors
- comments/mentions
- multi-user collaborative notes
- share links

---

## B12.7 Desktop Admin

**B**

管理员功能继续属于独立 Admin Web。

Desktop 不增加：

- user admin
- role admin
- system config admin
- backend operations dashboard

---

## B12.8 Self-registration / password reset / MFA / SSO

**B**

最终 Desktop Auth 范围：

- username/password login
- refresh
- me
- logout

不增加：

- registration
- forgot password
- email verification
- MFA
- OAuth/SSO

除非产品范围被正式重新定义；在本清单定义下这些永久 out-of-scope。

---

## B12.9 AI streaming / voice / direct provider attachments

**B**

稳定 AI contract 为 non-streaming。

不实现：

- token streaming
- voice input/output
- microphone
- AI image/file attachment direct to provider
- renderer provider SDK

AI 只经 backend。

---

## B12.10 Drag-drop / multi-file bulk upload

**B**

最终 Source upload 以标准 file picker + 单文件 workflow 为正式交互。

不增加：

- drag/drop upload
- batch 100 files
- parallel upload queue

---

## B12.11 Tray / native notifications / global shortcuts

**B**

不增加：

- system tray
- OS notification
- global hotkey
- background daemon

---

## B12.12 Auto updater

**B**

最终发布方式为安装包升级。

不加入：

- electron-updater
- update server
- silent update

---

## B12.13 macOS/Linux 发布认证

**B**

最终支持目标：

```text
Windows x64
```

现有 mac/linux electron-builder 配置可以保留，但不作为正式支持/验收平台。

---

## B12.14 Dark mode / theme system

**B**

不增加主题切换系统。

保持当前统一 Desktop visual language。

---

## B12.15 多语言/i18n

**B**

不引入 i18next 或多语言资源系统。

最终按现有中英文产品术语统一文案。

---

## B12.16 Analytics / telemetry / crash SaaS

**B**

不接：

- Google Analytics
- Sentry
- Mixpanel
- Amplitude

应用内不上传行为 telemetry。

本地错误边界保留。

---

## B12.17 独立 Dashboard / 推荐流

**B**

最终入口继续：

```text
Login → Spaces
```

不增加首页统计卡片、推荐学习流、算法 dashboard。

Practice/Exam 自身提供必要结果/历史即可。

---

## B12.18 Bookmark / Favorite / Spaced Repetition engine

**B**

除非这些已经是 final backend stable contract（当前前端源码没有相关迹象），本产品最终不新增独立：

- 收藏系统
- flashcard
- SM-2/spaced repetition
- daily review queue

Practice 是最终练习机制。

---

## B12.19 导入/导出/备份工具

**B**

不增加：

- export all data
- import backup
- local archive
- CSV bulk import

Source upload 是唯一学习资料导入路径。

---

# 13. 推荐执行顺序 / 依赖图

严格建议：

```text
A1 基线收口
  ↓
A2 Source/Knowledge provenance
  ↓
A3 AI final closure

A4 Question
  ↓
A5 Practice
  ↓
A6 Exam

A7 Search ─────┐
A8 Notes  ─────┤
               ↓
A9 Cross-feature shell/workflow
  ↓
A10 Formal Auth installed-app acceptance
  ↓
A11 Release / critical E2E
  ↓
FRONTEND V1 COMPLETE
```

可以并行：

- A7 Search 与 A4/A5/A6
- A8 Notes 与 A4/A5/A6
- backend contract fix 与其它独立 frontend A 项

不可跨越的依赖：

```text
Question renderer
→ Practice
→ Exam

Unified Source deep-link
→ Search result navigation
→ Notes context links
→ AI citations

All business modules
→ Final AppShell
→ Release E2E
```

---

# 14. 每个大点统一完成模板

开发人员每完成一个 A 大点必须输出：

```text
TASK <ID> REPORT

1. Stable backend/client contract
2. Routes implemented
3. Business flows implemented
4. Error/empty/loading states
5. Cross-feature links
6. Security constraints
7. Targeted tests
8. Real backend smoke
9. Production app:// smoke if relevant
10. lint/typecheck/test/build
11. Files changed
12. Backend contract requests
13. Remaining gaps
14. Git status

RESULT:
PASS
```

其中：

```text
Remaining gaps
```

最终只能是：

```text
NONE
```

或者：

```text
B — OUT OF FINAL PRODUCT SCOPE
```

不能出现：

```text
later
future
next phase
deferred
```

---

# 15. Final Acceptance Checklist

## Baseline

- [ ] Current branch/commits clean and documented
- [ ] README truthfully matches current implementation
- [ ] no temporary logs/plans/build artifacts

## Source / Knowledge

- [ ] TXT/MD/PDF/PNG/JPEG ingestion
- [ ] multi-asset/latest/history/retry
- [ ] SourcePage/ContentBlock reader
- [ ] Knowledge provenance → Source/Page/Block

## AI

- [ ] secure Provider Settings
- [ ] conversation list/create/archive
- [ ] persisted conversation message history
- [ ] send/receive
- [ ] Source/Knowledge context
- [ ] citation/reference deep-link
- [ ] provider-not-configured flow
- [ ] no frontend secret persistence/direct provider call

## Questions

- [ ] list/filter/pagination
- [ ] detail
- [ ] all stable question types
- [ ] final-backend-supported authoring/curation
- [ ] provenance

## Practice

- [ ] start
- [ ] active session
- [ ] answer
- [ ] feedback
- [ ] progress
- [ ] resume
- [ ] completion/review

## Exam

- [ ] start
- [ ] active attempt
- [ ] answer persistence
- [ ] navigation
- [ ] timer only if backend authoritative
- [ ] submit
- [ ] result/review
- [ ] resume/expiry

## Search

- [ ] query/filter/pagination
- [ ] all stable result types
- [ ] safe snippet
- [ ] entity deep-links

## Notes

- [ ] CRUD
- [ ] safe editor
- [ ] dirty-state protection
- [ ] context links
- [ ] navigation

## Auth

- [ ] installed-app login
- [ ] startup refresh restore
- [ ] refresh race behavior
- [ ] logout/reopen anonymous
- [ ] principal cache isolation

## Product shell

- [ ] final navigation
- [ ] back/forward/refresh
- [ ] unified error/loading/empty
- [ ] accessibility
- [ ] route-level performance

## Release

- [ ] Windows installer
- [ ] signing-ready
- [ ] critical E2E
- [ ] production app:// smoke
- [ ] security static scan
- [ ] documentation
- [ ] final clean Git state

---

# 16. 最终完成定义

当且仅当：

```text
A1–A11 全部 PASS
B12 全部正式记录为 OUT OF FINAL PRODUCT SCOPE
Final Acceptance Checklist 全勾选
```

前端开发阶段结束。

最终状态应写：

```text
AIStudy Desktop Frontend V1
FUNCTIONALLY COMPLETE
WINDOWS RELEASE BUILD VERIFIED
ALL FINAL-SCOPE BUSINESS FLOWS IMPLEMENTED
NO DEFERRED FRONTEND FEATURES
AWAITING FINAL PRODUCT ACCEPTANCE
```
