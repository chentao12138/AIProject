# AIStudy Desktop

Electron + React + TypeScript + Vite 桌面客户端。
早期阶段名 FE-001 / FE-001.5 只覆盖 Spaces/Sources/Knowledge 骨架与产品硬化；
当前范围已含 Questions（七类题型，排序/配对等后端契约）、Practice、Exams、Search、
AI 辅导与登录会话恢复。各表面的真实状态以 `docs/FINAL-FEATURE-MATRIX.md`
（已实现 / 被契约阻塞 / B 类排除）为准，本文件不再维护第二份完成度清单。

## Prerequisites

- **Windows Node >= 22.12.0**（本机 v24.11.1 / npm 11.x 验证；`package.json`
  `engines.node` 与 Electron 44 的要求一致，不再有 "Node >= 20" 旧契约）
- 后端 Spring Boot 运行在 `http://localhost:8080`（默认 `VITE_API_BASE_URL`）
- 构建/测试/运行全部使用 **Windows 工具链**：npm、Electron、Git

> 重要：本目录的 `node_modules` 由 **Windows npm** 生成。
> 禁止在 WSL/Linux Node 下执行 `npm install` / `npm run`（平台混用会破坏原生依赖；
> Electron 二进制是 win32 构建）。WSL 只允许编辑源码，`node -p "process.platform"`
> 必须输出 `win32` 后才能运行任何 npm 命令。

## Install

```powershell
cd D:\AIProject-frontend\desktop
npm install
```

若 Electron 二进制下载失败/缓慢（CN 网络常见），用官方安装器补二进制：

```powershell
$env:ELECTRON_MIRROR = 'https://npmmirror.com/mirrors/electron/'
node node_modules/electron/install.js
```

验证 `node_modules\electron\dist\electron.exe` 存在。

## Commands

```powershell
npm run dev            # electron-vite dev（HMR，开发窗口）
npm run lint           # ESLint 9 flat config（0 problems 为通过）
npm run lint:fix       # 只修可自动修复项
npm run typecheck      # tsc --noEmit（node + web 两个 project）
npm run test:run       # Vitest 单次运行（jsdom + Testing Library）
npm run test:coverage  # Vitest + v8 coverage（text 报告）
npm run api:check      # OpenAPI 生成物一致性（ADR-052；不连后端）
npm run build          # typecheck + api:check + electron-vite build（out/）
```

质量门禁顺序：`lint` → `typecheck` → `test:run` → `build`（build 内含 `api:check`）。
全绿才可交付。

## API base URL（运行期单一契约，ADR-051）

Renderer 只通过共享 `@aistudy/api-client` 调用后端；地址由 **main 在启动时解析一次**，
renderer 经 `window.aistudyConfig.getApiBaseUrl()`（只读 IPC 通道）读取同一个值。

优先级（高→低）：

1. 环境变量 `AISTUDY_API_BASE_URL`
2. `<userData>/config.json` → `{ "apiBaseUrl": "https://api.example.com" }`
3. 构建期 `VITE_API_BASE_URL`（复制 `.env.example` 为本地 `.env`，**不要提交** `.env`）
4. `http://localhost:8080`

开发时改 `.env` 仍需重启 `npm run dev`（构建期内联）；打包后的安装用
`config.json` 或环境变量即可指向别的后端，**不需要重新构建**。

**契约（`src/shared/api-config.ts` 是唯一 normalization 来源）**：

- 只接受 `http:` / `https:` 后端 URL；任一层缺失/空/非法值都降级到下一层，
  最终安全回退到 `http://localhost:8080` —— 绝不产生 `connect-src *` 或未经验证的 origin。
- **main 的 CSP 与 renderer client 使用同一次解析结果**：`connect-src` 只取
  origin（scheme + host + port），base URL 的路径（如 `/api`）不会进入 CSP。
- 例如 `apiBaseUrl=http://127.0.0.1:9090/api` → client base 为
  `http://127.0.0.1:9090/api`，CSP 为 `connect-src 'self' http://127.0.0.1:9090`。
- 外壳 header 显示当前 API target；`config.json` 只认 `apiBaseUrl` 一个键。

## 契约漂移门禁（ADR-052）

```powershell
npm run api:check   # openapi.json ↔ api.d.ts ↔ manifest.json 必须一致
```

