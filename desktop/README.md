# AIStudy Desktop

Electron + React + TypeScript + Vite 桌面客户端（FE-001 基础 + FE-001.5 产品硬化）。

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
npm run build          # typecheck + electron-vite build（out/）
```

质量门禁顺序：`lint` → `typecheck` → `test:run` → `build`。全绿才可交付。

## API base URL（单一配置契约）

Renderer 只通过共享 `@aistudy/api-client` 调用后端；base URL 由环境变量控制：

```
VITE_API_BASE_URL=http://localhost:8080
```

（复制 `.env.example` 为本地 `.env`，**不要提交** `.env`。）

**契约（`src/shared/api-config.ts` 是唯一 normalization 来源）**：

- 只接受 `http:` / `https:` 后端 URL；缺失/空值/非法值安全回退到
  `http://localhost:8080` —— 绝不产生 `connect-src *` 或未经验证的 origin。
- **main 进程 CSP 与 renderer client 使用同一个值**：`connect-src` 只取
  origin（scheme + host + port），base URL 的路径（如 `/api`）不会进入 CSP；
  dev/prod 两条策略都从同一 resolution 派生。
- 例如 `VITE_API_BASE_URL=http://127.0.0.1:9090/api` →
  client base URL 为 `http://127.0.0.1:9090/api`，
  CSP 为 `connect-src 'self' http://127.0.0.1:9090`。
- 修改 `.env` 后需要重启 `npm run dev`（build 期内联，renderer/main 同源）。

## Development Session（开发会话）

- **仅开发构建**显示：右上角可粘贴 Bearer access token，Apply/Clear。
- **纯内存**：不写 localStorage/sessionStorage/cookie；刷新窗口即失；
  令牌绝不进入日志/错误文案；输入框为 password 掩码。
- Apply 对空/纯空白输入禁用；任何编辑都会清除旧状态消息。
- Apply/Clear 是 **auth cache boundary**：触发 `queryClient.resetQueries()`，
  上一 principal 的缓存数据不会残留。
- **生产构建**：不显示令牌输入，显示 `Authentication integration pending`
  （正式登录后续任务实现）。
- 令牌限制：当前可用 token 来自后端 SPIKE JWT 服务（临时签发，5 分钟 TTL）。
  这是临时限制，正式 auth 落地后消失。

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

- 401：`当前开发会话未认证或令牌已失效。请更新 Development Session 令牌。`
  （dev）/ `Authentication integration pending.`（prod）——查询不会重试。
- 后端离线：`无法连接到后端服务。请确认服务已启动后重试。`——有界重试。
- 其他：403/404/5xx 各有专属文案（见 `src/renderer/src/lib/api-error.ts`）。

## Security contract（ADR-037）

- BrowserWindow：`contextIsolation: true`、`nodeIntegration: false`、
  `sandbox: true`、`webSecurity: true`（永不降级）。
- `window.open` deny；`will-attach-webview` deny；`will-navigate` 仅应用 origin。
- 浏览器权限 deny-by-default（`setPermissionRequestHandler` /
  `setPermissionCheckHandler` 全拒；无 camera/mic/geolocation/notification 需求）。
- Preload 零 IPC 暴露（`export {}`）；main 进程无数据库/业务规则/AI key。
- Renderer 不接触 Node API（静态扫描门禁）。
- 查询重试策略：401/403/404 不重试；network/5xx 有界重试；mutation 不自动重放。

## Scope

FE-001：Spaces / Sources / KnowledgeCategory / KnowledgePoint / Publish。
FE-001.5：产品硬化（错误语义、query 韧性、DevSession、shell/dialog a11y、
Electron 权限、协议/CSP 测试、覆盖率、lint 门禁）。
FE-001.5 PRE-COMMIT FIX-01：API base/CSP 单一配置契约（src/shared/api-config.ts）、
nav `end` 单 active、engines.node >=22.12.0、ESLint 直接依赖、ID 解析分离
（parseOptionalPositiveId）、Space unavailable 语义、StatusBadge 精确匹配。

**尚未实现（后续任务）**：SourceAsset 文件上传（FE-002，含 multipart / file picker IPC
——需 backend/shared-client 同步）、正式登录与 refresh-token、Admin Web、
Question/Practice/Exam/AI Chat/Search/Notes、Playwright E2E、
打包分发（electron-builder）、自动更新/tray/通知。

## Troubleshooting

- `spawn ...electron.exe\n ENOENT`：`node_modules/electron/path.txt` 含换行，
  重写为 `electron.exe` 字节（无换行）。
- npm audit 在 npmmirror registry 下不可用（404 NOT_IMPLEMENTED）；
  用 `--registry=https://registry.npmjs.org` 一次性查询。
- Electron 44 需要 Windows Node >= 22.12.0（`npm run dev` 前先 `node -v`；
  与 `package.json` `engines.node` 一致）。
