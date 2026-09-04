# 架构决策记录（Architecture Decision Records）

> 阶段：FINAL TECHNICAL + BUSINESS BASELINE
>
> 状态：**技术架构与核心学习业务均已完成 BUSINESS-ALIGNMENT**
>
> 本文件是 ADR 的唯一 Source of Truth。其他文档引用 ADR 时，以本文件中的编号、标题和状态为准。
>
> 历史决策不删除。已失效的决策保留并标记 `SUPERSEDED`；仍需技术 Spike、算法验证或产品策略确认的开放项标记 `PROVISIONAL`。

---

## 1. 状态定义

| 状态 | 含义 |
|---|---|
| `Accepted` | 当前技术基线正式有效，后续修改必须新增 ADR 或显式 Supersede |
| `Superseded` | 历史决策，已被后续架构或产品方向取代 |
| `Provisional` | 核心业务不受影响、但具体算法/技术/策略尚未最终确定；不得把候选方案当 Accepted |

当前统计：

- ADR 总数：**45**（ADR-001 ~ ADR-045）
- Accepted：**26**
- Superseded：**15**
- Provisional：**4**

Accepted：
`ADR-005、010、013、022、023、024、025、026、027、028、029、030、031、032、033、035、036、037、038、039、040、041、042、043、044、045`

Superseded：
`ADR-001、002、003、004、008、009、011、014、015、016、017、018、019、020、021`

Provisional：
`ADR-006、007、012、034`

---

# 2. v0.1 历史阶段（ADR-001 ~ ADR-014）

## ADR-001：软件形态选择 Local-first

- **背景**：早期基于“单机学习软件”假设，将产品定义为 Local-first 单机应用。
- **原决策**：本地数据为主，不建立正式 Client-Server 主架构。
- **状态**：`SUPERSEDED → ADR-022`
- **原因**：产品已明确为 PC Desktop + Admin Web + Spring Boot + MySQL + Future Android；当前在本机运行只是开发/部署阶段，不是产品架构定义。

---

## ADR-002：Desktop 选择 Electron 而非 Tauri

- **背景**：早期曾因本机 Rust 未安装而错误地把 Tauri 视为不可行。
- **原决策**：选择 Electron。
- **状态**：`SUPERSEDED → ADR-029 + ADR-037`
- **说明**：Electron 仍然是当前正式 Desktop 技术，但选型理由和职责已经重定义：Electron 是客户端，不是业务后端；其安全边界由 ADR-037 单独规定。

---

## ADR-003：第一阶段不建立独立 Backend

- **背景**：早期错误地认为独立 Backend 与 Local-first 冲突。
- **原决策**：MVP 不引入独立后端。
- **状态**：`SUPERSEDED → ADR-022 + ADR-032`
- **原因**：Spring Boot 已明确成为系统核心与唯一业务入口。

---

## ADR-004：数据库选择 SQLite + Prisma + better-sqlite3

- **背景**：早期以单机 Electron 为中心设计数据库。
- **状态**：`SUPERSEDED → ADR-023`
- **原因**：正式架构改为 Client-Server，数据库改为 MySQL，数据访问由 Spring Boot + MyBatis-Plus 统一承担。

---

## ADR-005：AI Provider 抽象层

- **背景**：系统需要接入 AI，但不能让业务代码绑定单一厂商或单一模型。
- **决策**：从第一版保留 AI Provider 抽象。具体 Provider 由 Spring Boot `ai` 模块适配，业务模块只依赖抽象接口。
- **至少考虑的 Provider 类型**：
  - SenseNova
  - OpenAI-compatible Endpoint
  - Ollama
  - LM Studio
  - Future Provider
- **必须支持的能力方向**：
  - Provider / Model 可切换
  - API Key 可替换
  - 超时和错误分类
  - Provider 不可用时的降级
  - 调用量/成本/Token 记录能力
  - Provider 不可用时的降级；完全无 AI 模式的产品边界由 ADR-006 保持 Provisional
- **禁止**：业务模块直接依赖 SenseNova SDK、具体 URL 或具体模型名称。
- **状态**：`Accepted`

---

## ADR-006：核心学习闭环不依赖 AI

- **背景**：基于早期“学习软件”业务假设，曾定义 KnowledgePoint → Question → Answer → WrongQuestion → SRS → Mastery 的闭环。
- **原决策**：AI 关闭时，核心学习闭环仍可运行。
- **状态**：`PROVISIONAL`
- **说明**：真实学习闭环已经确定；当前仅保留“AI Provider 完全不可用时应支持到什么降级程度”作为产品策略开放项。

---

## ADR-007：SRS 使用 SM-2

- **背景**：基于早期学习软件假设。
- **原决策**：使用 SM-2 作为间隔重复算法。
- **状态**：`PROVISIONAL`
- **说明**：Review/Mastery 已正式进入业务模型，但调度算法不锁死 SM-2，需真实学习数据验证。

---

## ADR-008：领域逻辑纯 TypeScript / Renderer 实现