`npm run build` 已包含该检查。生成物只能由
`npm --prefix ../packages/api-client run api:generate`（需后端在跑）产生，
**不得手改** `api.d.ts`；renderer 领域类型统一由
`src/renderer/src/lib/types.ts` 从 client 反推（页面禁止 `as unknown as`
与平行 DTO，由 `renderer-conventions.test.ts` 扫描强制）。

## 正式登录与会话（ADR-026 / ADR-037）

- 公开路由 `/login`：用户名/密码调用 `/api/v1/auth/login` 获取令牌对；
  其余路由由 `RequireAuth` 守卫，未登录跳回登录页并记住目标地址。
- **access token 只在 renderer 内存**；**refresh token 不进 renderer 磁盘**：
  main 进程通过 Electron `safeStorage` 加密后写入 `userData/auth-refresh.bin`
  （版本化信封、0600 权限；系统加密不可用时拒绝落盘）。
- renderer 只经 `contextBridge` 暴露的 `window.aistudyAuthVault`
  （save/load/clear 三个固定通道）与保险库通信，IPC 每次校验 senderFrame 来源；
  preload 不泄漏 `ipcRenderer`。
- **401 自动轮换**：`createRefreshingFetch` 单飞调用 `/auth/refresh`，仅对
  GET/HEAD/OPTIONS 用新令牌重放一次；写操作只换令牌不重放；auth 端点自身不刷新。
  后端每次轮换 refresh，复用旧 refresh 会吊销整个 family → 回到登录页。
- **可恢复 vs 已死亡**：只有凭证被拒绝（400/401/403）才清除保险库里的
  refresh token；5xx / 网络不可达保留令牌，`RequireAuth` 显示离线面板并提供
  重试（`retryRestore`），后端恢复即可继续，不需要重新登录。
- **单实例锁**：`app.requestSingleInstanceLock()` 保证同一时刻只有一个进程
  持有 vault 槽；再次启动只聚焦已有窗口（避免两个 renderer 并发轮换同一
  refresh token 导致整个 family 被吊销）。
- 登录/退出/主体切换是 **auth cache boundary**：清空 React Query 缓存，
  上一 principal 的数据不残留。
- 令牌绝不进入 localStorage/sessionStorage/cookie、日志或错误文案。

## Electron 定制协议与 CORS

- 生产 renderer origin：`app://aistudy`（custom scheme：
  `standard + secure + supportFetchAPI + corsEnabled`，无 bypassCSP）。
- 渲染输出从 `out/renderer` 经 `protocol.handle('app')` 服务，
  URL→路径映射是 traversal-safe 纯函数（`src/main/app-protocol.ts`）。
- 导航白名单：dev 仅 vite dev server origin；prod 仅 `app://aistudy`
  （hash 路由允许；file://、http(s)、外部 app host 全拒绝）。
- CSP 单一来源（无 meta CSP）：prod 由协议响应头注入，dev 由
  `onHeadersReceived` 注入（`src/main/csp.ts` 纯函数，dev/prod 显式区分）。
- Backend CORS allowlist：`http://localhost:5173`（dev）+ `app://aistudy`（prod）
  —— 后端需显式配置（ELECTRON-CORS-001-B 已落地）。

## 如何区分 401 与后端离线

- 401：先触发一次 refresh 轮换；轮换失败或未登录时统一提示
  `登录状态已失效，请重新登录。` 并回到 `/login`——auth 端点本身不重试。
- 后端离线：`无法连接到后端服务。请确认服务已启动后重试。`——有界重试。
- 其他：403/404/5xx 各有专属文案（见 `src/renderer/src/lib/api-error.ts`）。

## Security contract（ADR-037）

- BrowserWindow：`contextIsolation: true`、`nodeIntegration: false`、
  `sandbox: true`、`webSecurity: true`（永不降级）。
- `window.open` deny；`will-attach-webview` deny；`will-navigate` 仅应用 origin。
- 浏览器权限 deny-by-default（`setPermissionRequestHandler` /
  `setPermissionCheckHandler` 全拒；无 camera/mic/geolocation/notification 需求）。
- Preload 仅经 `contextBridge` 暴露 refresh-token 保险库三个固定通道
  （save/load/clear，senderFrame 来源校验），不泄漏 `ipcRenderer`；
  main 进程无数据库/业务规则/AI key。
- Renderer 不接触 Node API（静态扫描门禁），令牌不进 localStorage。
- 查询重试策略：401/403/404 不重试；network/5xx 有界重试；mutation 不自动重放。

## Scope

