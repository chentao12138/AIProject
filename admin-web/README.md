# AIStudy Admin Web

浏览器版管理后台（ADR-028），独立于 Electron 桌面端的纯 Web 工程。
只面向具备 `ROLE_ADMIN` 的账号，通过共享包 `@aistudy/api-client`
（OpenAPI 生成类型）调用 Spring Boot 后端，**不包含任何后端代码**，
也不与桌面端共享 UI。

## 功能

- 正式登录：`/api/v1/auth/login` + `/auth/refresh` 令牌轮换，401 自动
  刷新一次（仅重试安全方法，写操作不重放）。
- 用户管理：分页用户列表、查看用户、整体替换角色（PUT roles）、
  启用/停用账号（PATCH status）。
- 存储运维：孤儿文件 DRY_RUN 扫描与 CLEANUP 清理（清理需二次确认）。

## 令牌存储策略（ADR-026 的当前实现与偏离）

ADR-026 的目标是 refresh token 走 HttpOnly Cookie。当前后端登录/刷新
接口以 JSON body 返回令牌对、**不设置 Cookie**，浏览器 JS 无法自行写入
HttpOnly Cookie。因此在后端支持 Set-Cookie 之前，本工程把 access 与
refresh 令牌**只保存在标签页内存**中：

- 绝不写入 localStorage / sessionStorage / IndexedDB；
- 关闭标签页即登出（保守降级，安全性高于任何 JS 可读的磁盘存储）；
- 后端支持 Cookie 传输后，应改为凭据 Cookie 并移除内存 refresh。

## 开发

```bash
npm install
npm run dev          # http://localhost:5174，/api 代理到后端
```

默认把 `/api` 代理到 `http://localhost:8080`，可用
`VITE_PROXY_TARGET` 覆盖（见 `.env.example`）。走相对路径 + 代理是为了
绕开后端 CORS 白名单（仅放行 GET/POST/OPTIONS），且不在前端硬编码主机。

## 生产部署

```bash
npm run build        # 类型检查 + 产物输出到 dist/
```

`dist/` 为纯静态文件，由 Nginx（或同类反代）托管，并把 `/api` 反代到
后端，保证同源；SPA 路由需要 `try_files ... /index.html` 回退。示例：

```nginx
location / {
  try_files $uri /index.html;
}
location /api/ {
  proxy_pass http://backend:8080;
}
```

## 质量门禁

```bash
npm run typecheck
npm run lint
npm run test:run
npm run build
```

## 权限边界

前端的 `RequireAuth` / `RequireAdmin` 仅为体验层（隐藏入口、给出提示），
**所有管理员接口的鉴权以后端为准**；前端被绕过不影响安全边界。