- **背景**：早期 Electron 单机架构下，将领域逻辑放在客户端并要求纯 TypeScript。
- **状态**：`SUPERSEDED → ADR-031`
- **说明**：保留“核心规则应可独立测试、避免 I/O 污染”的原则，但核心领域规则现在位于 Spring Boot 服务端，语言为 Java。

---

## ADR-009：KnowledgePoint 是唯一价值锚点（旧业务假设）

- **背景**：早期曾把所有学习实体都定义为围绕 KnowledgePoint 的唯一中心。
- **原决策**：其他业务实体围绕 KnowledgePoint 组织。
- **状态**：`SUPERSEDED → ADR-038 + ADR-040`
- **原因**：真实业务已确认 KnowledgePoint 是核心学习单元，但一级业务隔离边界是 LearningSpace；同时 Source/Content 是不可被 KnowledgePoint 取代的事实与溯源层。

---

## ADR-010：审计字段与软删除按实体语义使用

- **背景**：早期曾规定“所有表统一 createdAt / updatedAt / deletedAt”，过于机械。
- **决策**：
  - 主要可变业务实体通常保留 `createdAt` / `updatedAt`。
  - 只有具备恢复需求、业务删除语义或审计价值的实体使用 `deletedAt` 软删除。
  - 关联表、RefreshToken、纯日志、不可变历史、临时数据等不强制使用软删除。
  - 是否软删除由实体语义决定，不为了字段统一而机械添加 `deletedAt`。
- **时间类型**：MySQL 侧优先统一为具备毫秒精度的时间类型（如 `DATETIME(3)`）；最终 JDBC/MyBatis 时区行为在 V001 前通过 Spike 确认。
- **状态**：`Accepted`

---

## ADR-011：预留离线多端同步字段

- **背景**：早期计划使用 `syncVersion`、`deletedFlag` 等字段处理多个本地副本同步。
- **状态**：`SUPERSEDED → ADR-022 + ADR-031`
- **原因**：正式模式为多个客户端连接同一 Spring Boot 服务端，不以“多个权威离线数据库副本”为主架构。若未来真实业务需要离线同步，必须另立 ADR。

---

## ADR-012：Markdown 为核心内容格式

- **背景**：基于早期学习软件的 Note / AI Output / Explanation 等内容模型。
- **原决策**：核心文本统一使用 Markdown。
- **状态**：`PROVISIONAL`
- **说明**：Note/内容编辑已确认需要，但 Markdown、富文本或结构化编辑器的具体格式仍需 UI/编辑体验验证。

---

## ADR-013：AI Agent 不自动提交 Git

- **决策**：Hermes / 其他 AI Agent 默认只能执行只读或可审查的 Git 操作，例如：
  - `git status`
  - `git diff`
  - `git log`
  - `git show`
- **未经用户明确授权禁止**：
  - `git add`
  - `git commit`
  - `git push`
  - `git reset --hard`
  - `git clean -fd`
  - force push
  - 改写远程历史
- **状态**：`Accepted`

---

## ADR-014：第一阶段不引入云依赖

- **背景**：早期将“零成本”近似理解为“不使用后端/服务器类技术”。
- **状态**：`SUPERSEDED → ADR-035`
- **原因**：零成本的真正含义已重新定义，不应为了省基础设施费用而破坏合理架构。

---

# 3. v0.2 历史纠偏阶段（ADR-015 ~ ADR-021）

## ADR-015：重新定义 Local-first

- **背景**：用于纠正“有 Backend 就不是 Local-first”的错误理解。
- **状态**：`SUPERSEDED → ADR-022`
- **原因**：产品主架构已经不再采用 Local-first 定位。

---

## ADR-016：重新确认 Electron

- **背景**：承认 Tauri 并非因为 Rust 未安装而技术不可行，但仍选择 Electron。
- **状态**：`SUPERSEDED → ADR-029 + ADR-037`
- **说明**：Electron 仍保留，但角色与安全模型由新的 ADR 定义。

---

## ADR-017：MVP 暂不引入 Backend

- **状态**：`SUPERSEDED → ADR-022 + ADR-032`
- **原因**：产品正式要求 Spring Boot Backend 从第一版成为统一服务端。

---

## ADR-018：继续使用 SQLite + Prisma + better-sqlite3

- **状态**：`SUPERSEDED → ADR-023`
- **原因**：数据库正式改为 MySQL，SQLite/Prisma/better-sqlite3/FTS5 退出正式架构。

---

## ADR-019：Electron Main / Preload / Renderer 安全边界（旧版）

- **背景**：旧架构中 Electron Main 同时承担数据库与 AI Provider，因此定义了 typed IPC、Preload、Main 安全边界。
- **状态**：`SUPERSEDED → ADR-029 + ADR-037`
- **说明**：
  - “数据库和 AI Provider 位于 Electron Main”已经失效，由 ADR-029 取代。
  - “Electron 仍需安全 IPC / Preload 边界”并未失效，由 ADR-037 重新定义。
  - **不得得出“Electron 不再需要 IPC”的结论。**

---

