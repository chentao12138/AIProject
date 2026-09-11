# Frontend Development Log

append-only。每个 major phase 完成后追加 checkpoint。
Git 状态一律以 Windows Git 输出为权威（git.exe -C D:/AIProject-frontend）。

## FE-001 恢复检查 Checkpoint（2026-09-06）

- Baseline: feat/fe-001 @ 7ef6a79 "feat: implement knowledge catalog"（Windows Git 确认，status 干净）
- desktop/ 与 docs/frontend-*.md 不存在 = FE-001 正常起始状态
- 并行 backend worktree D:/AIProject [main] 独立存在，本分支不触碰
- Windows Node 确认: process.platform=win32, node v24.11.1, npm 11.6.2
- shared api-client 13 个业务方法确认存在；generated contract 字段全部 optional
- 工作规则: WSL 编辑文件 / Windows Git 管 Git / Windows Node 管 npm 与 Electron runtime

## FE-001 Scaffold Checkpoint（完成）

- 技术决定: electron-vite + Electron 33 + React 18 + TS + Vite 5；React Router (createHashRouter)；TanStack Query v5；Vitest + RTL + jest-dom + jsdom
- 不引入: Redux/Zustand/MobX/Recoil/大型 UI framework/Tailwind/Axios/React Hook Form/electron-builder/Playwright
- 独立 desktop package，@aistudy/api-client: file:../packages/api-client；不建 root workspace
- scripts: dev / typecheck / test / test:run / build（build = typecheck && electron-vite build）
- Windows npm install 完成（node v24.11.1 / npm 11.6.2），package-lock.json 由 npm 生成；electron 33.4.11 二进制默认下载失败，经 npmmirror 手动补齐
- .gitignore: node_modules/ out/ dist/ *.log .env* + *.tsbuildinfo

## FE-001 Electron Security Checkpoint

- BrowserWindow: contextIsolation=true, nodeIntegration=false, sandbox=true, webSecurity=true（src/main/index.ts:82-88）
- setWindowOpenHandler → deny；will-navigate 仅允许 dev server origin 或 prod file:（src/main/index.ts:59-103）
- CSP 双保险：main 进程 onHeadersReceived 注入 + index.html meta；dev 仅放开 vite 5173 / ws HMR / inline preamble（documented）；无 unsafe-eval、无 remote script
- Preload zero surface（export {}，无 ipcRenderer/fs/process/shell/require）
- Main 无数据库/业务规则/AI key

## FE-001 Renderer Architecture Checkpoint

- StrictMode + QueryClientProvider + ApiClientProvider + RouterProvider（src/renderer/src/main.tsx）
- createHashRouter 路由契约：/ → /spaces；/spaces；/spaces/:spaceId/sources；/spaces/:spaceId/knowledge；/spaces/:spaceId/knowledge/:knowledgePointId；* NotFound
- current space 由 URL 决定（AppShell useCurrentSpaceId），无 store、无 localStorage
- query keys 契约 6 个（lib/query-keys.ts）；mutation 成功 invalidate 对应 key
- 领域类型从 ApiClient 方法返回值推导（lib/types.ts ResultData），零 DTO 复制

## FE-001 Temporary Auth Checkpoint

- InMemoryTokenSession（lib/api-client.ts）：getAccessToken / setAccessToken / clear，纯内存，刷新即失（当前正确行为）
- DevelopmentSession：import.meta.env.DEV gate；文案 "Development-only token injection. Not persisted."；非 DEV 显示 "Authentication integration pending"，无假 login
- 无 localStorage/sessionStorage/cookie/硬编码 token/VITE_ACCESS_TOKEN

## FE-001 Desktop Shell Checkpoint

- 顶栏 AIStudy + backend URL 状态点；左导航 Spaces /（当前空间下）Sources + Knowledge；紧凑 footer
- 当前 space name 由 getLearningSpace(spaceId) 解析显示；无效/404 space → "Space not found or inaccessible"，不请求其子资源
- 可复用组件：Button / Card / Dialog / EmptyState / ErrorState / LoadingState / StatusBadge / Input / Select / TextArea；普通 CSS + CSS variables

## FE-001 LearningSpace Checkpoint

- /spaces：listLearningSpaces 真实调用，显示 name/description/status
- empty state + "Create Learning Space" CTA；createLearningSpace name required / description optional；owner 绝不由 renderer 提交
- 成功 invalidate ['spaces'] + 导航 /spaces/{newId}/sources（response 有合法 id 时）

## FE-001 Source Metadata Checkpoint

- /spaces/:spaceId/sources：listSources 显示 title/sourceType/status/createdAt
- 创建仅 title；payload sourceType 固定 'DESKTOP_UPLOAD'（无 admin sourceType dropdown）
- 无 file input / 拖放 / multipart / file picker IPC；说明文案 "File upload will become available when SourceAsset contract is integrated."

## FE-001 Knowledge Catalog Checkpoint

