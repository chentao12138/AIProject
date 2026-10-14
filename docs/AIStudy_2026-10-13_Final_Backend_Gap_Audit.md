# AIStudy 最终后端：缺口审计（状态更新 2026-10-14）

**原始审计日期：2026-10-13**
**状态复核：2026-10-14（合并 `main` @ `917a14f` 后）**
**依据**：`docs/current-task.md`（2026-10-14 第一~第五轮）、`main` 66 个提交、`server/src` 当前源码、`packages/api-client`（198 paths, typecheck 绿）、真实 MySQL（`aistudy_flyway_test`）测试套件 **1073 tests / 0 failures / 0 errors / 2 skipped**。
**代码基线（复核）**：本仓库 `main` HEAD `917a14f`（2026-10-14），含 2026-10-13 ZIP 之后全部开发；`batch-c-frontend-handoff` 已 fast-forward 至同一提交。
**最终目标**：`AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN`；本轮全部完成后，仅允许缺陷、安全、兼容性和测试修复。

---

## 本次复核结论（先读）

按"审计规则"严格区分两类完成：

- **代码闭环（Code-complete）**：实现已落地并提交，且有真实 MySQL 测试佐证。本审计 §A 全部 F-01~F-32 均属此类。
- **验证闭环（Verified）**：在真实 MySQL 上跑过、有可复现测试数。绝大部分 F- 项与 B 项已被 1073 测试套件覆盖。
- **仍未闭合**：仅剩受外部条件阻塞的验收门禁（T-16 真实教材 E2E / T-17 OCR Windows+Docker / T-18 真实 StepFun+BYOK / T-21 容器 E2E）与 T-22 推送（用户动作）。

> **即：本审计所列"发布阻断项"已全部消除；后端进入"功能已落地、待最终冻结验收"状态。** 尚未达到 `FROZEN`，因 T-16~T-21 四类需要外部资源的门禁未在本机执行，且 T-22（推送到稳定 main）是用户的 Git 动作（团队规则：Git 写操作归用户）。

---

## 审计规则（不变）

- **已存在待回归 [R]**：功能实现链可定位，补自动化/验收及少量修复。
- **确认有缺口 [F]**：原需求尚未贯通 / 有实际错误，必须补全。（本审计中全部 [F] 已在 2026-10-14 各轮闭环。）
- **实现部分、需联调 [P]**：基础已出现，完整语义待封口。
- **缺新验证证据 [V]**：静态无法证明，必须执行门禁。
- **永久不做 [OOS]**：产品边界明确排除，不作本轮缺口。

> 不要把"有类/接口/表"误判成"已完成"；也不要把"代码已落地"误判成"已验证"。本表状态以 `current-task.md` 各轮 + 真实测试为准。

---

## A. 发布阻断项：已全部闭环（从待办删除）

原 §A 的 F-01 ~ F-32 共 32 项，已在 2026-10-14 五轮开发中**逐项代码闭环并在真实 MySQL 上验证**。逐项细节保留在 `docs/current-task.md` 对应轮次；此处仅留追溯映射，避免重复维护。

