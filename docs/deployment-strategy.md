# 部署策略

> 状态：**TECHNICAL BASELINE**
>
> 目标：当前在 Windows 本地零基础设施成本开发，未来平滑迁移到 Linux Server + 域名。

## 1. 当前本地开发形态

```text
Windows PC
├─ Desktop (Electron)
├─ Admin Web (Vite dev server)
├─ Spring Boot
├─ MySQL
└─ D:\AIStudyData\resources
```

默认开发连接可采用：

```text
Spring Boot: 127.0.0.1:8080
MySQL:      127.0.0.1:3306
```

端口通过配置管理，不写死到业务代码。

## 2. 本地数据目录

源码：

```text
D:\AIProject
```

真实运行数据：

```text
D:\AIStudyData
├── resources
├── backups
├── exports
├── logs
└── temp
```

数据库文件本身由 MySQL 管理；Spring Boot 不关心 MySQL 物理 data directory，只使用 JDBC 连接参数。

如果使用 Docker Desktop 跑 MySQL，在创建长期 volume 前先确认 Docker 数据实际落盘位置，避免无意占用 C 盘。

## 3. Local 配置

服务端配置不写死秘密：

```text
DB_URL
DB_USERNAME
DB_PASSWORD
STORAGE_ROOT
AUTH_* / JWT_*      # 具体实现后确定
AI_PROVIDER_*       # 按 Provider
```

Admin Web：

```text
VITE_API_BASE_URL=http://127.0.0.1:8080
```

Desktop API 地址同样通过配置读取；具体变量名在 Electron 脚手架阶段固定。

## 4. 未来服务器形态

```text
Internet
   │
   │ HTTPS
   ▼
Nginx
├── https://admin.<domain> ──> Admin Web static files
└── https://api.<domain>   ──> Spring Boot
                                  │
                                  ├─ MySQL
                                  └─ /data/ai-study/resources
```

真实域名当前不写入仓库文档。

## 5. Nginx 职责

未来 Nginx 主要负责：

- TLS termination
- Admin Web 静态资源
- 反向代理 Spring Boot
- 合理的请求体大小、超时与安全 header
- 可选压缩/缓存

不要把业务授权放到 Nginx 代替 Spring Security。

## 6. Spring Boot 发布

第一目标是生成可运行 jar：

```text
server/target/<app>.jar
```

开发/测试通过 Maven Wrapper：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
```

未来服务器可选择：

1. systemd + jar
2. Docker container

当前不提前锁死；TECH-SPIKE 只需要证明 jar 能构建，部署阶段再决定实际运维方式。

## 7. Admin Web 发布

Admin Web：

```text
npm ci
npm run build
```

生成静态产物后由 Nginx 托管。

构建时 API base 指向：

```text
https://api.<domain>
```

不要把 localhost 编译进正式生产构建。

## 8. Desktop 发布

Desktop 开发阶段连接本机 API；未来服务器阶段连接 HTTPS API。

API 地址必须配置化，不能假定永远 localhost。

Electron Windows 打包必须在 Windows 工具链下验证，不用 WSL `node_modules` 构建 Windows native 依赖。

## 9. MySQL 本地 → Server

当前从第一版就使用 MySQL，因此服务器迁移不需要 SQLite → MySQL 转换。

首次迁移可使用：

```text
local MySQL
   ↓ logical backup (mysqldump or equivalent)
server MySQL
   ↓ restore
```

迁移前：

- 确认 schema version。
- 停止写入或建立一致性备份窗口。
- 备份数据库。
- 恢复后做行数/关键数据校验。

## 10. Schema Migration Gate

当前版本化 SQL 是 Schema Source of Truth。

在第一个不可随意删除的真实持久数据库投入使用前：

- 必须选择正式 migration executor。
- 优先 Flyway。
- 通过新 ADR 明确 baseline、history、执行时机。

禁止上线后靠人工记忆“哪些 SQL 执行过”。

## 11. 文件迁移 Windows → Linux

数据库只保存 `storageKey`，所以迁移只需要复制资源根目录：

```text
D:\AIStudyData\resources
        ↓
/data/ai-study/resources
```

数据库记录无需从 Windows 路径改写为 Linux 路径。

## 12. Backup

至少覆盖：

- MySQL logical backup
- Storage resources
- 关键部署配置（不含明文秘密）

备份文件不得进入 Git。

正式服务器上线前需要补充：

- 备份频率
- 保留周期
- 恢复演练
- RPO/RTO

这些取决于真实业务价值和数据量，当前不臆造。

## 13. Secrets

生产秘密通过服务器环境变量或安全的 secret 文件/服务注入。

禁止：

- `.env` 提交 Git
- 数据库密码写 README
- AI Key 写前端代码
- JWT signing key 写进静态资源

## 14. Network Security

Local-only 阶段：Spring Boot 默认优先绑定 loopback，避免无意暴露到 LAN。

真正需要 LAN/公网时：

- 明确监听地址
- 配置防火墙
- 强制认证
- 配置 CORS
- 公网强制 HTTPS

未来 Android 如果通过公网访问，直接使用正式 HTTPS API；不以开放裸 MySQL 端口作为方案。

## 15. Docker

Docker 是开发/部署工具，不是产品硬依赖。

本地可用 Docker Desktop 跑 MySQL；未来服务器也可容器化，但不要求最终用户安装 Docker Desktop。

## 16. 首次服务器部署 Checklist（未来）

1. Linux Server 基础环境。
2. DNS / 域名解析。
3. TLS 证书。
4. MySQL 创建与权限。
5. Schema migration executor 就绪。
6. 数据库备份与恢复验证。
7. Spring Boot 配置与启动。
8. Admin Web build + Nginx。
9. Storage root 权限。
10. CORS / Security / Firewall。
11. Health check。
12. Desktop API base 切换验证。
13. 日志、备份、恢复策略验证。