## ADR-020：领域层与 Electron 运行进程解耦

- **状态**：`SUPERSEDED → ADR-031`
- **原因**：核心领域逻辑已统一迁移到 Spring Boot 服务端，不再围绕 Renderer/Main 复用设计。

---

## ADR-021：引入技术 Spike Gate（旧版）

- **背景**：要求关键技术先验证再进入大量业务 TASK。
- **状态**：`SUPERSEDED（由当前 development-plan.md 中的技术 Spike 计划取代）`
- **保留原则**：关键技术路径应先验证，再进入正式业务开发。
- **说明**：旧 Spike 内容基于 Electron + SQLite，已经失效；具体 Spike 清单不由本 ADR 固定。

---

# 4. 当前正式技术基线（ADR-022 ~ ADR-037）

## ADR-022：产品架构采用 Client-Server

- **背景**：产品形态已明确包含 Windows PC 前台、Web Admin、Spring Boot Backend、MySQL，以及后续 Android；当前先在本地开发，未来部署 Linux Server 并使用用户已有域名。
- **决策**：主架构定义为：

```text
Client-Server Architecture
+
Local Development
+
Server Deployable
```

逻辑拓扑：

```text
Desktop (Electron) ─────┐
                        │
Admin Web ──────────────┼── REST / WebSocket ──> Spring Boot ──> MySQL
                        │
Future Android ─────────┘
```

- **当前本地阶段**：Desktop、Admin Web、Spring Boot、MySQL 均可运行在用户自己的 Windows PC。
- **未来服务器阶段**：Spring Boot、Admin Web、MySQL、文件存储迁移到服务器；Desktop/Android 通过 HTTPS API 使用服务。
- **禁止**：客户端绕过 Spring Boot 直接操作数据库。
- **状态**：`Accepted`

---

## ADR-023：Persistence 采用 MySQL + MyBatis-Plus

- **背景**：正式系统需要从本地开发平滑迁移到服务器，不再使用 Electron 直连 SQLite。
- **决策**：

```text
Spring Boot
   ↓
Service / Domain
   ↓
Mapper
   ↓
MyBatis-Plus
   ↓
MySQL
```

- **MyBatis-Plus 使用原则**：
  - 基础 CRUD：BaseMapper / Service 能力。
  - 简单条件查询：LambdaQueryWrapper 等可读写法。
  - 复杂 JOIN、统计、聚合：允许明确 SQL / Mapper XML；禁止为了“全用 Wrapper”制造难维护链式表达式。
- **不采用**：SQLite、Prisma、better-sqlite3、JPA/Hibernate 作为当前正式 Persistence 方案。
- **MySQL 版本**：采用 MySQL 8.x；具体小版本在数据库 Spike / deploy 配置中固定，不在本 ADR 频繁漂移。
- **状态**：`Accepted`

---

## ADR-024：Backend 采用单 Maven Project 的 Modular Monolith

- **背景**：当前由单人 + AI Agent 主导开发，微服务会显著增加构建、部署、日志、网络、事务和运维复杂度。
- **决策**：Backend 使用一个 Spring Boot 应用、一个 Maven Project，通过 Java Package / Module Boundary 组织代码。

示意：

```text
server/
└── src/main/java/.../
    ├── common/
    ├── auth/
    ├── user/
    ├── ai/
    ├── resource/
    ├── admin/
    ├── system/
    ├── space/
    ├── ingestion/
    ├── content/
    ├── knowledge/
    ├── note/
    ├── question/
    ├── practice/
    ├── review/
    ├── exam/
    ├── mastery/
    ├── plan/
    └── search/
```

- **注意**：上述包是当前业务模块边界，不要求每个模块都机械拆成 Maven module。
- **模块规则**：
  - 模块拥有自己的应用服务和 Persistence 边界。
  - 跨模块调用优先通过服务接口，不直接绕过业务层修改对方数据。
  - Common 只保存真正跨模块基础能力，禁止成为“万能垃圾包”。
- **禁止**：
  - 多个 Spring Boot 微服务起步
  - API Gateway / Service Mesh 起步
  - 每模块独立数据库起步
  - 为了“架构先进”提前引入消息队列
- **未来**：只有出现明确独立扩缩容、独立发布、团队边界或隔离需求时，才新增 ADR 讨论拆分。
- **状态**：`Accepted`

---

## ADR-025：数据库变更以版本化 SQL 为 Source of Truth

- **背景**：Schema 必须可追踪、可 Review、可部署，但当前阶段不希望为了 migration 管理自行实现复杂框架。
- **决策**：数据库结构及必要数据回填使用版本化 SQL：

```text
server/src/main/resources/db/migration/
├── V001__init.sql
├── V002__add_xxx.sql
├── V003__add_status_and_backfill.sql
└── ...
```