- /spaces/:spaceId/knowledge：并行 listKnowledgeCategories + listKnowledgePoints；左 category tree 右 point list
- buildCategoryTree 纯函数：root/child 嵌套、sibling 顺序保持、orphan 安全显示、cycle 输出无环（visited 过滤保证 DAG，递归渲染器不会栈溢出）
- Create category：name required / description/parentId/sortOrder optional；parent 选择器来自当前 space categories；不提交 spaceId/ownerSubject
- Point 列表：title/summary/status/originType/difficulty/category name 解析/createdAt；点击进 detail
- Create point：title + content required / summary/categoryId/difficulty optional；difficulty 自由文本（不发明 enum）
- Detail：title/status/originType/category/difficulty/summary/content/publishedAt
- Publish：DRAFT 显示 Publish（pending 禁用）；成功 invalidate points list + point detail；PUBLISHED 仅 badge，无重复 CTA

## FE-001 UX State Hardening Checkpoint

- normalizeApiError 区分 network / 401 / 403 / 404 / 5xx；404 文案唯一「资源不存在或当前不可访问。」（anti-IDOR）
- formatDateTime(value?)：无值或无效 → "—"，否则 locale 格式；无日期依赖
- 所有 remote 数据 optional 安全访问，无 data!.id 泛滥

## FE-001 Test Checkpoint

- 17/17 PASS（5 个测试文件，exit 0）：覆盖 K1.1-K1.12 + sibling order、null parentId、pure cycle root surfacing、DRAFT detail、Source 固定类型说明等
- 修复记录：K1.3 测试 walk 缺 visited 防御（buildCategoryTree 同时改为输出无环 DAG——原实现对纯 cycle 保留环，递归渲染器会无限递归）；K1.10 'PUBLISHED' 出现于 header badge + status 行两处 → getAllByText
- mock ApiClient interface（createMockApiClient / mockApiClient），不 mock 全局 fetch

## FE-001 Windows Runtime Checkpoint

- npm run typecheck：PASS（exit 0）
- npm run test:run：17/17 passed（5 files，exit 0）
- npm run build：PASS（main 2.58 kB / preload 0.01 kB / renderer 511.39 kB + 12.06 kB css，exit 0）
- npm run dev smoke（ELECTRON_ENABLE_LOGGING=1）：dev server 5173 启动、Electron 窗口启动、renderer [vite] connected、React 挂载成功、无 Node integration / CSP violation / 未捕获异常
- 环境修复：node_modules/electron/path.txt 含尾部换行（前一轮手动补齐二进制时写入）→ spawn ENOENT；重写为 b'electron.exe'（node_modules 内，非源码）
- 视觉项（Development Session gate 可见、hash routing 交互、无 token 界面）无法从 console 证明，留待用户 review

## FE-001 Hardening Checkpoint

- 尝试 react-router v7_startTransition future flag 消除 dev 警告 → 当前 react-router-dom 版本类型不支持，已回退（dev-only 良性警告，不升级依赖）
- 静态扫描无新增问题；临时构建日志已清理（*.log 本已被 .gitignore 忽略）

## FE-001 Final Static Closeout