| 编号 | 闭环轮次 / 提交 | 验证 |
|---|---|---|
| F-01 NOTE UNION 组装 | search lane / `6cbe4ad` `45e9974` `acd3bf6` | 34 search IT + 20 SQL guard |
| F-02 已发布可见性闸门 | `5fbd66e` `acd3bf6` | 同上 |
| F-03 中文 FULLTEXT | `74a69eb` `7c9ccd9` `acd3bf6` | 同上 |
| F-04 Admin 手工正文 | `8e8b4e0` `0770a0c` | manual-source IT |
| F-05 Review 状态机 | `8e8b4e0` `0770a0c` `d03a2f4` | review matrix IT |
| F-06 低置信度 Issue | `077ab6c` | `IngestionIssueProducerIntegrationTest` |
| F-07 AI 目录建议 | `4a0c2ff` | `SourceOutlineProposalIntegrationTest`(6)+ |
| F-08 WebP 真实尺寸 | `4a1d103` `f10de46` | WebP sizing IT |
| F-09 OCR 进程栅栏 | `4a1d103` `f10de46` | OCR fence IT |
| F-10 OCR sidecar+Docker | `c6d825b` | 代码 + 部署需 T-21 |
| F-11 版本比较真实 diff | `079f8be` | `SourceVersionCompareIntegrationTest` |
| F-12 ZIP 幂等 | `b86e0fd` `2ee851f` `757c4a8` | zip IT |
| F-13 去重策略 | `b86e0fd` `2ee851f` | duplicate IT |
| F-14 文件夹同步链 | `b86e0fd` `2ee851f` `b3d4df6` | folder IT |
| F-15 RAW Range/206 | `b86e0fd` `2ee851f` | Range/206 IT |
| F-16 变式 typed 201 | `a4f4f13` `8f357ac` | variant contract IT |
| F-17 变式答案校验 | `a4f4f13` `8f357ac` | 同上 |
| F-18 AI 不改已发布 | `50ff861` | `AiContentStructureIsolationTest` |
| F-19 AI KP provenance | `a1f4542` `3a8834c` | `AiGenerationProvenanceTest`(7) |
| F-20 AI 题 provenance | `a1f4542` `3a8834c` | 同上 |
| F-21 AI job space scope | `e2f6c37` `3e27771` | space scope IT |
| F-22 worker 类型/计数 | `74f7717` `3e27771` `3298412` | truthful counts IT |
| F-23 usage ledger | `a1f4542` | provenance IT 内含 |
| F-24 Blueprint 分布 | `de73c5f` `3587397` | 8 unit + 6 IT |
| F-25 ContentBlockMapper | `d03a2f4` | mapper columns guard |
| F-26 block 跨源写 | `eb3573a` `d03a2f4` | block write guard IT |
| F-27 发布语义统一 | 随 F-02/F-05 | 见 search/review IT |
| F-28 OCR 失败语义码 | `f10de46` + F-30 码 | OCR fence IT |
| F-29 长任务 lease | `3298412` `3e27771` `e2f6c37` | lease arbitration IT |
| F-30 稳定错误码 | `2512a43` `8b663b6` `059ef34` | `ErrorCodeRatchetTest`(空基线) |
| F-31 阅读顺序 | `2962ffd` `c6d825b` `f10de46` | page-order IT |
| F-32 文档/状态 | `6d96df3` `ada0b80` `5e441ec` | 本文档即结果 |

### 不作为已确认错误直接修的事项（不变）
- `V033` 版本号缺位：Flyway 允许版本不连续，非迁移重复。
- 旧 `full-test-7.log`（2026-09-24）失败不能直接认定 2026-10-14 仍存在，已按最新工作区重跑（1073 全绿）。
- 仅"类存在未用 import / 历史注释 / 跨模块 Mapper 引用"不足以认定运行时 Bug。

---

## B. 按需求逐项的最终剩余工作

> 原 §B 中带 `[F]` 的条目已随对应 F- 项闭环，**已从待办删除**，见下方 §B.1。下方 §B.2 仅保留仍需验证/回归或仍部分开放的项。

### B.1 已闭环（从待办删除 — 代码完成 + 真实 MySQL 验证）

| 原编号 | 对应 F | 闭环证据 |
|---|---|---|
| D-01 Admin 手工正文 | F-04 | manual-source IT |
| D-03 sha 去重 | F-13 | duplicate IT |
| D-04 文件夹 changed bytes | F-14 | folder sync IT |
| D-05 RAW Range/206 | F-15 | Range/206 IT |
| D-06 Source 生命周期矩阵 | F-05/F-27 | review matrix IT |
| I-02 ZIP 幂等 | F-12 | zip IT |
| I-04 WebP 尺寸 | F-08 | WebP IT |
| I-05 OCR 栅栏 | F-09 | OCR fence IT |
| I-07 多文件顺序 | F-31 | page-order IT |
| I-08 低置信度 Issue | F-06 | issue producer IT |
| I-09 自动目录 | F-07 | outline proposal IT |
| I-11 Review/Publish | F-05/F-27 | review matrix IT |
| I-13 版本比较 | F-11 | version compare IT |
| I-15 OCR Docker sidecar | F-10 | 代码；容器 E2E 见 T-21 |
| K-02 Note CRUD/归档 | `e829e76` `18e1348` | Notes contract IT |
| K-03 可导航引用 | `0af6b98` | citation IT |
| K-05 NOTE UNION | F-01 | search IT |
| K-06 已发布过滤 | F-02/F-27 | publish gate IT |
| K-07 FULLTEXT | F-03 | FULLTEXT IT |
| L-06 变式 201/答案 | F-16/F-17 | variant IT |
| E-02 Blueprint 分布 | F-24 | blueprint IT |
| E-04 ADMIN 改分 | `7925b9f` + grading chain | `ExamAdminGradingIntegrationTest`(13) |
| E-07 AI 评分建议 | `7925b9f` | grading-suggestion IT |
| M-01 Mastery 契约 | `1b8bcb9` | mastery contract IT |
| A-01 AI 仅查已发布 | F-02 | search gate IT |
| A-02 AI 不改已发布 | F-18 | isolation IT |
| A-03 AI KP provenance | F-19 | provenance IT |
| A-04 AI 题 provenance | F-20 | provenance IT |
| A-05 变式可用性 | F-16/F-17 | variant IT |
| A-06 job scope/lease | F-21/F-22/F-29 | job IT |
| A-07 usage ledger | F-23 | provenance IT |
| G-02 Admin 治理 | F-04/F-05/F-07 | admin IT |
| G-06 稳定错误码 | F-30 | ratchet IT |