- **命名**：`V{version}__{description}.sql`。
- **规则**：
  - `CREATE TABLE`、`ALTER TABLE`、`CREATE INDEX` 等结构变更必须进入版本脚本。
  - 为完成结构迁移所必需的 DML/backfill 可以进入对应 `Vxxx` migration。
  - 不使用 `Uxxx__...` 表示普通升级；`U` 前缀保留给 Flyway Undo 语义，避免未来冲突。
  - 已经应用到不可随意删除的持久数据库上的 migration 不回头修改，只新增下一版本。
  - 禁止只在数据库手工 ALTER / UPDATE 而不留下版本脚本。
- **当前阶段**：
  - 在只有可丢弃开发/测试数据库时，可以重建数据库并按顺序应用全部 V 脚本。
  - **不自研** `schema_migrations + checksum + startup scanner + executor` 这类半套 Flyway。
- **进入真实持久数据前的 Gate**：
  - 在第一个“不可随意删除/已有真实数据”的数据库投入使用之前，必须正式选择并接入 migration executor。
  - 首选候选为 **Flyway**。
  - 若决定接入 Flyway，新增 ADR 记录，不把“未来可接”写成已经验证。
- **状态**：`Accepted`

---

## ADR-026：Spring Security + 短期 JWT Access Token + Opaque Refresh Token

- **背景**：Desktop、Admin Web、Future Android 需要统一身份认证，同时要支持 Refresh Token 撤销与轮换。
- **决策**：Backend 使用 Spring Security。

### Access Token

- 使用 JWT。
- 短生命周期，初始建议约 15 分钟，实际值通过配置管理。
- 客户端正常 API 请求通过 `Authorization: Bearer <access-token>` 发送。

### Refresh Token

- 使用密码学安全随机生成的高熵 opaque token，而不是长期 JWT。
- 服务端只保存 token 的不可逆摘要/hash，不保存原始 Refresh Token。
- 存储在 MySQL，第一版不为了 Token 引入 Redis。
- 支持：
  - rotation
  - revoke
  - expiry
  - logout / session invalidation
- Refresh 成功后默认轮换旧 token，旧 token 失效。

### 客户端存储

| 客户端 | Access Token | Refresh Token |
|---|---|---|
| Desktop | 内存 | Electron `safeStorage` |
| Admin Web | 内存 | `HttpOnly + Secure + SameSite` Cookie |
| Android | 内存 | Android Keystore（未来） |

- **禁止**：长期 Refresh Token 放入 `localStorage`。
- **Admin Cookie 安全**：Refresh/Logout 等依赖 Cookie 的端点必须使用合理的 SameSite、Origin/Referer 校验；若未来扩大 Cookie 认证范围，必须加入完整 CSRF 防护。
- **权限**：`/api/v1/admin/**` 必须由后端执行 ADMIN 授权检查，前端路由守卫不能替代服务端授权。
- **状态**：`Accepted`

---

## ADR-027：从第一版建立用户身份与数据作用域

- **背景**：系统明确存在 Desktop、Admin Web、Future Android 和未来服务器访问，需要服务端身份边界，避免以后再给所有数据补 ownership。
- **决策**：第一版建立最小身份模型：
  - `User`
  - `Role`
  - `UserRole`
  - 基础角色：`USER`、`ADMIN`
- **数据作用域原则**：
  - 用户私有数据必须具备明确 owner / user scope。
  - 是否使用直接 `userId`、关联表或其他业务归属关系，由真实业务模型决定。
  - **不再预先列出尚未确认的 LearningProject / Question / Mastery 等业务表。**
- **当前不做**：
  - 企业组织
  - SaaS 多租户
  - 复杂 RBAC/ABAC
  - 组织级数据隔离
- **未来真实业务若需要更复杂主体模型**：新增 ADR，而不是在现有表里随意堆字段。
- **状态**：`Accepted`

---

## ADR-028：Admin Web 为独立前端工程

- **背景**：Web 管理后台与 Desktop 面向不同使用场景，并需要独立构建和部署。
- **决策**：

```text
admin-web/   React + TypeScript + Vite

desktop/     Electron + React + TypeScript

server/      Spring Boot
```

- Admin Web 只通过 Spring Boot API 操作数据。
- Admin Web 不直连 MySQL。
- Admin UI 权限控制只是 UX，最终授权由 Spring Boot 决定。
- API 类型/Client 优先从 OpenAPI Contract 生成，而不是复制 DTO。
- 未来可由 Nginx 静态托管。
- **状态**：`Accepted`

---

## ADR-029：Electron 是客户端，不是 Backend

- **背景**：旧架构让 Electron Main 承担数据库、AI Provider 和核心业务逻辑，阻碍未来 Web/Android 复用。
- **决策**：Electron 只负责 Desktop UI 和必要的本地 OS 集成。
- **Electron 不负责**：
  - 直接访问 MySQL
  - 服务端核心业务规则
  - 服务端授权
  - AI Provider 服务端 API Key
  - 数据库 migration
- **Electron 可以负责**：
  - UI / 交互
  - 文件选择 / 拖放
  - 系统托盘 / 通知
  - `safeStorage`
  - 受控的本地 OS 能力
  - 调用 Spring Boot API