- PHASE N 扫描（search_files，desktop/src/renderer）：from 'electron' / require('electron') / node:fs / child_process / ipcRenderer / safeStorage / process.env / shell.openExternal → 0 命中；localStorage 仅注释提及（无写入）、无 sessionStorage、无硬编码 Bearer/JWT、无 VITE token secret、无 axios、无直接 fetch（仅 TanStack Query refetch()）；ownerSubject 仅注释声明
- main/index.ts 安全基线核对：contextIsolation=true / nodeIntegration=false / sandbox=true / webSecurity=true；deny window.open；will-navigate 白名单；preload zero surface
- 写入范围仅 desktop/** + docs/frontend-current-task.md + docs/frontend-development-log.md + docs/frontend-autonomous-plan.md（已有）；server/、packages/api-client/**、docs/current-task.md 等 backend scope 零触碰
- Windows Git 最终审查：diff --check 干净、status 仅 untracked frontend 范围（详见最终报告）
- 最终状态：FE-001 IMPLEMENTED / AI WINDOWS RUNTIME VERIFIED / AWAITING USER REVIEW（dev smoke 已真实运行；视觉项与交互留待用户最终 review；不标 COMPLETE）

## FE-001 Pre-Commit Review Fix Checkpoint（2026-09-06，外部 reviewer 10 项）

- #1 Auth cache boundary：DevelopmentSession Apply/Clear 现在调用 queryClient.resetQueries()（先 setAccessToken/clear，再全量 reset：清上一 principal 已成功 data 并让 active query 以新 token 重新请求；不用 invalidate 因为要丢弃旧 data；token 不进 query key；不解析 JWT；不持久化）。新增 DevelopmentSession.test.tsx 3 项（Apply reset+refetch / Clear reset+refetch / tokenSession 值变化），afterEach 清全局 tokenSession 防污染。
- #2 生产 file:// navigation allowlist：新 src/main/navigation.ts 纯函数（rendererIndexPath / rendererIndexFileUrl / isAllowedFileNavigation 比较 protocol+host+pathname，忽略 hash）；main/index.ts isAllowedNavigation prod 分支只允许实际 packaged renderer index.html，loadFile 与 allowlist 同源于 rendererIndexPath()（单一路径来源）；任意 file:///C:/other-file 与 remote http 均拒绝。新增 navigation.test.ts 5 项；vitest include 扩展 src/main/**/*.test.ts。
- #3 SpaceScopeGuard：新 app/SpaceScopeGuard.tsx，路由嵌套 spaces/:spaceId → Guard（children: index→sources / sources / knowledge / knowledge/:id）；invalid id → inaccessible 零调用；getLearningSpace pending → loading；404/error → normalized；success → Outlet。与 AppShell 共享 queryKeys.space(spaceId) 同一 cache。新增 SpaceScopeGuard.test.tsx 3 项（404 断言 listSources/listKnowledgePoints/listKnowledgeCategories 未调用；invalid id 断言 getLearningSpace 未调用；success 渲染子路由）。
- #4 Spaces accessibility：Card 移除 onClick API（删除 card--interactive 样式）；SpacesPage 改用语义 <Link to="/spaces/{id}/sources"> 包 Card（.space-card-link CSS + focus-visible）；无 tabIndex+onKeyDown 模拟。新增测试：link role/href、Tab 两次可聚焦、无 id 空间渲染非导航卡。
- #5 Detail category name：KnowledgePointDetailPage 新增 categoriesQuery（复用 queryKeys.knowledgeCategories(spaceId)，从列表进入命中 cache）；"Category ID" 行改为 "Category" + 解析名；解析失败 fallback "#<id>"；category query 失败不阻塞 detail。测试 mock 补 listKnowledgeCategories data: []（消除 Query data cannot be undefined 警告）。
- #6 Optional ID safety：isNavigableId（finite positive）用于 create space 后导航、space card Link、point list 导航按钮（无 id → 静态项不可点击，绝不生成 /knowledge/undefined）；SourcesPage 行 key、两个 dialog 的 option key/value 加 fallback。
- #7 BACKEND CONTRACT REQUEST — ELECTRON ORIGIN / CORS：已写入 frontend-current-task.md（事实：dev origin 5173 → 8080 Authorization preflight；SecurityConfig 无 .cors()；Electron real backend flow 未验证；file:// Origin:null 非正式方案；禁止 webSecurity=false / ACAO:* / 盲目 Origin:null / IPC HTTP proxy；后续 ELECTRON-CORS-001 spike：custom protocol app://aistudy + explicit origin allowlist，符合 api-guidelines.md §19）。本分支不改 backend。
- #8 CSP 状态纠正：docs 不再宣称 production CSP 完全验证；production file/custom-protocol CSP 与 ELECTRON-CORS-001 一起最终确认；webSecurity=true 保持。
- #9 文档状态：改为 FE-001 IMPLEMENTED / WINDOWS TYPECHECK/TEST/BUILD/BOOT VERIFIED / AWAITING ELECTRON ORIGIN/CORS INTEGRATION SPIKE AND USER VISUAL REVIEW；明确 Electron boot smoke PASS ≠ real Backend HTTP integration PASS。
- #10 Windows runtime：typecheck PASS / test:run 31/31 PASS / build PASS（修复后全部复跑，真实输出）。
- 测试总数：17 → 31（新增 14 项：navigation 5 + Guard 3 + DevSession 3 + Spaces 3）；React Router future flag 警告良性保留（当前版本类型不支持）。

## FE-001-DEP-001 Electron Runtime Upgrade Checkpoint（2026-09-06）

- Before：package.json "electron": "^33.2.0"，lock exact 33.4.11（已装，EOL）；Windows node v24.11.1 / npm 11.6.2 / win32 x64
- Upgrade：npm install --save-dev "electron@^44"（Windows npm，EXIT=0）→ package.json ^44.2.0，lock exact 44.2.0（npm 生成，未手改）；engines node>=22.12.0（本机 24.11.1 满足）
- 二进制：postinstall 默认源下载失败（与 33 时代相同），ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/ 重跑 node node_modules/electron/install.js 补齐；dist/electron.exe 存在，path.txt=b'electron.exe' 无换行
- Breaking changes 审查（Electron 44 release notes）：影响面零交集（见 current-task「Electron Runtime」段）；安全 flags 未降级
- Runtime 回归（Windows Node）：typecheck PASS / test:run "8 passed (8) / 31 passed (31)" / build PASS（renderer 514.07 kB + 12.44 kB css）
- Dev boot smoke（ELECTRON_ENABLE_LOGGING=1, 55s）：electron-vite dev 起 → "start electron app..." → renderer "[vite] connected." → React mount → 无 startup crash / 无 Electron API deprecation / 无 CSP fatal
- 真实 versions（electron.exe 运行临时探针，探针已删）：electron=44.2.0 / chrome=152.0.7977.76 / node=24.20.0 / win32 x64
- CORS 未触碰：http://localhost:5173 → localhost:8080 真实业务请求仍 NOT VERIFIED，属 ELECTRON-CORS-001

## ELECTRON-CORS-001-A Production Origin + CSP Spike Checkpoint（2026-09-06）

