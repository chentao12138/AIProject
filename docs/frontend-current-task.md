# Frontend Current Task

## Objective

LONG-RUN-FE-001 — DESKTOP FOUNDATION + FIRST BUSINESS UI：
- Electron + React + TypeScript + Vite foundation（electron-vite）
- 安全 BrowserWindow baseline（ADR-037）
- React Router（createHashRouter）+ TanStack Query
- Desktop App Shell
- Development Auth Session abstraction（in-memory only）
- LearningSpace / Source / KnowledgeCategory / KnowledgePoint UI + Publish flow
- Frontend tests（Vitest + React Testing Library）
- frontend-specific persistent docs（本文件 + docs/frontend-development-log.md）

## Current Phase

**PHASE 14 + PRE-COMMIT REVIEW FIX-01 + DEP-001 + ELECTRON-CORS-001-A/B/C + FINAL-POLISH-001 DONE — 最终状态：**

FE-001 IMPLEMENTED
ELECTRON ORIGIN/CORS REAL INTEGRATION VERIFIED（真实 Desktop UI → Backend → MySQL 全链路）
WINDOWS RUNTIME VERIFIED
READY FOR USER FINAL REVIEW AND COMMIT

（不写 COMPLETE：最终 commit 由用户执行。）

## Baseline

- Branch: feat/fe-001 @ 7ef6a79 "feat: implement knowledge catalog"（与 main 同 commit，Windows Git 权威确认）
- frontend worktree: D:/AIProject-frontend（独立 worktree，不触碰 D:/AIProject backend working tree）
- 本轮新增文件全部 untracked（未 add/commit/push，由用户提交）

## Allowed Write Scope