- **API 地址**：从配置读取；开发阶段可默认 `http://127.0.0.1:8080`，未来切换 `https://api.<domain>`。
- **安全边界**：详见 ADR-037。
- **状态**：`Accepted`

---

## ADR-030：统一 `/api/v1` + OpenAPI Contract

- **背景**：多个客户端复用同一 Backend，必须避免接口、DTO、错误结构各自漂移。
- **决策**：
  - 业务 API 从 `/api/v1/**` 开始。
  - 统一请求验证、错误码、分页、时间格式、Request ID、认证/授权约定。
  - 使用 springdoc-openapi 生成 OpenAPI Contract。
  - Desktop / Admin Web 优先基于 OpenAPI 生成 TypeScript API Client。
  - Future Android 也复用同一 Contract。
- **springdoc 路径**：默认 API docs 为 `/v3/api-docs`；如果希望使用 `/api/v1/openapi.json`，必须显式配置 `springdoc.api-docs.path`，不能把自定义路径写成默认值。
- **版本规则**：非破坏性扩展优先保持 v1；真正破坏性变化需要明确迁移策略，必要时新增 `/api/v2` 和 ADR。
- **禁止**：
  - 三个客户端各自手写一套同义 DTO
  - 业务代码写死 API 域名
  - 未记录的破坏性 API 修改
- **状态**：`Accepted`

---

## ADR-031：核心领域规则统一位于 Spring Boot 服务端

- **背景**：多客户端必须共享同一事实来源，不能在 Electron / Admin / Android 各实现一套业务规则。
- **决策**：所有具有业务语义的核心规则由 Spring Boot 执行。
- **原则**：
  - Domain / Application 逻辑尽量与 Controller、数据库、文件系统、网络 I/O 解耦。
  - 核心算法和规则应可通过单元测试验证。
  - 客户端可以做格式校验、即时提示、乐观 UI 等 UX 优化，但服务端拥有最终判定权。
- **正式业务规则**：LearningSpace、Source/Content、KnowledgePoint、Question/Practice、Review、Exam、Mastery、StudyPlan 已由 ADR-038~045 和业务文档确定；具体算法仍由各模块策略控制。
- **状态**：`Accepted`

---

## ADR-032：Spring Boot 是系统核心与唯一业务入口

- **背景**：未来 Desktop、Admin Web 和 Android 都需要复用一套业务能力。
- **决策**：Spring Boot 承担：
  - Authentication / Authorization
  - 业务 Application Service
  - Domain Rule
  - Persistence
  - AI Provider Integration
  - File/Storage Service
  - Admin API
  - System / Health / Configuration Boundary
- **唯一数据入口**：

```text
Client
  ↓
REST / WebSocket
  ↓
Spring Boot
  ↓
Service / Domain
  ↓
Mapper / Storage / Provider
```

- **禁止**：客户端自行建立“第二业务后端”。
- **状态**：`Accepted`

---

## ADR-033：文件存储通过 StorageService 抽象，数据库只保存 storageKey

- **背景**：本地 Windows 路径与未来 Linux Server 路径不同，未来还可能切换对象存储。
- **决策**：业务模块不直接拼接物理路径，统一依赖 StorageService。

概念接口：

```java
public interface StorageService {
    StorageResult store(InputStream input, StorageMetadata metadata);
    InputStream load(String storageKey);
    void delete(String storageKey);
}
```

- 第一版实现：`LocalStorageService`。
- 本地根目录示例：`D:\AIStudyData\resources`。
- Linux Server 示例：`/data/ai-study/resources`。
- 数据库只保存跨平台 `storageKey`，例如：`2026/09/<uuid>.pdf`。
- **禁止**：数据库业务字段保存 `D:\AIStudyData\resources\xxx.pdf` 这种绝对路径。
- **未来对象存储**：如真实需求出现，再新增 ObjectStorage 实现及 ADR。
- **状态**：`Accepted`

---

## ADR-034：SearchService 与中文搜索方案

- **背景**：业务已确认需要在 LearningSpace 内搜索资料/知识，但中文全文搜索的实际数据规模与质量要求尚需真实样本验证。
- **暂定方向**：业务层通过 SearchService 隔离具体实现；第一阶段优先验证 MySQL LIKE/FULLTEXT/ngram，而不是直接引入 Elasticsearch/OpenSearch/Meilisearch。
- **不得提前假设**：MySQL FULLTEXT + ngram 已经满足需求。
- **状态**：`PROVISIONAL`
- **下一步**：使用真实 OCR/KnowledgePoint 中文数据执行搜索 Spike，再决定正式实现。

---

## ADR-035：零成本的正式定义

- **背景**：用户当前希望本地开发，不为基础设施支付持续费用；未来已有域名，可自行部署服务器。
- **决策**：“零成本”主要指当前开发阶段不强制购买：
  - 云服务器
  - 云数据库
  - 对象存储
  - 收费中间件
  - 必须付费 SaaS