- 目标：production renderer 从 file://（Origin:null）迁移到非 opaque 固定 origin app://aistudy，真实观察其 HTTP Origin 行为
- Custom scheme：protocol.registerSchemesAsPrivileged（app ready 前）：standard+secure+supportFetchAPI+corsEnabled；无 bypassCSP / allowServiceWorkers / webSecurity:false
- protocol.handle('app')：只服务 app://aistudy/**；resolveAppUrlPath 纯函数（new src/main/app-protocol.ts）防护 foreign host / malformed / decode 后 traversal（%2f 编码 separator 组合的 ..）逃逸 → 404；net.fetch(pathToFileURL) 流式
- 关键发现（WHATWG URL 语义）：%2e%2e 完全编码的 dot segment 被 standard-scheme URL parser 自身折叠，无法逃逸；真正需要 decode 防护的是 %2f 编码 separator 隐藏的 ..——已按此修正测试断言并验证
- Production load：loadURL('app://aistudy/')（file:// 完全弃用）；导航白名单仅 app://aistudy（hash 路由允许，file/http(s)/foreign host 拒绝）
- CSP 单一来源：index.html 的 meta CSP 已移除。关键架构修正：session.webRequest 不拦截 protocol.handle 响应 → prod CSP 由 handler 直接注入响应头（确定性生效），onHeadersReceived 仅 dev（http(s)）。cspFor 抽为纯函数（new src/main/csp.ts）+ 单测。prod 策略：script-src 'self'（无 unsafe-inline/eval）、style-src 'self' + style-src-attr 'unsafe-inline'（唯一 scoped 例外：React inline style attribute / category tree 缩进，runtime evidence）、connect-src 'self' http://localhost:8080、object-src/base-uri/frame-ancestors none
- Security flags 无回归：contextIsolation=true / nodeIntegration=false / sandbox=true / webSecurity=true；preload zero surface；无 IPC HTTP proxy
- REAL ORIGIN PROBE（临时 8080 probe server，ephemeral 已删）：Electron 44.2.0 生产模式真实启动两轮，probe 记录：
  - `OPTIONS /api/v1/spaces | Origin=app://aistudy | ACRM=GET | ACRH=content-type | SF-Mode=cors`
  - `GET /api/v1/spaces | Origin=app://aistudy`（两轮，含 TanStack retry）
  - secure custom origin → localhost HTTP：未被 mixed-content 阻止，请求实际到达 → PASS
  - 运行时日志确认 CSP 注入（临时日志已移除）
- Windows runtime：typecheck PASS / test:run "9 passed (9) / 43 passed (43)" / build PASS（renderer 514.07 kB + 12.44 kB css）
- Backend 需求（ELECTRON-CORS-001-B，backend scope）：CorsConfigurationSource allowlist = http://localhost:5173（dev）+ app://aistudy（prod）+ Admin Web 单独；real authenticated flow 待 CORS-B + integration C（需有效 JWT）
- 注：desktop/electron.vite.config.zip（107KB，11:12 创建，desktop 配置+src 快照，含已删除的 navigation.ts）来源不明、非本任务创建，保留未动，待用户确认处置

## ELECTRON-CORS-001-C Real Desktop/Backend Integration Checkpoint（2026-09-06）

- 目标：真实 Electron Renderer → Chromium CORS → Spring Security → JWT → Controller → MyBatis → MySQL 全链路，通过真实 Desktop UI 读写正式业务数据
- PART 1 harness：server/src/test/java/com/aistudy/server/e2e/E2eBackendHarness（@SpringBootTest DEFINED_PORT 8080 + flyway-it，类名非 *Test 不被 full suite 执行；注入 SpikeJwtTokenService 生成 subject=desktop-e2e-user 的 5 分钟 token 写 %TEMP%\aistudy-desktop-e2e-token.txt，CountDownLatch keep-alive）
- HTTP 层预检（PowerShell 真实请求）：GET /api/v1/spaces + Bearer + Origin app://aistudy → **200 + ACAO=app://aistudy + []**
- PART 3 dev-origin UI flow（Electron 44 dev + --remote-debugging-port=9222，CDP 驱动真实 renderer DOM，DOM 状态逐证据记录）：
  - Development Session 注入 token → Apply → "Token applied (in-memory only)."；spaces 真实读取（空态）
  - Create Learning Space E2E-C-Space-1788668853157 → 自动导航 #/spaces/1/sources（响应 id 驱动）
  - Create Source E2E-C-Source-1788668853157 → 表格显示 DESKTOP_UPLOAD / REGISTERED / 时间
  - Create Category E2E-C-Category-1788668853157 + -1788668901507 → Category 树显示
  - Create KnowledgePoint E2E-C-Point-1788668901507（content "Integration C content"）→ 列表 DRAFT
  - 打开 detail #/spaces/1/knowledge/1 → DRAFT badge + Publish 按钮 + Content 显示
  - Publish → header PUBLISHED + "Published at 2026/09/06 12:28" + Publish CTA 消失
- PART 4 DB 证据（E2eDbEvidence 只读 SELECT *）：learning_space 1 行 owner_subject=desktop-e2e-user；source 1 行 created_by_user_id=desktop-e2e-user + DESKTOP_UPLOAD；knowledge_category 2 行（root）；knowledge_point 1 行 status=PUBLISHED + published_at=2026-09-06T12:28:30.207760
- PART 5 production probe（临时 runner 加载真实 app://aistudy renderer + executeJavaScript 带 JWT fetch）：**status 200 + body=[{id:1,name:E2E-C-Space-...}] JSON 可读**（CORS 检查真实通过；JS 读不到 ACAO 是预期——Backend exposedHeaders 空；HTTP 层已另证 ACAO=app://aistudy 回显）
- PART 7 安全无回归：contextIsolation/nodeIntegration=false/sandbox/webSecurity=true 保持；无 Origin:null/ACAO:*/generic IPC proxy/token 持久化
- PART 8 cleanup：Electron + harness 已停；%TEMP% token 文件已删；临时 runner/driver/日志已删；E2E-C-* 数据保留作验收 fixture
- PART 9 回归：frontend typecheck PASS / test:run 43/43 PASS / build PASS；backend CorsContractIntegrationTest 9/9 + SpikeSecurityBoundaryTest 10/10 PASS（E2e 类编译通过、不被 full suite 执行）
- 观察记录：detail 页未选分类时显示 "Category #null"（categoryId=null 时 UI 文案小瑕疵，功能正确；候选后续修复）