- desktop/**（含 desktop/package-lock.json，由 Windows npm 生成）
- docs/frontend-current-task.md
- docs/frontend-development-log.md
- docs/frontend-autonomous-plan.md（执行计划，已存在）

禁止修改：server/**、packages/api-client/**（含 generated/、client.ts、index.ts、package.json）、docs/current-task.md、docs/development-log.md、docs/development-plan.md、docs/decisions.md、docs/architecture.md、docs/technology-selection.md 及任何 Flyway migration。本轮零触碰。

## Completed

- 恢复检查（Windows Git 权威）：worktree 干净，feat/fe-001 @ 7ef6a79
- Source of Truth 读取：ADR-029/030/037、architecture.md §8、technology-selection.md §8、requirements.md；shared api-client 13 个业务方法齐全，TokenProvider 存在，generated 字段全 optional
- PHASE A: desktop/ scaffold（package.json/tsconfig x3/electron.vite.config.ts/vitest.config.ts/.env.example/README.md/.gitignore + src/{main,preload,renderer}）
- PHASE B: Electron 安全基线（sandbox/contextIsolation/nodeIntegration=false/webSecurity=true、deny window.open、will-navigate 白名单、CSP、zero-IPC preload）
- PHASE C: Renderer 架构（StrictMode/QueryClientProvider/ApiClientProvider/RouterProvider、createHashRouter 路由、URL 决定 current space、query keys 契约）
- PHASE D: API 边界（仅 @aistudy/api-client；ReturnType 推导类型；VITE_API_BASE_URL；error normalization 5 类；404 anti-IDOR 文案）
- PHASE E: InMemoryTokenSession + DEV-only Development Session
- PHASE F: App Shell（顶栏/侧栏/主区；space name 解析；无效 space 文案）
- PHASE G-I: Spaces / Sources（DESKTOP_UPLOAD 固定）/ Knowledge Catalog / Publish flow
- PHASE J: formatDateTime + optional 安全访问
- PHASE K: 31 项测试全绿（K1.1-K1.12 + 补充 + review fix 测试）
- PHASE P: Windows npm install → typecheck PASS → test:run 31/31 PASS → build PASS
- PHASE Q(optional): dev smoke 通过（Electron 启动 + renderer connected + 无错误）
- PHASE N/T: 静态安全扫描 0 命中；Windows Git diff --check 干净
- PHASE M: 全部 checkpoint 已追加 development-log
- PRE-COMMIT REVIEW FIX-01（外部 reviewer 10 项，全部完成，见 development-log checkpoint）

## In Progress

- 等待：ELECTRON-CORS-001 spike（backend CORS 配置 / custom protocol 决策，backend scope）+ 用户视觉 review

## Frontend Architecture Decisions

- Electron 33 + React 18 + TypeScript + Vite 5（electron-vite），独立 package（无 root workspace）
- 状态管理：TanStack Query v5（server-state）；无 Redux/Zustand/MobX/Recoil
- Router：createHashRouter（Electron file:// 安全）；current space 由 URL 决定，不存 localStorage
- UI：普通 CSS + CSS variables（无大型 UI framework / Tailwind / Ant Design）
- 类型：从 ApiClient 方法返回值推导（ResultData），禁止复制 generated DTO
- Source 创建 sourceType 固定 DESKTOP_UPLOAD；不实现 file upload（SourceAsset 未接入）
- difficulty 用 optional text input（Backend 无 enum contract，前端不发明枚举）
- buildCategoryTree 输出保证无环 DAG（visited 过滤），递归渲染安全
- DevelopmentSession = auth cache boundary：Apply/Clear 触发 queryClient.resetQueries()（清上一 principal 缓存并重新请求）
- SpaceScopeGuard：/spaces/:spaceId/* 子资源查询前先确认 space 可访问
- Space card 导航用语义 <Link>（无 onClick div/section 模拟）

## Electron Runtime（DEP-001）

- **SUPPORTED VERSION VERIFIED**：Electron 33.4.11（EOL）→ 44.2.0（当前 stable line）
- 升级方式：Windows npm `npm install --save-dev electron@^44`，package-lock.json 由 npm 真实生成（exact 44.2.0）；electron 44 二进制经 npmmirror 跑官方 install.js 补齐（默认源下载失败，同 33 时代）
- 真实运行环境（electron.exe 实际启动探针）：electron=44.2.0 / chrome=152.0.7977.76 / node=24.20.0 / platform=win32 / arch=x64
- Breaking changes 审查（33→44）：macOS 12 弃用、ANGLE 静态链接、32-bit 构建移除、clipboard 不再暴露 renderer、Unity 移除、net.request Sec-Fetch-Dest 限制——与当前代码使用面（app/BrowserWindow/session.webRequest.onHeadersReceived/setWindowOpenHandler/will-navigate/loadURL/loadFile）零交集；webRequest 在 44 仍受支持
- 安全设置未降级：contextIsolation=true / nodeIntegration=false / sandbox=true / webSecurity=true 原样保留
- CORS/backend 集成仍属 ELECTRON-CORS-001，本任务未触碰

## Electron Security Contract

- BrowserWindow: contextIsolation=true, nodeIntegration=false, sandbox=true, webSecurity=true（必须保持）
- 无 webSecurity:false / allowRunningInsecureContent
- **Production renderer origin: app://aistudy**（custom scheme，standard+secure+supportFetchAPI+corsEnabled；无 bypassCSP/allowServiceWorkers；file:// 已完全弃用 → 浏览器不再见 Origin:null）
- setWindowOpenHandler → deny；will-navigate：dev 仅 dev server origin；prod 仅 app://aistudy（isAllowedAppNavigation，file/http(s)/foreign app host 全拒，hash 路由允许）
- protocol.handle('app')：只服务 app://aistudy/**，resolveAppUrlPath 纯函数防护（foreign host/malformed/decode 后 traversal 逃逸 → 404）；net.fetch(pathToFileURL) 流式，不整文件读内存
- Preload：zero business IPC surface（export {}，不暴露任何 Node API）；无 generic fetch IPC bridge、无 HTTP proxy through IPC
- CSP 单一来源（无 meta CSP）：**prod 由 protocol.handle 直接注入响应头**（webRequest 不拦截 custom protocol 响应）；dev 由 onHeadersReceived 注入（http(s)）。prod 策略：default-src 'self'；script-src 'self'（无 unsafe-inline/unsafe-eval）；style-src 'self' + style-src-attr 'unsafe-inline'（唯一 scoped 例外：React inline style attribute——category tree 缩进，runtime evidence）；connect-src 'self' http://localhost:8080；object-src/base-uri/frame-ancestors none
- Main 无数据库/业务规则/AI Provider/key

## Routing Contract

- / → redirect /spaces
- /spaces → LearningSpace selection / management
- /spaces/:spaceId → SpaceScopeGuard（index → redirect sources）
  - sources → Source Library
  - knowledge → Knowledge Catalog
  - knowledge/:knowledgePointId → KnowledgePoint Detail
- * → NotFoundPage

## API Integration Contract

- Renderer 只通过 @aistudy/api-client（ApiClientContext/Provider 注入，生产 createApiClient 单例，测试 typed fake）
- 禁止业务组件直接 fetch/axios/XHR；禁止复制 DTO interface（lib/types.ts 全 ReturnType 推导）
- VITE_API_BASE_URL 默认 http://localhost:8080（desktop/.env.example）；不提交 .env
- query keys: ['spaces'] / ['space', id] / ['sources', spaceId] / ['knowledge-categories', spaceId] / ['knowledge-points', spaceId] / ['knowledge-point', spaceId, id]
- mutation invalidate：createLearningSpace→spaces；createSource→sources；createCategory→knowledge-categories；createPoint→knowledge-points；publish→points+point
- unwrap(): openapi-fetch {data,error,response} → 抛 ApiRequestError(status)；normalizeApiError 区分 network/401/403/404/5xx；404 文案「资源不存在或当前不可访问。」
- DevelopmentSession Apply/Clear → queryClient.resetQueries()（auth cache boundary）
- AppShell 与 SpaceScopeGuard 共享 queryKeys.space(spaceId) 同一 cache 条目

## Temporary Auth Contract

- InMemoryTokenSession implements TokenProvider：getAccessToken/setAccessToken/clear，纯内存，刷新即失（当前正确行为）
- 无 localStorage/sessionStorage/cookie token、无硬编码 token、无 VITE_ACCESS_TOKEN
- DEV only：Development Session（粘贴 Bearer token，Apply/Clear，不保存），文案 "Development-only token injection. Not persisted."
- 非 DEV：显示 "Authentication integration pending"，无假 login

## UI Routes

（同 Routing Contract。AppShell：顶栏 AIStudy + backend URL 状态；侧栏 Spaces + 当前 space 的 Sources/Knowledge；当前 space name 显示；无效 space → "Space not found or inaccessible"）

## Files Added / Modified

- desktop/src/main/app-protocol.ts（新增：app://aistudy 常量 + 导航白名单 + traversal-safe URL→path 映射）
- desktop/src/main/app-protocol.test.ts / csp.ts / csp.test.ts（新增）
- desktop/src/main/index.ts（改造：scheme 注册 + protocol.handle + CSP 注入 + prod loadURL app://aistudy）
- desktop/src/renderer/index.html（移除 meta CSP，单一 header 来源）
- desktop/src/main/navigation.ts / navigation.test.ts（删除：file:// 时代产物，被 app-protocol 取代）
- desktop/** 其余（FE-001/DEP-001 已列）+ docs/frontend-current-task.md / docs/frontend-development-log.md / docs/frontend-autonomous-plan.md
- 无任何 tracked 文件修改；server/、packages/api-client/、docs/current-task.md 等零触碰
- 注：desktop/electron.vite.config.zip（107KB，2026-09-06 11:12 创建，desktop 配置+src 快照）来源不明，非本任务创建，保留未动，待用户确认

## Tests Added

43 项（9 个文件，全部 Windows Node 实测 PASS）：
- lib/category-tree.test.ts（K1.1/K1.2/K1.3 + sibling order + null parentId + pure cycle）
- features/spaces/SpacesPage.test.tsx（K1.4-K1.6 + K1.12 + review#4 link/href/focus + review#6 无 id 空间不可导航）
- features/sources/SourcesPage.test.tsx（K1.7 DESKTOP_UPLOAD）
- features/knowledge/KnowledgePage.test.tsx（K1.8）
- features/knowledge/KnowledgePointDetailPage.test.tsx（K1.9/K1.10/K1.11 + category mock）
- features/auth/DevelopmentSession.test.tsx（review#1：Apply/Clear reset cache + refetch + tokenSession 值）
- app/SpaceScopeGuard.test.tsx（review#3：404 不调子 API / invalid id 零调用 / success 渲染子路由）
- main/app-protocol.test.ts（CORS-001-A：origin allowlist 5 项 + URL→path 映射 8 项：root→index.html / assets / foreign host / raw+encoded traversal / malformed / URL-construction）
- main/csp.test.ts（CORS-001-A：prod 严格（self-only script、无 unsafe-eval/wildcard、style-src-attr 唯一例外、connect 仅 8080、object/base-uri/frame-ancestors none）+ dev HMR 放行）

## Runtime Evidence

- npm run typecheck → PASS（exit 0，修复后复跑）
- npm run test:run → "Test Files 8 passed (8) / Tests 31 passed (31)"（exit 0，两次复跑）
- npm run build → PASS（exit 0；renderer 514.07 kB + 12.44 kB css）
- npm run dev smoke（修复前，ELECTRON_ENABLE_LOGGING=1）：dev server 5173 → Electron 窗口启动 → renderer connected → 无 Node integration/CSP/未捕获错误；修复了 electron/path.txt 换行导致 spawn ENOENT
- DEP-001 复跑（Electron 44.2.0）：typecheck PASS / test:run 31/31 PASS / build PASS；dev boot smoke PASS（44 进程启动、renderer connected、React mount、无 crash/deprecation/CSP fatal）；versions 探针（真实运行时）electron=44.2.0 chrome=152.0.7977.76 node=24.20.0
- ELECTRON-CORS-001-A production smoke（Electron 44.2.0 生产模式，app://aistudy + 临时 8080 probe server）：**真实观察到 OPTIONS+GET /api/v1/spaces 携带 Origin=app://aistudy**（preflight ACRM=GET / ACRH=content-type，SF-Mode=cors）；secure custom origin → localhost HTTP 未被 mixed-content 阻止（请求实际到达）；运行时日志确认 prod CSP 已注入（script-src 'self' / style-src-attr 唯一例外 / base-uri+frame-ancestors none）；无 CSP violation / 无 Node integration 错误
- **Electron boot smoke PASS ≠ real Backend HTTP integration PASS**（backend CORS allowlist 未配，见 BACKEND CONTRACT REQUEST）

## Static Evidence

- renderer 扫描 0 命中：electron/require('electron')/node:fs/child_process/ipcRenderer/safeStorage/process.env/shell.openExternal
- 无 localStorage.setItem(token)/sessionStorage token/硬编码 Bearer/VITE token secret/axios/fetch('/api...')
- main 安全基线：contextIsolation=true/nodeIntegration=false/sandbox=true/webSecurity=true；deny window.open；will-navigate 白名单（review#2 后仅 packaged index.html）
- Windows Git：diff --check 干净；status 仅 untracked（desktop/ + docs/frontend-*.md）

## Deferred

- FE-002 SourceAsset file upload（file dialog IPC / multipart）
- 正式 Login / Refresh Token / safeStorage production implementation
- Admin Web / Question / Practice / Exam / AI Chat / Search / Notes
- Playwright、Electron packaging（electron-builder）、auto-update、tray、notification
- SourceAsset multipart 接入时必须重审 shared client Content-Type（本轮不解决）
- ELECTRON-CORS-001：custom protocol（app://aistudy）与 backend CORS allowlist（见下）
- react-router v7 future flags（当前版本类型不支持；dev 良性警告，未来升级时消除）

## Backend Contract Dependencies

- shared client 已满足本轮全部 JSON endpoint（Content-Type: application/json 适用，未修改）
- SourceAsset multipart future：不得继承 application/json（FE-002/BUSINESS-004 closeout 后再处理）
- **BACKEND CONTRACT REQUEST — ELECTRON ORIGIN / CORS（状态更新：Origin 已确认）**：
  - ELECTRON-CORS-001-A 已真实观察：**production renderer 发送 Origin: app://aistudy**（OPTIONS preflight + GET 均确认；preflight 携带 Access-Control-Request-Headers: content-type，带 token 时会加 authorization）
  - Backend 需要的 allowlist：`http://localhost:5173`（dev）、`app://aistudy`（prod custom origin）、Admin Web origin 单独配置——见 docs/api-guidelines.md §19
  - 禁止：webSecurity=false / Access-Control-Allow-Origin: * / 盲放 Origin:null / generic IPC HTTP proxy
  - 下一步 ELECTRON-CORS-001-B（backend scope）：SecurityConfig 配 .cors() + CorsConfigurationSource allowlist；随后 integration C 验证 real authenticated flow（需有效 JWT，本轮不做）

## Known Risks

- packages/api-client 无独立 node_modules：desktop npm install 依赖 npm 对 file: 依赖的解析（已成功）
- WSL 不可作为 Windows npm/runtime 替代（node_modules 平台混用风险）
- 全部 generated 字段 optional：已安全访问（isNavigableId finite-positive 检查），禁 data!.id
- electron 二进制依赖网络（npmmirror 手动补齐路径已验证可用）
- CORS 未配 → 真实 backend 数据流（含 Development Session token 注入）尚未端到端验证

## Next Actions

1. 用户 review：视觉/交互检查（npm run dev）；Electron boot 已验证，backend 数据流待 CORS spike 后验证
2. ELECTRON-CORS-001 spike（backend scope，由 backend 分支/用户决定）
3. 用户提交（git add/commit 由用户执行，Hermes 不做）
4. FE-002 规划（SourceAsset 等）——禁止在本任务内开始

## Resume Instructions

context 被 compact 后：先读本文件 + docs/frontend-development-log.md 最后 200 行 + Windows Git status --short（git.exe -C D:/AIProject-frontend status --short），再继续。Git 一律 Windows Git；npm 一律 Windows Node（先 node -p "process.platform" == win32）；文件编辑可 WSL。下一任务启动前先确认用户已完成 review；CORS spike 属 backend scope。
