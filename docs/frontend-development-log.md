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