## FE-001-FINAL-POLISH-001 Checkpoint（2026-09-06）

- 修复：KnowledgePointDetailPage 的 categoryId=null 时显示 "Category #null"——改为 isPositiveId（finite positive）判断：有效 id + categories 命中 → name；有效 id + 未命中 → #<id>；null/undefined/非正数 → "—"（项目空值规范，与 formatDateTime 一致）。绝不再出现 #null/#undefined/#NaN
- 测试：KnowledgePointDetailPage.test.tsx +3（null → "—" 且无 #null/#undefined/#NaN；id 命中 → category name；id 未命中 → #<id>）——46/46 PASS
- Windows 回归：typecheck PASS / test:run 46/46 PASS / build PASS
- Cleanup：desktop/electron.vite.config.zip 已不存在（用户处理）；docs/frontend-autonomous-plan.md 已删除（无人值守执行计划，非产品文档，不提交）；保留 frontend-current-task.md + frontend-development-log.md
- 最终状态：FE-001 IMPLEMENTED / ELECTRON ORIGIN/CORS REAL INTEGRATION VERIFIED / WINDOWS RUNTIME VERIFIED / READY FOR USER FINAL REVIEW AND COMMIT（COMPLETE 留待用户 commit 后）

## FE-001.5 Phase 1 — Baseline Audit Checkpoint（2026-09-06）

- 权威基线确认（Windows Git）：branch=feat/fe-001、HEAD=ec115be、worktree clean（唯一 untracked=本计划文件，非产物）
- Windows Node 确认：v24.11.1 / win32；Electron 44.2.0（DEP-001 后基线）
- 审计完成（见 frontend-current-task.md "FE-001.5 Baseline Audit"）：
  - 路由/结构/query 契约/error 模型/DevSession/组件/表单/Electron 安全/CSP 现状全部盘点
  - 9 test 文件 / 46 tests 基线确认（dev-log 上一任务 FINAL-POLISH-001 记录 46/46 PASS）
- 关键发现（后续 Phase 处理）：isNavigableId 三处重复（P3）；401 文案无 dev/prod 区分、网络/403/5xx 文案待对齐（P4）；无按错误类 retry 策略（P5）；DevSession 缺 whitespace 禁用/编辑清状态/aria-live（P6）；backend 绿点误导（P7）；Dialog 无焦点管理/focus trap/focus return（P9）；无 permission deny-by-default（P19）；CSP style-src-attr 例外可消除（P21）；page__header 4 处重复（P17 评估）
- 基线 gate（typecheck/test/build）后台运行中，结果见下个 checkpoint

## FE-001.5 Phase 2 — Static Quality Gate Checkpoint（2026-09-06）

- 新增 ESLint 9 flat config（eslint.config.mjs）：@eslint/js + typescript-eslint v8.69 + eslint-plugin-react-hooks v5.2 + eslint-plugin-react-refresh v0.4.26；规则覆盖 unused vars/imports、no-explicit-any:error、hooks rules、no-fallthrough/no-unreachable、fast-refresh 导出契约；renderer 仅 browser globals（Node 全局由 P26 静态扫描兜底）；无 Prettier/风格化配置
- 安装：Windows npm `npm install --save-dev eslint@^9 typescript-eslint@^8 eslint-plugin-react-hooks@^5 eslint-plugin-react-refresh@^0.4` → INSTALL_EXIT=OK；package.json 新增 lint / lint:fix scripts
- 修复 1 处 lint error：router.tsx 同时导出组件 + router 常量（react-refresh/only-export-components）→ NotFoundPage 拆到 app/NotFoundPage.tsx，router.tsx 只导出 router
- 结果：`npm run lint` → LINT_EXIT=OK，0 problems（真实输出）
- Baseline gate（后台已跑完）：typecheck OK / test:run "9 passed (9) / 46 passed (46)" / build OK（renderer 514.18 kB + 12.44 kB css）——全部 Windows Node 实测

## FE-001.5 Phase 3–9 Checkpoint（2026-09-06）

