# AIStudy Desktop

Electron + React + TypeScript + Vite 桌面客户端（FE-001）。

## Prerequisites

- Windows Node >= 20（本机 v24.11.1 验证）
- 后端 Spring Boot 运行在 `http://localhost:8080`（默认 `VITE_API_BASE_URL`）

> 重要：本目录的 `node_modules` 由 **Windows npm** 生成。
> 禁止在 WSL/Linux Node 下执行 `npm install` / `npm run`（平台混用会破坏原生依赖）。

## Install

```powershell
cd D:\AIProject-frontend\desktop
npm install
```

若 Electron 二进制下载缓慢，可先设置镜像：

```powershell
$env:ELECTRON_MIRROR = 'https://npmmirror.com/mirrors/electron/'
```

## Dev

```powershell
npm run dev
```

## Typecheck

```powershell
npm run typecheck
```

## Tests

```powershell
npm run test:run
```

## Build

```powershell
npm run build
```

## Backend

Renderer 通过共享 `@aistudy/api-client` 调用后端；base URL 由环境变量控制：

```
VITE_API_BASE_URL=http://localhost:8080
```

（复制 `.env.example`，不要提交 `.env`）

## Auth

FE-001 uses development-only in-memory token injection.
No token persistence. Formal auth deferred.

开发模式（`npm run dev`）下，右上角 Development Session 可粘贴
Bearer access token（仅内存，刷新即失）；生产构建不显示该输入，
显示 `Authentication integration pending`。

## Security contract（ADR-037）

- `contextIsolation: true`、`nodeIntegration: false`、`sandbox: true`
- `window.open` deny；`will-navigate` 仅限应用 origin
- Preload 零 IPC 暴露（FE-001 无 OS integration 需求）
- Renderer 不接触 Node API / 数据库 / AI Provider key

## Scope

FE-001：Spaces / Sources / KnowledgeCategory / KnowledgePoint / Publish。
SourceAsset 文件上传（FE-002）未实现。