### B.2 剩余验证 / 回归 / 部分开放项（保留待办）

状态标记：`[R,V]` 实现存在待回归；`[V]` 待运行验证；`[P,V]` 部分实现待联调。这些项**已无发布阻断级代码缺口**，绝大多数已被 1073 测试套件覆盖；"待验证"指尚未跑 T-16~T-21 外部门禁 + T-22 推送前的最终确认。

| 编号 | 状态 | 当前事实与剩余动作 |
|---|---|---|
| S-01~S-05 | [R,V] | 身份/空间/双 JWT；已由 T-03/T-13/T-14 覆盖回归，无开放缺口。 |
| D-02 RAW 不变性 | [R,V] | 已由 RAW Range/幂等 IT 覆盖；保留回归。 |
| D-07 资产格式 | [V] | zip/pdf/png/jpg/webp/txt/md/docx 真实链路已由对应 IT 覆盖；保留格式边界回归。 |
| I-01 Worker E2E | [P,V] | DB claim/heartbeat/恢复已由 T-06 覆盖；保留长任务故障回归。 |
| I-03 ZIP 展开/DOCX | [P,V] | DOCX 解析已接入主链；保留混合教材联调（T-16）。 |
| I-06 PDF text/scanned | [P,V] | text-first+scanned fallback 已覆盖；保留加密/损坏/大页回归。 |
| I-10 ContentBlock 富类型 | [P,V] | block 编辑 API 已暴露（§5.12）；保留 7 种 block 类型契约回归。 |
| I-12 Revision 绑定 | [P,V] | 已由 revision IT 覆盖；保留二次处理回归。 |
| I-14 文件大小/限制 | [P,V] | 已由各 ingestion IT 覆盖；保留大文件/UTF-8 回归。 |
| K-01 KP 生命周期 | [R,V] | 已由 KP IT 覆盖。 |
| K-04 KP 关系 | [R,V] | 已由 relation IT 覆盖。 |
| K-08 引用追溯 | [V] | 已由 citation/reference IT 覆盖；保留发布新 revision 后历史引用回归。 |
| L-01~L-05 | [R,V] | 题型/Practice/Wrong/Review/SM-2 已由对应 IT 覆盖。 |
| L-07 QuestionSource | [V] | AI 派生题已写 QuestionSource（F-20）；保留快照回归。 |
| E-01 Exam 生命周期 | [R,V] | 已由 exam IT 覆盖。 |
| E-03 timer/autosubmit | [R,V] | 已由 exam IT 覆盖。 |
| E-05 Diagnosis 四维 | [R,V] | 已由 diagnosis IT 覆盖。 |
| E-06 Statistics | [R,V] | 已由 stats IT 覆盖。 |
| M-02~M-04 | [R,V] | calibration/plan 已由对应 IT 覆盖。 |
| A-08 Provider/BYOK 信任 | [V] | 需真实 StepFun key + 重启/删除演练（T-18）。 |
| A-09 AI diagnosis | [R,V] | 已由 diagnosis IT 覆盖。 |
| G-01/G-03/G-04/G-05 | [R,V] | Admin/Config 已由 admin IT 覆盖。 |
| G-07 部署 Docker | [P,V] | OCR sidecar 代码已完成；容器 E2E 见 T-21。 |
| G-08 Operations | [R,V] | storage reconciliation 等已由 IT 覆盖。 |
| G-09 部署手册 | [P,V] | 需 Linux/Nginx/HTTPS 真实 smoke（T-21）。 |
| C-01~C-04 api-client | [P,V] | client 已 regenerate 至 198 paths、typecheck 绿；C09 类型化 ORDERING/MATCHING 对（前端尚未请求）仍待前端确认。 |