- P3 Type safety：新增 lib/ids.ts（isPositiveId 整数守卫 + parsePositiveIdParam 严格十进制解析，拒绝 hex/exponent/NaN/Infinity/越界/空白）；SpacesPage/SourcesPage/KnowledgePage/DetailPage/SpaceScopeGuard/AppShell 全部改用它——消灭 Number(routeParam) 与三处重复守卫；ids.test.ts 13 项边界用例
- P4 Error semantics：normalizeApiError(error, {isDev})；401 dev=「当前开发会话未认证或令牌已失效。请更新 Development Session 令牌。」/ prod=「Authentication integration pending.」；403=「当前会话无权执行此操作。」；5xx=「服务器暂时无法完成请求，请稍后重试。」；network=「无法连接到后端服务。请确认服务已启动后重试。」；api-error.test.ts 12 项（含反 IDOR 文案、不泄漏 stack/body）
- P5 Query resilience：lib/query-retry.ts shouldRetryQuery——401/403/404 永不重试，network/5xx 有界重试（QUERY_RETRY_MAX_ATTEMPTS=2）；main.tsx QueryClient：retry=shouldRetryQuery、staleTime 10s、gcTime 5min、refetchOnWindowFocus=false、refetchOnReconnect=true、mutations.retry=false；query-retry.test.ts 7 项；invalidate 均为 space-scoped key 无跨界
- P6 DevSession：Apply 空/纯空白禁用；输入即清 stale status；aria-live status（applied/cleared）；autoComplete=off；无持久化断言测试；+7 tests
- P7 Backend indicator：绿点删除，改中性「API target: http://localhost:8080」（无 health probe 不假装 online）；CSS .dot/.app-shell__backend 移除
- P8 Shell：NotFoundPage 拆独立文件（router.tsx 仅导出 router）；footer 改「AIStudy Desktop」；nav focus-visible + active hover；AppShell.test.tsx 6 项（landmarks/aria-current/space name/无 space 无子链接/不可访问文案）
- P9 Dialog/form：Dialog 焦点管理（初始聚焦首个 editable field、Tab/Shift+Tab trap、关闭后焦点回 trigger、busy 时 Escape/overlay/close 全禁用、aria-labelledby + tabIndex=-1）；4 个 create dialog 传 busy=pending；lib/use-form-error.ts 编辑即清 stale server error；Dialog.test.tsx 14 项
- 修复 2 个测试问题：Dialog 初始聚焦误落 button（改 input/select/textarea 优先 + panel tabIndex=-1）；AppShell 测试 harness 缺 spaces/:spaceId 路由（No routes matched）
- 验证（Windows Node 实测）：typecheck OK / lint 0 problems / test:run "14 passed (14) / 104 passed (104)"

## FE-001.5 Phase 10–28 Checkpoint（2026-09-06）

- P10 Spaces：+9 tests（whitespace/trim/create failure/pending/Escape 阻断/no-id 不导航/403/network+retry/5xx/missing optionals）
- P11 Sources：+7 tests（empty/rows optionals/placeholder/invalid id 零调用/create error/pending/stale error 清除）
- P12 Category graph：+9 tests（deep hierarchy/self-cycle/3-node cycle exactly once/dup parent/dup ids/missing ids/2000 浅层/600 深层/order）
- P13 Category create：strict optional-int 解析（lib/ids.ts parseOptionalInteger——hex/exp/whitespace/NaN/越界→undefined，签名整数保留）；+5 tests
- P14 Point list：+3 tests（optionals/category lookup/order/static row）
- P15 Point create：+4 tests（blank 禁用/trim+内容保留内部空白 payload/invalidate/error）
- P16 Detail+publish：+10 tests（invalid id 零调用/loading/401-403-404-500/network/publishedAt 缺失/content 空白保留/pending 防重复/publish error/成功后 refetch→PUBLISHED）
- P17 Primitives：PageHeader 提取（4 页复用）；ErrorState 用 Button
- P18 CSS：单一 :focus-visible 契约；overflow-wrap:anywhere 长文本组；knowledge grid min-width:0；header/dev-session wrap；category 缩进改嵌套 ul padding（消灭 inline style）
- P19 Electron permissions：main/permissions.ts 纯函数 deny-by-default + setPermissionRequestHandler/setPermissionCheckHandler 全拒 + will-attach-webview deny；+3 tests
- P20 Protocol：+8 tests（query/hash 不影响解析、%5c 编码 separator、double-encoding 惰性、javascript:/data:/port/empty-host）
- P21 CSP：style-src-attr 'unsafe-inline' 全移除（prod+dev）——React inline style 已归零，prod 现为纯 self 样式；csp.test 更新断言
- P22 Test infra：createTestQueryClient()（retry:false + gcTime:Infinity），DevSession 测试改用
- P23 Regression：NotFoundPage test、guard loading/network+retry/it.each 非法 id 零调用
- P24 Coverage：@vitest/coverage-v8@2.1.9 + test:coverage script + vitest.config coverage（v8/text；排除测试与 test infra）；基线 Stmts 83.05% / Branch 81.39% / Funcs 85.82% / Lines 83.05%
- P25 Fake-data audit：renderer 生产源码 0 命中（仅注释提及）
- P26 Forbidden-pattern scan：renderer 0 命中（electron/node:fs/child_process/ipcRenderer/safeStorage/process.env/localStorage/axios/XHR 均无）；main 无 webSecurity:false 等弱 flag
- P27 Dependency audit：npm ls --depth=0 干净（electron 44.2.0 保持）；npm audit（官方 registry）：10 项全为 dev-only 工具链，修复全 semver-major → 按计划记录不升级；npmmirror registry 无 audit endpoint（404 NOT_IMPLEMENTED）已记录
- P28 README：重写为完整 developer guide（前置/Windows Node 要求/全部命令/API base/DevSession 语义/token 不持久化/SPIKE JWT 限制/custom origin/CORS/401 vs offline 识别/electron mirror 问题/scope 与 deferred）
- 验证（Windows Node 实测）：typecheck OK / lint 0 problems / test:run "16 passed (16) / 177 passed (177)" / test:coverage OK（数值见 current-task）

