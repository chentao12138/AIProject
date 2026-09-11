# Frontend Current Task

## Objective

LONG-RUN-FE-001.5 — DESKTOP PRODUCT HARDENING / UX / A11Y / QUALITY SYSTEM
（Phase 1–34 顺序执行；完成后继续 Stretch A–G；计划文件 docs/frontend-hardening-plan.md 为唯一权威）

## Current Phase

PHASE 1–34 + STRETCH A/C/D/F DONE（Stretch B 由既有 pending 阻断测试覆盖、E 经审查无需改动、G 文案一致性已审查）
+ **PRE-COMMIT REVIEW FIX-01 DONE（外部 reviewer 检查 package.zip 源码后提交前修复）** — 等待用户 review 与 commit

## Baseline

- Branch: feat/fe-001 @ ec115be "feat: establish desktop frontend foundation"（Windows Git 权威确认）
- Working tree: clean（唯一 untracked = docs/frontend-hardening-plan.md 计划文件本身，非本任务产物）
- Windows Node: v24.11.1 / npm 11.x / win32 x64；Electron 44.2.0（EOL 升级已完成，DEP-001）
- 上一任务 FE-001 已提交并集成（含 ELECTRON-CORS-001-A/B/C 真实链路验证）

## Allowed Write Scope

- desktop/**（含 package-lock.json，由 Windows npm 生成）
- docs/frontend-current-task.md / docs/frontend-development-log.md
- 禁止：server/**、deploy/**、packages/api-client/**、docs/current-task.md 等非 frontend docs
- 禁止 git add/commit/push/reset/restore/clean/rebase/merge（用户自行提交）
- 禁止 FE-002 / SourceAsset upload / multipart / file picker IPC

## FE-001.5 Baseline Audit（PHASE 1 产物）

### Renderer structure
- src/renderer/src/main.tsx（QueryClientProvider + ApiClientProvider + StrictMode）→ App.tsx → RouterProvider(createHashRouter)
- 目录：app/（AppShell、SpaceScopeGuard、router）、components/（Button/Card/Dialog/EmptyState/ErrorState/Input/LoadingState/Select/StatusBadge/TextArea）、features/{auth,spaces,sources,knowledge}、lib/（api-client/api-context/api-error/category-tree/format/query-keys/types）、styles/global.css、test/（setup、test-utils）

### Route hierarchy
- / → redirect /spaces；/spaces（SpacesPage）；/spaces/:spaceId → SpaceScopeGuard → index→sources / sources / knowledge / knowledge/:knowledgePointId；* → NotFoundPage
- current space 由 URL 决定（useCurrentSpaceId：param 优先 + 正则回退）

### Query client setup
- main.tsx: retry:1、refetchOnWindowFocus:false；无 staleTime/gcTime/refetchOnReconnect/按错误类 retry 策略
- 各页 queryKey 契约存在（query-keys.ts）；mutation invalidate 均只清受影响 key；auth 边界 = resetQueries（非 invalidate）

### API error normalization
- lib/api-error.ts: unwrap()（openapi-fetch {data,error,response} → throw ApiRequestError(status)）+ normalizeApiError → 6 类（network/unauthorized/forbidden/not-found/server/unknown）
- 现状与计划差异：401 文案为 dev 专属但无 dev/prod 区分；网络/403/5xx 文案与计划推荐不完全一致；normalizeApiError 无 isDev 参数（纯函数可测性 OK）

### Development Session
- InMemoryTokenSession（lib/api-client.ts）：内存、trim、无持久化；DEV-only 粘贴 UI；Apply/Clear → queryClient.resetQueries()（auth cache boundary 已实现）
- 缺口：whitespace-only Apply 未禁止（按钮不禁用）；编辑后不清理 applied 状态；status 消息非 aria-live 常驻（role=status 有但语义弱）；无 cleared 反馈

### Reusable components
- 已有 9 个 primitives（Button/Card/Dialog/EmptyState/ErrorState/Input/LoadingState/Select/StatusBadge/TextArea），覆盖主要重复；每页重复 page__header 结构（Spaces/Sources/Knowledge/Detail 4 处）→ Phase 17 评估 PageHeader
- isNavigableId / isPositiveId 逻辑在 SpacesPage/KnowledgePage/DetailPage 三处重复 → Phase 3 抽 lib/ids.ts

### Dialogs/forms
- Dialog：role=dialog/aria-modal/aria-label、Escape、overlay click、close 按钮；无初始聚焦、无 focus trap、无 focus return、无 busy 时防误关
- 表单：真实 form submit、pending 防重复提交、客户端 blank 校验；缺口：编辑时不清 stale error、无 maxLength 视觉约束、sortOrder/difficulty 数字解析 OK 但 NaN 防护分散

### Electron security controls（FE-001 基线已强）
- contextIsolation=true / nodeIntegration=false / sandbox=true / webSecurity=true；setWindowOpenHandler deny；will-navigate 白名单（dev origin / prod app://aistudy）
- protocol.registerSchemesAsPrivileged（standard+secure+supportFetchAPI+corsEnabled）；protocol.handle('app') + resolveAppUrlPath（traversal-safe）；net.fetch 流式
- CSP：cspFor(isDev, devServerUrl) 纯函数；prod 由 protocol.handle 响应头注入；style-src-attr 'unsafe-inline' 唯一例外（category tree inline paddingLeft）→ Phase 21 尝试用嵌套 ul padding 消除后收紧
- preload zero surface（export {}）；无 IPC；main 无业务逻辑
- 缺口：无 setPermissionRequestHandler/CheckHandler（Phase 19 deny-by-default）

### Test files & count（baseline）
- 9 文件 / 46 tests：category-tree.test.ts、SpacesPage.test.tsx、SourcesPage.test.tsx、KnowledgePage.test.tsx、KnowledgePointDetailPage.test.tsx、DevelopmentSession.test.tsx、SpaceScopeGuard.test.tsx、app-protocol.test.ts、csp.test.ts
- vitest include 已覆盖 src/main/**/*.test.ts；QueryClient per-test（retry:false）