- **允许正常使用**：Java、Spring Boot、Maven、MyBatis-Plus、MySQL、React、TypeScript、Vite、Electron、Nginx、Docker、Git 等开源/免费工具。
- **原则**：不为了省基础设施费用而采用明显增加未来迁移成本的错误架构。
- **未来**：部署服务器后的真实成本根据使用量、稳定性和公网需求重新评估，不承诺“任何规模永久零成本”。
- **状态**：`Accepted`

---

## ADR-036：保留全局 Maven 3.6.2，项目使用 Maven Wrapper

- **背景**：用户 Windows 全局 Maven 为 3.6.2，不希望修改；Spring Boot 3.5.x 的 Maven 要求高于该版本。
- **决策**：
  - Windows 全局 Maven 3.6.2 保留，不修改。
  - `server/` 使用 Maven Wrapper 固定满足项目要求的 Maven 3.9.x 版本。
  - 具体 3.9.x 小版本在 SPIKE-001 时固定。
- **未来目录**：

```text
server/
├── mvnw
├── mvnw.cmd
└── .mvn/
    └── wrapper/
```

- **正式项目构建优先使用**：
  - Windows：`.\mvnw.cmd test` / `.\mvnw.cmd package`
  - WSL/Linux（如确有需要）：`./mvnw ...`
- **注意**：Desktop/Admin 的目标环境仍是 Windows；不要把“WSL 能执行 wrapper”误解为整个项目统一使用 Linux 工具链。
- **Git**：`mvnw`、`mvnw.cmd`、`.mvn/` 必须提交，不得被 `.gitignore` 排除。
- **当前阶段**：只记录方案，不在文档阶段下载/生成 Wrapper。
- **状态**：`Accepted`

---

## ADR-037：Electron Client Security Boundary

- **背景**：Electron 不再承担数据库和 AI Provider，但仍需要 Main / Preload / Renderer 处理 `safeStorage`、文件选择、系统通知、托盘和其他 OS 集成，因此 IPC 与进程边界仍然存在。
- **决策**：Electron 使用最小权限模型。

### BrowserWindow 基线

```text
contextIsolation = true
nodeIntegration = false
sandbox = true（默认）
```

如果某个明确能力要求关闭 sandbox，必须单独记录原因并 Review，不能全局默认关闭。

### Renderer

Renderer：

- 不直接访问 Node.js API。
- 不直接访问 `safeStorage`。
- 不拥有任意文件系统能力。
- 不拥有任意命令执行能力。
- 不保存服务端 AI Provider Key。

### Main Process

Main 可以持有受控的本地能力，例如：

- `safeStorage`
- file dialog
- notification
- tray
- 受控 external URL 打开
- 必要 OS integration

### Preload / contextBridge

- 只暴露最小、typed、业务语义明确的 API。
- 不把 `ipcRenderer`、`fs`、`shell`、`process` 等原始 Electron/Node 对象直接暴露给 Renderer。

### IPC

必须：

- channel allowlist
- 请求参数验证
- 返回值边界清晰
- 验证 IPC sender / frame 来源

禁止：

- generic `execute(command)`
- generic shell IPC
- generic arbitrary filesystem IPC
- Renderer 可传任意路径/命令并由 Main 无条件执行

### Navigation / Window

- 禁止任意页面导航。
- 限制 `window.open` / new-window 行为。
- 外部 URL 仅允许经过 scheme/host 校验后由受控逻辑打开。
- 不加载不可信远程页面进入拥有 preload 能力的 BrowserWindow。

### CSP

- Renderer 必须配置合理 Content Security Policy。
- 尽量避免 `unsafe-eval`、任意远程脚本和无限制连接源。

### 与 ADR-029 的关系

- ADR-029：规定“Electron 是什么”——客户端，不是 Backend。
- ADR-037：规定“Electron 怎么安全运行”——Main / Preload / Renderer / IPC 的安全边界。
- ADR-019：旧版 Electron 后端宿主安全设计，已由 ADR-029 + ADR-037 取代。

- **状态**：`Accepted`

---

# 5. 正式业务基线（ADR-038 ~ ADR-045）

## ADR-038：LearningSpace 是一级知识与学习数据隔离边界

- **背景**：用户需要同时学习“数据库管理”“Java 学习”等不同主题，进入某个分类后，资料、知识、AI、题库、考试、错题、掌握度和计划都不能与其他主题混合。
- **决策**：引入 `LearningSpace` 作为一级业务作用域。
- **规则**：
  - 用户先选择/进入 LearningSpace，再进行日常学习。
  - Source、KnowledgePoint、Question、Exam 等内容实体必须归属明确 `spaceId`。
  - Note、Practice、WrongQuestion、Review、Mastery、StudyPlan、ExamAttempt、AIConversation 等用户状态必须同时具备 `userId + spaceId` 或可通过强关系确定两者。
  - AI Retrieval、Search、Practice selection、Exam paper generation 均必须默认限制当前 `spaceId`。
  - 隔离由 Spring Boot 服务端执行，不能只靠前端隐藏。
  - 创建关联关系时必须验证双方属于同一 LearningSpace。