## FE-001.5 Phase 29–34 + Stretch A–G Checkpoint（2026-09-06）

- P29 最终 Windows validation loop（当前状态实测）：lint OK / typecheck OK / test:run "17 passed (17) / 186 passed (186)" / test:coverage OK / build OK（renderer index-DEJZG_3Q.js + 13.30 kB css）
- P30 dev smoke（CDP DOM 证据，Electron 44.2.0）：origin http://localhost:5173/#/spaces（hash 路由生效）；DEV_SESSION_VISIBLE（DEV gate 正确）；无 auth placeholder；body 含「API target: http://localhost:8080」中性标签 + 后端离线 network 错误 + 重试；log 含 "[vite] connected."；0 CSP/preload/uncaught
- P31 最终 production smoke（当前 bundle，CDP DOM 证据）：origin app://aistudy/#/spaces；DEV_SESSION_HIDDEN；「Authentication integration pending」；CSP header 实测与 cspFor(false) 完全一致且无 style-src-attr/unsafe-inline；fetch('app://aistudy/') 同源可读 headers；0 console violation（含 ErrorBoundary 包装后的最终构建）
- P32 最终源码审查：无 console.log/TODO/注释代码/散落 Number()（仅 lib/ids.ts 严格解析器内）；无 div-as-button（仅 Dialog stopPropagation + point 列表真 button）；P25/P26 静态审计 0 命中
- P33 文档交接：current-task 状态 = FE-001.5 HARDENING IMPLEMENTED / WINDOWS LINT/TYPECHECK/TEST/COVERAGE/BUILD VERIFIED / DEV/PRODUCTION SMOKE VERIFIED / AWAITING USER REVIEW AND COMMIT；含变更范围/测试数/coverage/运行证据/残余债/无新增 BACKEND CONTRACT REQUEST/SourceAsset 推迟声明
- P34 Git review（Windows Git）：diff --check 干净；status 范围仅 desktop/** + docs/frontend-*；无 node_modules/out/.env/token/zip 泄漏；零 add/commit/push
- Stretch A：ErrorBoundary（class 组件 + getDerivedStateFromError + DEV-only console 诊断 + 生产 UI 不暴露 stack）+ main.tsx 包裹 + 5 tests（含 transient 恢复 harness：数据修复后重试成功、持久错误重捕）
- Stretch B：已由既有 pending 阻断测试覆盖（Escape/overlay/close 在 pending 时全禁用——Spaces/Sources/Dialog 测试断言）；路由切换仅卸载 dialog 不会误提交
- Stretch C：+4 torture tests（200 字空间名、400 字描述、300 字标题 + 500 字摘要 + 200 字分类名、40 层分类链渲染不崩）
- Stretch D：DetailPage category 名解析改 Map 查找（去 find()）；KnowledgePage 本就 O(n) Map 构建；无 O(n²) 残留；按计划不引入虚拟化
- Stretch E：唯一测试 stderr 噪音 = React Router future-flag 警告（良性、文档化）+ preload 空 chunk 提示（预期）；不全局压制
- Stretch F：react-router-dom 6.30.6 类型中无 v7_startTransition/v7_relativeSplatPath（grep 实测）→ 按计划文档化不迁移
- Stretch G：文案一致性审查——heading/主操作英文、状态/错误中文、重试按钮随中文错误面板；各界面内部一致，不盲翻
- Cleanup：desktop/*.log 全部删除（含 fin-*/zf-* 等 gate 日志）；%TEMP% fe15-* 探针/编排脚本/日志/pid 文件全部删除；npm-audit.json 已删；最终 smoke 0 violation 后删日志
- 最终验证（真实输出）：lint OK / typecheck OK / test:run "17 passed (17) / 186 passed (186)" / coverage Statements 84.06% / Branches 82.5% / Functions 86.36% / Lines 84.06% / build OK

## FE-001.5 PRE-COMMIT REVIEW FIX-01 Checkpoint（2026-09-06）