FE-001：Spaces / Sources / KnowledgeCategory / KnowledgePoint / Publish。
FE-001.5：产品硬化（错误语义、query 韧性、DevSession、shell/dialog a11y、
Electron 权限、协议/CSP 测试、覆盖率、lint 门禁）。
FE-001.5 PRE-COMMIT FIX-01：API base/CSP 单一配置契约（src/shared/api-config.ts）、
nav `end` 单 active、engines.node >=22.12.0、ESLint 直接依赖、ID 解析分离
（parseOptionalPositiveId）、Space unavailable 语义、StatusBadge 精确匹配。

**本轮最终开发范围**：Question/Practice/Exam/Search/Notes。SourceAsset 文件上传与
AI Chat 已实现。Notes 与若干收尾项被后端合同卡住（逐字段核对结果见
`BACKEND-CONTRACT-REQUESTS.md` 与 `docs/FINAL-FEATURE-MATRIX.md` 的 Blocked 表）：
Notes 端点、Question 更新动词、考试已保存答案回读、练习历史成绩、七类题型的
排序/配对答案结构、题库分页、搜索 NOTE 结果类型。
端到端验证目前是 `docs/SMOKE-INSTRUCTIONS.md` 的手工步骤 + 每个模块的
Vitest/Testing-Library 行为测试；本仓库**没有** Playwright 依赖，不要把
"自动化 E2E" 当作已有能力。

已落地：正式登录与 refresh-token（ADR-026，见上节）、Admin Web
（独立工程，见 `../admin-web/`，ADR-028）、electron-builder 打包分发（见下节）。

## 打包分发（electron-builder）

```powershell
npm run icon:generate  # 零依赖重生成 build/icon.png 与 icon.ico（可复现）
npm run pack           # electron-vite build + electron-builder --dir（免安装目录，自测）
npm run dist:win       # NSIS 安装包（dist/，x64）
npm run dist:mac       # dmg（x64 + arm64）
npm run dist:linux     # AppImage
```

- 配置在 `package.json` 的 `build` 字段；应用 ID `com.aistudy.desktop`，
  产品名 AIStudy。NSIS 非一键安装、可改安装目录、创建桌面/开始菜单快捷方式。
- **electron-builder 需要额外下载 NSIS/winCodeSign 二进制**：直连 GitHub 的机器上
  `npm run dist:win` 会停在 `⨯ connect ETIMEDOUT <github-ip>:443`（`packaging` 阶段，
  产物仍是 `dist/` 里的旧包，容易误判成成功）。走镜像即可成功（本仓库实测产出
  `dist/AIStudy Setup 0.1.0.exe`，约 111 MB）：
  ```powershell
  $env:ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"
  $env:ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
  npm run dist:win
  ```
  判成功要看 `dist/` 里 exe 的时间戳，不要只看退出码。
- 打包只收 `out/` 与 `package.json`（renderer bundle 已自包含 React/openapi-fetch/
  共享 client，故显式排除 `node_modules`，app.asar 约 0.6 MB；Electron 运行时另计）。
- 产物在 `dist/`（gitignore）；`win-unpacked/AIStudy.exe` 为免安装可执行文件，
  `*.exe` NSIS 安装包为分发给用户的成品。未做代码签名，Windows SmartScreen
  首次运行会有未识别发布者提示（后续接签名证书）。

## Troubleshooting

- `spawn ...electron.exe\n ENOENT`：`node_modules/electron/path.txt` 含换行，
  重写为 `electron.exe` 字节（无换行）。
- npm audit 在 npmmirror registry 下不可用（404 NOT_IMPLEMENTED）；
  用 `--registry=https://registry.npmjs.org` 一次性查询。
- `npm run api:check` 报 "api.d.ts is not what openapi.json generates"，但内容其实没变：
  `core.autocrlf=true` 会把 `../packages/api-client/src/generated/api.d.ts` 检出成
  CRLF，而生成器输出 LF，`check-api.mjs` 直接比对原始字节哈希。先按
  `tr -d '\r'` 归一化后对比两边 sha256 确认是否真的漂移；确认为行尾问题时，
  根治在 api-client 仓（`.gitattributes` 固定生成物为 LF 并 `git add --renormalize`，
  或让校验本身归一化行尾），**不要**用 `--record` 掩盖。
- Electron 44 需要 Windows Node >= 22.12.0（`npm run dev` 前先 `node -v`；
  与 `package.json` `engines.node` 一致）。