- **空间内结构**：LearningSpace 内部可以有 `KnowledgeCategory`；单个资料自己的目录使用 `SourceOutlineNode`，二者不混用。
- **状态**：`Accepted`

---

## ADR-039：学习资料采用 RAW → EXTRACTED → DERIVED 分层并保留 Provenance

- **背景**：核心数据来源可能是 ZIP、教材照片、PDF、Markdown、TXT 或管理员录入。OCR/AI 会出错且未来可能更换模型，因此不能只保存最终 AI 结果。
- **决策**：资料处理采用三层：

```text
RAW       原始文件/图片/ZIP/PDF
  ↓
EXTRACTED 页面、OCR/解析文本、目录、ContentBlock
  ↓
DERIVED   KnowledgePoint、Question、Summary、AI Explanation 等
```

- **规则**：
  - RAW 原始文件不得被重新 OCR/AI 覆盖。
  - EXTRACTED/DERIVED 可以重算或产生新版本。
  - 自动生成的正式内容默认经过 Review/Publish。
  - 从 Source 派生的 KnowledgePoint/Question 应通过明确关联关系追溯到 ContentBlock/SourcePage。
  - 管理员直接整理的内容标记 `ADMIN_CURATED`，没有真实资料来源时不得伪造引用。
- **状态**：`Accepted`

---

## ADR-040：KnowledgePoint 是核心学习单元，但 Source/Content 是事实来源

- **背景**：真实业务已确认系统围绕知识点进行学习、练习、复习、考试和掌握度管理，但知识点不是原始输入。
- **决策**：`KnowledgePoint` 正式进入核心模型。
- **关系**：

```text
LearningSpace
   ↓
Source / Content
   ↓
KnowledgePoint
   ↓
Question / Practice / Review / Exam / Mastery / StudyPlan
```

- 一个 KnowledgePoint 可来源于多个 ContentBlock。
- 一个 Question 可关联多个 KnowledgePoint。
- KnowledgePoint 的用户掌握状态不写在 KnowledgePoint 本身，而由 `Mastery(user, space, knowledgePoint)` 管理。
- 不再使用“KnowledgePoint 是系统唯一价值锚点”的表述。
- **状态**：`Accepted`

---

## ADR-041：Practice 与 Exam 是两个独立一级学习流程

- **背景**：练习目标是训练，考试目标是测量；两者在反馈、时间、AI 辅导、历史还原等方面规则不同。
- **决策**：Practice 与 Exam 共用 Question 内容资源，但使用独立 Session/Attempt 模型。
- **Practice**：允许即时正确性、解析、AI 讲解和变式练习。
- **Exam**：
  - 固定开始/结束和时长。
  - 考试过程中默认不展示答案和 AI 解题提示。
  - 使用 `ExamPaper + ExamQuestion`。
  - `ExamQuestion` 保存题目快照、顺序和分值，避免题库后续编辑改变历史试卷。
  - 交卷后生成 `ExamResult + ExamDiagnosis`。
  - Diagnosis 必须能反馈 Mastery、Review 和 StudyPlan。
- **禁止**：只用 `PracticeSession.mode = EXAM` 代替完整考试业务。
- **状态**：`Accepted`

---

## ADR-042：Mastery 是当前能力状态，Review 与 StudyPlan 消费学习证据

- **背景**：系统需要把 Practice、错题、Review、Exam 连接成长期学习状态，而不是每个模块彼此独立。
- **决策**：
  - `Mastery` 表示用户在一个 LearningSpace 的一个 KnowledgePoint 上的**当前状态**。
  - 历史事实由 PracticeAnswer、ReviewRecord、ExamAnswer/Diagnosis 等保存。
  - MasteryService 消费结构化学习证据更新当前状态。
  - ReviewTask 可由错误、低掌握度、考试诊断、计划或用户手动触发。
  - StudyPlan 至少支持 `LEARN / PRACTICE / REVIEW / EXAM`。
  - AI 可提供建议，但用户可以调整/跳过计划。
- **算法**：第一版使用可解释策略；不在本 ADR 固定 SM-2 或黑盒 AI 评分。
- **状态**：`Accepted`

---

## ADR-043：Admin Web 是内容生产/审核/治理后台

- **背景**：管理员不仅需要用户管理，还需要直接录入原始资料、修正 OCR、维护知识点、题库和考试。
- **决策**：Admin Web 正式承担：
  - User / Role 管理
  - LearningSpace 管理
  - Source 上传和 `ADMIN_MANUAL` 原始资料录入
  - 页面顺序/OCR/目录/Content Review
  - KnowledgePoint 管理
  - Question 管理
  - Exam/ExamBlueprint 管理
  - Ingestion/AI Job 管理
  - System Config
- **规则**：Admin 的所有写操作仍通过 Spring Boot API；Admin Web 不直接访问 MySQL/Storage。
- **状态**：`Accepted`

---

## ADR-044：本地资料导入以文件内容/流上传为正式协议，后端不依赖客户端绝对路径