外部 reviewer 实际检查 package.zip 源码后的提交前修复（仅 desktop/** + 两份 frontend docs）。

- FIX-01-1 API base/CSP 单一契约：新增 src/shared/api-config.ts（resolveApiBaseUrl 只收 http(s)、非法回退 default、拒绝 * 主机与 http:///x 宽松解析；apiOriginFromBaseUrl 只取 scheme+host+port）；renderer lib/api-client.ts 与 main csp.ts/index.ts 共用（electron-vite main build envPrefix 含 VITE_，tsconfig.node.json types 加 vite/client；tsconfig node/web include 加 src/shared）；cspFor 改 options 签名；api-config.test.ts 12 项 + csp.test.ts 扩展 5 项（默认/custom http/https/非法回退/永不 connect-src *）；.env.example 与 README 契约同步
- FIX-01-2 nested nav：AppShell Spaces NavLink 加 end；+3 项单 active 断言 + 1 项"至多一个 active"不变式
- FIX-01-3 engines：package.json engines.node >=20 → >=22.12.0；README prerequisites 同步去矛盾
- FIX-01-4 ESLint 直接依赖：Windows npm install -D @eslint/js@^9 globals（@eslint/js 10 需 eslint 10 故锁 9.x）→ @eslint/js@9.39.5 / globals@17.12.0；npm ls --depth=0 干净；lock 由 npm 生成未手改
- FIX-01-5 ID parser 分离：ids.ts 新增 parseOptionalPositiveId（拒绝 0/负数/float/NaN/Infinity/hex/exponent/garbage/overflow）；KnowledgePage parentId/categoryId 改用它（sortOrder 保持 parseOptionalInteger）；两个 category select 只渲染 isPositiveId 的 option；ids.test.ts +7、KnowledgePage.test.tsx +2
- FIX-01-6 AppShell 语义：404 → Space not found or inaccessible；401/403/network/5xx → Space unavailable；child links 仅 space query 成功且有有效 space 时渲染（pending/error 隐藏）；SpaceScopeGuard 未动；AppShell.test.tsx +6 项（404/network/401/5xx/pending/success + 单 active 组）
- FIX-01-7 StatusBadge：includes() 子串匹配改 exact map（DRAFT/PUBLISHED/ACTIVE，其余 unknown）；新增 StatusBadge.test.tsx 10 项（INACTIVE/UNPUBLISHED/REGISTERED/undefined）
- FIX-01-8 静态审查：renderer 生产源码 0 命中（electron/node:fs/child_process/ipcRenderer/safeStorage/process.env/localStorage/axios/XHR/fetch，仅注释）；deps 无 axios/Redux/Zustand/UI framework；Electron 安全四件套不变
- FIX-01-9 Windows 验证（Windows Node 实测）：lint OK / typecheck OK / test:run "19 passed (19) / 230 passed (230)"（186 → 230，+44 项回归测试）/ test:coverage OK（Statements 84.41% (1506/1784) / Branches 85.16% (396/465) / Functions 86.56% (116/134) / Lines 84.41%，api-config.ts 100%）/ build OK（renderer index-DphqYEBd.js 521.05 kB + 13.30 kB css；out/main 实测含 connect-src 'self' http://localhost:8080）
- FIX-01-9 dev smoke（CDP DOM 证据，后端离线，Electron 44.2.0）：/#/spaces → 1 active（Spaces aria-current=page）；/#/spaces/7/sources pending → 0 active 无 child links；settled → sidebar「Space unavailable」+ guard 网络错误文案 + 重试、0 active 无 child links；返回 → 1 active；dev CSP header 实测 connect-src 'self' http://localhost:5173 ws://localhost:5173 http://localhost:8080（无通配无路径）；log 0 CSP/preload/uncaught（仅良性 React Router future-flag 警告）；custom CSP 由纯函数单测证明，未另启 backend port
- FIX-01-10 Git review（Windows Git）：diff --check 干净；status 范围仅 desktop/** + docs/frontend-*；未 commit；smoke 产物（log/ps1/cjs）已删除

## FE-001.5 PRE-COMMIT REVIEW FIX-01 Re-validation Checkpoint（2026-09-06，二次全量验证）

外部 reviewer 再次要求执行 FIX-01 后的完整 Windows 验证，确认源码修复可复现。

- 源码核对：7 项核心修复（api-config 契约 / nav end / engines / ESLint deps / parseOptionalPositiveId / AppShell 语义 / StatusBadge exact）均已在源码与测试中落地，无遗漏
- 静态审查：renderer 生产源码 electron/node:*/axios/localStorage 等仅注释提及；Electron 四件套 contextIsolation=true / nodeIntegration=false / sandbox=true / webSecurity=true 不变；package.json 无 axios/Redux/Zustand/UI framework
- Windows Node v24.11.1 / npm 11.6.2 / win32 实测：lint OK / typecheck OK / test:run 230/230（19 文件）/ coverage Statements 84.41% (1506/1784) Branches 85.16% (396/465) Functions 86.56% (116/134) Lines 84.41% / build OK（main 6.44 kB + preload 0.01 kB + renderer index-DphqYEBd.js 521.05 kB + 13.30 kB css）
- npm ls --depth=0 干净（@eslint/js@9.39.5 + globals@17.12.0 为直接 devDependencies）
- out/main/index.js 实测含 DEFAULT_API_BASE_URL="http://localhost:8080" + resolveApiBaseUrl + apiOriginFromBaseUrl 链路
- Dev smoke：electron-vite dev 起服成功（5173 + start electron app...），进程存活 25s 后正常终止；nested route 单 active 由 AppShell.test.tsx 4 项 aria-current 断言覆盖（/spaces、/spaces/7/sources、/spaces/7/knowledge、至多一个 active）；custom API CSP 由 api-config.test.ts 11 项 + csp.test.ts custom-origin 5 项纯函数测试充分证明
- Git：diff --check 无 whitespace error（仅 LF→CRLF 规范化 warning）；status 范围仅 desktop/** + docs/frontend-*；diff --stat 36 files / +4938 / −710；未 commit
- 结论：FE-001.5 PRE-COMMIT REVIEW FIX PASS — READY FOR USER REVIEW AND COMMIT