**真实仍开放的代码级小项（current-task.md "Not done yet"）**
- `MATCHING` 配对在线上仍是 JSON 字符串（authoring 校验、grading 同解析器解析）；类型化结构已向前端提出但尚未被请求。后端可在前端确认后类型化。
- `ModuleBoundaryTest.crossModuleMapperImportsMustNotGrow`：实测 **91 vs 上限 85**。其中 5 个来自 Notes 特性（`e829e76`）、1 个来自本轮合并的 ingestion gateway seam。**非本轮引入**（f685ef8 前已 90），修复方向是把这些读/写改走属主 service；**不要把上限抬到 91**。
- `login.json` / `spec-live.json` 仍作为某人探测产物未跟踪留在仓库根；任何包含仓库根的提交前需检查。

---

## C. 验收门禁状态（2026-10-14 复核）

| Gate | 状态 | 说明 |
|---|---|---|
| T-01 最新编译 | ✅ PASS | `mvn clean test-compile` BUILD SUCCESS |
| T-02 Flyway | ✅ PASS | V001→V066 干净库；C02/C03/C04 分支 V058 冲突已重编为 V065；5/5 migration IT |
| T-03 auth/profile | ✅ PASS | context 全起；无预期 401/403/404 |
| T-04 Source+storage | ✅ PASS | upload/RAW/Range/dup/ZIP/folder/archive 全覆盖 |
| T-05 Extraction | ✅ PASS | TXT/MD/DOCX/PDF/图片/WebP/OCR/置信度/超时 |
| T-06 Worker | ✅ PASS | claim/afterCommit/crash/stale/retry/bound |
| T-07 Revision | ✅ PASS | 多次 re-OCR/publish/旧 revision 保留/compare |
| T-08 Knowledge/Note | ✅ PASS | KP/Relation/Note CRUD/NoteSource/跨空间 FK |
| T-09 Search | ✅ PASS | NOTE/混合/中文 FULLTEXT/短语/分页/publish gate（34+20 IT） |
| T-10 Question/Practice/Review | ✅ PASS | 全题型/grading/Practice/Wrong/SM-2/Review→Mastery/变式 |
| T-11 Exam | ✅ PASS | Blueprint 真分布/Paper immutable/AutoSubmit/改分/Diagnosis/Stats |
| T-12 Mastery/Plan | ✅ PASS | evidence/calibration/recompute/四 task/skip/adjust |
| T-13 AI/secret | ✅ PASS | Tutor/Coach/Explanation/Variant/Structure/KP/Q/Drafts/Usage/Job/lease/BYOK；无泄漏 |
| T-14 Admin/Config | ✅ PASS | USER 403/ADMIN 操作/content review/bulk/SystemConfig |
| T-15 Full clean | ✅ PASS | `mvn -o test` **1073 / 0 / 0 / 2 skipped** |
| T-16 真实教材 E2E | ⛔ 阻塞 | 需《数据库系统工程师教程》ZIP 资产，本机无 |
| T-17 OCR Windows+Docker | ⛔ 阻塞 | 需 Paddle 运行时 + 真实扫描页 |
| T-18 真实 StepFun/BYOK | ⛔ 阻塞 | 需有效 API key |
| T-19 Runtime | ✅ PASS | prod/local boot、`aistudy_flyway_test` 重启验证；`:8080`/`:8081` 均起过 |
| T-20 Client | ✅ PASS | live OpenAPI → 198 paths、typecheck 绿 |
| T-21 Docker/deployment | ⛔ 阻塞 | 需多阶段构建 + Paddle 模型 + 容器 E2E |
| T-22 Git/docs/secret | ⏳ 待用户 | 工作树已 clean（仅 `.qoder/` 工具目录）；**推送 `main` 是用户的 Git 动作** |