- **背景**：当前 Desktop 与 Spring Boot 同机，但未来 Spring Boot 会迁到服务器。若 API 设计成让 Backend 读取 `D:\...`，服务器阶段将不可用。
- **决策**：

```text
Desktop file/folder selection
→ Desktop reads file bytes/stream
→ HTTP upload
→ Spring Boot
→ StorageService
```

- 本地开发时上传到 localhost；未来只切换 API Base URL。
- `D:\...` 等本地路径只属于 Desktop OS integration，不成为服务端业务事实。
- 后续如超大本地数据确有性能问题，可增加经过明确 ADR/设计的 local optimization，但不能破坏通用上传协议。
- **状态**：`Accepted`

---

## ADR-045：资料型 AI 必须 Space-scoped、可溯源，AI 派生内容默认先审核

- **背景**：AI 是核心增强能力，但不同 LearningSpace 的知识必须隔离，且用户需要知道回答/知识点/题目来自哪里。
- **决策**：
  - 基于已导入资料的 AI Retrieval 必须限制当前 LearningSpace。
  - AI 回答应尽可能返回 ContentBlock/SourcePage citation。
  - 如果使用模型的一般知识补充，数据/UI 应允许与“资料内依据”区分。
  - AI 批量生成 KnowledgePoint/Question 默认产生 DRAFT/NEEDS_REVIEW，不直接污染 PUBLISHED 数据。
  - Provider/Model 可替换，业务对象不能绑定 SenseNova。
- **考试规则**：进行中的 ExamAttempt 默认不能使用普通 AI Tutor 获取当前题答案；由服务端状态规则限制。
- **状态**：`Accepted`

---

# 6. 最终技术 + 业务基线摘要

正式主干：

```text
Desktop / Admin Web / Future Android
                ↓
          Spring Boot
          Modular Monolith
                ↓
        MySQL + StorageService
```

正式业务边界：

```text
User
 ↓
LearningSpace
 ↓
Source → Extracted Content → KnowledgePoint
                               ↓
                    Learn / Note / AI Tutor
                               ↓
                           Practice
                               ↓
                         WrongQuestion
                               ↓
                            Review
                               ↓
                             Exam
                               ↓
                         Diagnosis
                               ↓
                           Mastery
                               ↓
                         StudyPlan
```

内容和状态均以 LearningSpace 隔离。

Admin Web 是内容治理后台；Desktop 是主要学习客户端和本地资料入口。

# 7. 仍然开放但不阻塞业务封版的决策

以下内容不影响当前核心业务定义，继续保持 Provisional/Spike 后决定：

- ADR-006：核心闭环在 AI 完全不可用时需要保留到什么程度。
- ADR-007：Review 是否采用 SM-2 或其他算法。
- ADR-012：Note/Content 编辑是否以 Markdown 为主格式。
- ADR-034：中文全文搜索最终采用 MySQL 还是专用搜索引擎。
- OCR 引擎/文档解析库的具体实现。
- Mastery 第一版具体评分公式。
- Subjective Question 的最终 AI/人工评分策略。
- Android 技术栈。

这些问题通过 Spike/真实使用数据决定，不再阻塞建立 Git Baseline 和开始技术验证。

# 8. 后续 ADR 触发条件

| 触发条件 | 需要的新决策 |
|---|---|
| 正式接入 Flyway | Migration Executor / Baseline / Schema History |
| 固定 OCR 引擎或引入独立 OCR Service | Extraction Provider Architecture |
| 引入对象存储 | Local → Object Storage |
| MySQL 中文搜索不满足 | Search Engine |
| Android 正式启动 | Android Technology Stack |
| 需要离线数据同步 | Offline-first / Conflict Resolution |
| 需要多人共享 LearningSpace | Membership / Sharing / Authorization |
| 需要多租户 | Tenant Model |
| 需要 MQ | Async Event / Reliability Model |
| 需要微服务 | Service Boundary / Data Boundary |
| API 破坏性升级 | API v2 |

# 9. 当前工程禁止事项

- 不绕过 Spring Boot 让客户端直连 MySQL。
- 不跨 LearningSpace 查询/关联学习数据。
- 不将 AI 输出当成 RAW 原始资料。
- 不让批量 AI 生成内容默认直接 Published。
- 不使用 `questionIds JSON` 表示正式 M:N/有序关系。
- 不把历史 ExamPaper 绑定到会变化的当前 Question 内容而无快照。
- 不自研 migration framework。
- 不提前微服务化/引入 Redis、MQ、Elasticsearch、Graph DB。
- Hermes 未经用户明确授权不 commit/push。

# 10. 文档维护规则

1. `decisions.md` 是 ADR 唯一 Source of Truth。
2. `business-baseline.md` 是业务定义总入口。
3. ADR 编号永久唯一，不复用。
4. Accepted 被替代时新增 ADR 显式 Supersede。
5. Provisional 不能当作已确定实现。
6. 业务实体细节以 `data-model.md` 为准，接口规则以 `api-guidelines.md` 为准。
7. 修改业务边界后必须同步 requirements / feature-map / data-model / development-plan。