### Known accessibility risks
- Dialog 无焦点管理；点列表项用 <button> 包裹多行内容（可接受但长标题布局风险）；nav active 无显式 aria-current 断言（NavLink 自动有）；space name 无截断 CSS；backend 绿点误导（Phase 7 修）；footer "FE-001 desktop foundation" 过期文案

### Known error-state risks
- 401 文案 dev/prod 不分；network 与 backend offline 语义需按 Phase 4 规范；publish 错误与 detail 错误共用 form__error 但无独立持久状态（OK）；无 ErrorBoundary（Stretch A）

## Durable Architecture Contracts（FE-001 已定，不得回退）

- 安全 flags 四件套不变；origin=app://aistudy；CSP 单一来源（header，无 meta）
- createHashRouter；current space 来自 URL；query keys 契约（spaces/space/sources/knowledge-categories/knowledge-points/knowledge-point）
- ApiClientContext 注入；禁止业务组件直接 fetch/axios；DTO 类型全部 ReturnType 推导
- 404 anti-IDOR 文案「资源不存在或当前不可访问。」；DevSession = auth cache boundary（resetQueries）
- sourceType 固定 DESKTOP_UPLOAD；difficulty 自由文本（不发明枚举）；buildCategoryTree 输出 DAG（visited 守卫）

## Final Status（PHASE 33 + PRE-COMMIT REVIEW FIX-01）

FE-001.5 HARDENING IMPLEMENTED
PRE-COMMIT REVIEW FIX-01 IMPLEMENTED（外部 reviewer 10 项源码问题全部修复）
PRE-COMMIT REVIEW FIX-01 RE-VALIDATED（2026-09-06 二次全量 Windows 验证，结果一致）
WINDOWS LINT/TYPECHECK/TEST/COVERAGE/BUILD VERIFIED（FIX-01 后全量重跑）
DEV SMOKE VERIFIED（nested route 单 active 由 4 项 aria-current 测试覆盖；custom CSP 由纯函数单测证明）
AWAITING USER REVIEW AND COMMIT
（不写 COMPLETE；不开始 FE-002）