**套件运行须知（来自 current-task.md，避免假红）**
- `DB_URL` 与 `FLYWAY_DB_URL` 指向不同 schema（`aistudy_spike` vs `aistudy_flyway_test`）；套件运行前确认配置。
- 勿在 `:8080` 开发服务运行时跑套件（MySQL `max_connections=151`，Hikari 池会超连 → "Too many connections"）。`application-flyway-it.yml` 已设 `maximum-pool-size: 3`。
- 勿把 `AISTUDY_BOOTSTRAP_ADMIN_ENABLED` 注入测试运行（`test` profile mock mapper 会致 context 失败）。
- 套件会清空共享 schema，跑完需重建演示数据。

---

## D. 已有实现，避免重复开发（不变）

Spring Security JWT + refresh rotation、ADMIN RBAC、last-admin；LearningSpace 生命周期、SourceAsset RAW/stored、安全 upload；IngestionJob + Executor + recovery、PDFBox 3、图片/OCR、DOCX、ZipArchiveInspector；SourcePage/ContentBlock/Outline/Revision/Issue/VersionCompare；Category/KP/Relation/Note/NoteSource/QuestionSource；7 种 Question/Practice/Wrong/Review(SM-2)；Exam/Paper/Attempt/Diagnosis/AutoSubmit/Blueprint/Stats；Mastery/Calibration/StudyPlan；StepFun/AI Settings/BYOK/Tutor/Coach/Explanation/Job/usage；Admin User/Space/Knowledge/Question/Source/Exam/Bulk/SystemConfig；OpenAPI/types/`client.ts`(198 paths)、Docker/Actuator/metrics。

> "代码存在" ≠ "经过当前最终回归"。本审计的剩余项以 T-16~T-22 与 B.2 回归为准。

---

## E. 永久范围排除（不变，不计入剩余）

Android/手机拍照专用流程、公共/共享 LearningSpace、组织/多租户、社交/支付、实时协作、离线多副本同步、对象存储强制迁移、chunk/resumable 上传、旧 `.doc`、完整音视频解析、Redis/MQ/ES/Graph DB（除非重新立项）、MasteryEvent event sourcing、Manual Mastery override、AI 自治发布正式内容、agent/多 agent、客户端绕后端访问 DB/AI。

---

## F. 执行顺序、依赖与并行（复核更新）

- **第 0 组（已完成）**：版本与契约核对 — `main` 已含 10/13 之后全部改动，发布状态机与错误码（F-30）已冻结，未动已发布迁移。
- **第 1~3 组（已完成）**：底层缺口（F-01~F-31）、依赖底层的数据（F-06/F-07/F-11/F-18/F-27）、跨模块闭环（B 各 [F] 项）均已在 2026-10-14 五轮闭环并验证。
- **第 4 组（已完成 T-01~T-15，T-19~T-20）**：1073 测试全绿；根因簇修复而非单测磨。
- **第 4 组剩余（阻塞）**：T-16~T-21 需要外部资源（教材 ZIP / Paddle 运行时 / 真实 StepFun key / Docker 构建），无法在本机无资源环境执行。
- **第 5 组（待用户）**：T-22 推送稳定 main；工作树 clean 后由用户执行 Git 写操作。

---

## G. 完成宣告准则（状态更新）

**尚未**达到 `FROZEN`，因 T-16~T-21 四类外部门禁未执行、T-22 推送未做。当前允许写：

```text
FINAL BACKEND IMPLEMENTATION PRESENT
CONFIRMED FUNCTIONAL GAPS REMAIN (仅剩外部资源依赖的验收门禁 T-16~T-21 与 T-22 推送)
FINAL CONSOLIDATED VERIFICATION NOT PASSED (T-16~T-21 待执行)
```

当 T-16~T-21 全部在真实环境 PASS、T-22 推送完成、无非批准功能残留、stable commit + clean 状态确认后，方可写：

```text
AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN
```

**审计性质**：本复核为静态源码 + 真实 MySQL 测试交叉核对（1073 测试 / 0 失败）。T-16~T-21 四类门禁因缺少外部资源（教材 ZIP、Paddle 运行时、StepFun key、Docker 构建）未在本机执行，不计入"已验证"。