- 变更范围：desktop/**（源码/测试/配置/CSS/README/.env.example）+ 两份 frontend docs；server/** 与 packages/api-client/** 零触碰
- 测试：19 文件 / 230 tests（Windows Node 实测 PASS，FIX-01 新增 44 项 regression tests）
- Coverage（最终基线，实测）：Statements 84.41% (1506/1784) / Branches 85.16% (396/465) / Functions 86.56% (116/134) / Lines 84.41%
- 运行证据：最终 lint OK / typecheck OK / test:run 230/230（19 文件）/ test:coverage OK / build OK；FIX-01 dev smoke（5173 启动 + 进程存活）；production smoke 证据沿用 PHASE 31（CSP 由同一纯函数 cspFor 生成，FIX-01 已单测双策略）
- 残余 UX 债：点列表按钮多行内容可接受；混合中英文按界面一致性保留（heading/动作英文，状态/错误中文）；React Router v7 future flags 类型不支持（已文档化）；npm audit 10 项 dev-only 发现待大版本升级（已记录）
- BACKEND CONTRACT REQUEST：无新增（CORS allowlist 已在 ELECTRON-CORS-001-B 落地）
- SourceAsset upload 仍推迟至 backend/shared-client 同步（FE-002 未开始）
- 下一步建议：用户 review（npm run dev 视觉检查）→ 用户 commit → FE-002 规划

## FE-001.5 PRE-COMMIT REVIEW FIX-01（外部 reviewer 提交前修复，2026-09-06）

外部 reviewer 实际检查 package.zip 源码后列出的 10 项提交前问题，全部修复如下：

1. **API BASE URL / CSP 单一配置契约**：新增 Electron-free 纯模块 `src/shared/api-config.ts`
   （`resolveApiBaseUrl` + `apiOriginFromBaseUrl`），renderer `lib/api-client.ts` 与 main
   `csp.ts` 共用同一 resolution（electron-vite 对 main build 同样暴露 VITE_* env，
   tsconfig.node.json 加 `vite/client` types）。只接受 http(s)；非法值安全回退
   `http://localhost:8080`；CSP connect-src 只取 origin（scheme+host+port），路径不进
   CSP；`http://*`、`http:///x` 等宽松解析产物被显式拒绝。`cspFor(isDev, {devServerUrl,
   apiBaseUrl})` 双策略单测（默认/自定义 http/https/非法回退/永不 `connect-src *`）。
   新增 3 测试文件成员（api-config.test.ts 12 项 + csp.test 扩展 5 项）。
2. **nested route 双 active nav**：Spaces NavLink 加 `end`；AppShell.test.tsx 新增
   /spaces、/spaces/7/sources、/spaces/7/knowledge 单 active 断言（aria-current），
   含"任意时刻至多一个主要 nav link active"不变式测试。
3. **Node engines contract**：package.json `engines.node` → `>=22.12.0`（与 Electron 44
   一致），desktop/README prerequisites 同步，删除 "Node >= 20" 矛盾表述。
4. **ESLint 直接依赖**：Windows npm `npm install -D @eslint/js@^9 globals`（@eslint/js
   10 要求 eslint 10，故锁 9.x 线）→ @eslint/js@9.39.5 + globals@17.12.0；
   `npm ls --depth=0` 干净；package-lock 由 npm 生成。
5. **ID parser 语义分离**：ids.ts 新增 `parseOptionalPositiveId`（empty→undefined；
   仅纯正 safe integer；拒绝 0/负数/float/NaN/Infinity/hex/exponent/garbage/overflow）；
   KnowledgePage parentId/categoryId 改用它，sortOrder 保持 parseOptionalInteger；
   category select 只渲染 `isPositiveId(id)` 的 option（malformed id 不进 selectable、
   不提交）。ids.test.ts +7 项，KnowledgePage.test.tsx +2 项。
6. **AppShell unavailable 语义**：404 → 「Space not found or inaccessible」；
   401/403/network/5xx → 中性「Space unavailable」；Sources/Knowledge child links 仅在
   space query 成功且拿到有效 space 后渲染（pending/error 不显示假有效链接）；
   SpaceScopeGuard 保持不变。AppShell.test.tsx 新增 404/network/401/5xx/pending/success
   全套。
7. **StatusBadge 精确匹配**：includes() 子串匹配改为 exact map（DRAFT/PUBLISHED/ACTIVE），
   其余 unknown 中性，不发明后端枚举。新增 StatusBadge.test.tsx 10 项
   （INACTIVE 非 active、UNPUBLISHED 非 published、REGISTERED 中性、undefined → —）。
8. **Dependency/static review**：renderer 生产源码 0 命中（electron/node:fs/child_process/
   ipcRenderer/safeStorage/process.env/localStorage/axios/XHR/fetch——仅注释提及）；
   package.json 无 axios/Redux/Zustand/UI framework；Electron 四件套
   contextIsolation=true/nodeIntegration=false/sandbox=true/webSecurity=true 不变。
9. **Windows validation（全部 Windows Node 实测）**：lint OK / typecheck OK /
   test:run 230/230（19 文件，较 186 增 44 项回归测试）/ test:coverage OK /
   build OK（renderer index-DphqYEBd.js 521.05 kB + 13.30 kB css）；out/main/index.js
   实测含 `connect-src 'self' http://localhost:8080`（默认契约内联正确）。
   Dev smoke（CDP DOM 证据，后端离线）：/#/spaces → 仅 Spaces active（aria-current=page）；
   /#/spaces/7/sources pending → 0 active、无 child links；settled network error →
   sidebar「Space unavailable」+ guard「无法连接到后端服务…」+ 重试、0 active、无 child
   links；返回 /#/spaces → 1 active；dev CSP header 实测 = connect-src 'self'
   http://localhost:5173 ws://localhost:5173 http://localhost:8080（无通配、无路径）。
   custom API CSP 已由纯函数单测充分证明，未另启 backend port。
10. **Docs + Git**：本文件 + frontend-development-log.md 更新；Windows Git
    `diff --check` 干净 / `status --short` 范围仅 desktop/** + docs/frontend-* /
    `diff --stat`（见下）；未 commit。

## Runtime Evidence（FE-001.5）

- PHASE 2：lint gate 建立；npm run lint → 0 problems；baseline gate typecheck OK / 46/46 PASS / build OK
- PHASE 3–9：typecheck OK / lint OK / test:run 104/104 PASS（14 文件）
- PHASE 10–24：typecheck OK / lint OK / test:run 177/177 PASS（16 文件）
- PHASE 24 coverage 基线（真实输出）：Statements 83.05% (1402/1688) / Branches 81.39% (350/430) / Functions 85.82% (109/127) / Lines 83.05%
- PHASE 27 npm audit（官方 registry 一次性查询）：10 项（6 moderate / 2 high / 2 critical）——全部为 dev-only 工具链（vite/vitest/esbuild/glob/react-router-dom），修复全部是 semver-major（vite 8/vitest 5/electron-vite 5/react-router 7），按计划不强制大版本升级，记录待后续；react-router 两项 moderate 均不适用（无 SSR hydration、无外部链接）
- PHASE 29 最终 loop（当前状态实测）：lint OK / typecheck OK / test:run 186/186（17 文件）/ coverage OK / build OK
- PHASE 30 dev smoke（CDP DOM 证据）：origin http://localhost:5173/#/spaces；DEV_SESSION_VISIBLE；无 auth placeholder；API target 中性标签；后端离线 network 错误正确显示；[vite] connected；0 CSP/preload/uncaught
- PHASE 31 + 最终 production smoke（当前 bundle，CDP DOM 证据）：origin app://aistudy/#/spaces；DEV_SESSION_HIDDEN；"Authentication integration pending"；CSP header 实测 = default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' http://localhost:8080; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'（无 style-src-attr/unsafe-inline）；0 console violation
- 最终 coverage：Statements 84.06% (1451/1726) / Branches 82.5% (363/440) / Functions 86.36% (114/132) / Lines 84.06%

## Deferred

- FE-002 SourceAsset upload（multipart/file picker）——待 backend/shared-client 同步
- 正式 Login / refresh token / safeStorage；Admin Web；Playwright；electron-builder；打包
- react-router v7 future flags（当前 v6 版本类型不支持，Stretch F 复核）

## Next Actions

1. 完成 PHASE 1 audit（本文件即为产物）+ baseline gate 确认
2. PHASE 2 lint gate → 3 → 4 … → 34，每阶段更新本文件 + append dev-log

## Resume Instructions

context 被 compact 后：先读本文件 + docs/frontend-development-log.md 最后 200 行 + `git.exe -C D:/AIProject-frontend status --short`，再继续当前 Phase。Git 一律 Windows Git；npm 一律 Windows Node（cmd.exe /c "cd /d D:\AIProject-frontend\desktop && node -p process.platform" 必须 win32）；文件编辑可 WSL。npm gate wrapper：cmd.exe /c "cd /d ... && npm run <s> > <s>.log 2>&1 && echo EXITCODE=OK || echo EXITCODE=FAIL"。任务结束前删除 *.log。用户禁止 Hermes 做任何 git add/commit。
