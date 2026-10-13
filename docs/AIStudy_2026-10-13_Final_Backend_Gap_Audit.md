# AIStudy 最终后端：最初需求 × 当前源码差距全量审计

**审计日期：2026-10-13**  
**依据**：本次 `AIProject.zip` 中的实际 `server/src/main`、迁移 SQL、`server/src/test`、`packages/api-client`、`docs/requirements.md`（Accepted）、`docs/feature-map.md`（Accepted）、`docs/AIStudy_Final_Backend_Feature_Freeze_Master_Checklist.md`、`docs/Final_Backend_Feature_Coverage_Audit.md`、`docs/current-task.md`、Maven 日志。  
**代码基线**：ZIP 中的 `main` HEAD `1b8bcb9`（2026-09-24），附有**大量未提交的工作树更改**。ZIP 快照与用户本机实时状态可能不同。  
**最终目标**：`AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN`；本轮全部完成后，仅允许缺陷、安全、兼容性和测试修复，不保留普通后端新增功能待办。

## 审计规则：不要把“有类/接口/表”误判成“已完成”

- **已存在待回归 [R]**：功能实现链在源码中可定位，本轮主要补自动化/真实验收及少量不一致修复。
- **确认有缺口 [F]**：源码能证明需求尚未贯通、存在实际错误/危险边界；开发必须补全/修复。
- **实现部分、需联调 [P]**：基础模型或 API 已出现，但完整业务语义未封口。
- **缺新验证证据 [V]**：静态无法证明行为正确，必须执行编译/真实数据库/OCR/AI/部署等门禁。
- **永久不做 [OOS]**：原 Accepted 文档的 Future 或最终 Freeze 文档中正式排除；**不作为本轮缺口**。

> **特别注意**：历史 P0/P1/P2 标签是优先级，不是以后是否再开发后端的许可。本审计只针对产品决定纳入当前最终冻结的功能。不要因为某个模块已有 Controller/Mapper 就删掉对应测试与验收项。

---

## A. 发布阻断项：能够从当前代码确认的缺口与缺陷

本表列出**已经有直接代码证据**的事项，开发人员必须首先确认最新工作区是否仍存在；存在就完成最小修复，并在后续测试中建立回归用例。无运行证据处不把实际 HTTP 结果写死。

| 编号 | 级别 | 已核查位置 | 当前事实、影响与必须完成的修复 | 验收临界条件 |
|---|---|---|---|---|
| F-01 | **阻断** | `search/mapper/SearchMapper.xml`：`searchProjection`、`searchCount` NOTE 分支 | 动态 SQL 的其它 SELECT 可在尾部放 `UNION ALL`，NOTE 分支本身又以 `UNION ALL` 开头；当类型只有 NOTE 或某些 `SOURCE+NOTE` 组合时可能生成开头 UNION/重复 UNION，SQL 非法。统一用规范的 UNION 组装策略。 | `types=NOTE`、`SOURCE,NOTE`、`WRONG_QUESTION,NOTE`、空过滤的真实 MySQL 查询均合法，count 与分页一致。 |
| F-02 | **阻断（权限/发布语义）** | `SearchMapper.xml` 的 SOURCE / CONTENT_BLOCK / KNOWLEDGE_POINT / QUESTION 查询 | 查询只验证 owner/space/删除状态，**未过滤已发布 Source/修订/KP/Question**；AI Context 使用 SearchService 时会受到同一问题影响。建立 published revision + published entity 的唯一可见性规则，Note/WrongQuestion 仍各自按用户过滤。 | DRAFT/NEEDS_REVIEW/ARCHIVED 内容不出现在普通 Search/Tutor；PUBLISHED 可检索；admin 审核入口另行授权。 |
| F-03 | **明确未完成** | `V054__search_fulltext_ngram.sql` vs `SearchMapper.xml` | V054 已建立 MySQL ngram FULLTEXT 索引，但查询仍仅使用 `LIKE`，没有 `MATCH ... AGAINST`。结合真实中文数据固定全文检索与短词/特殊字符 fallback。 | 中文全文搜索命中和排序正确，并用 EXPLAIN 确认可用索引；literal `%/_` 不退化。 |
| F-04 | **阻断业务** | `source/service/SourceService.createManual(adminSubject, spaceId, title, content)` | `content` 参数**没有被使用**，只插入一条 Source；没有写 SourcePage/ContentBlock，AdminManual 在实际学习链缺正文。Admin 的 `/admin/sources` controller 也缺明确“手工创建正文”的业务入口。 | Admin 手工输入真实正文后能在 page/block/provenance、review/publish、Search/AI 中看到，且无需直接修改数据库。 |
| F-05 | **阻断审核** | `source/controller/ReviewSourceRequest`；`SourceService.review()` | reviewStatus 只做 `@NotNull`，Service 直接写入输入状态，未见闭合状态白名单/合法迁移矩阵；未检测未解决的高危 IngestionIssue。必须建立严格审核状态机与发布前条件校验。 | 不合法/越级 publish 400/409；待处理 blocker issue 阻止发布；所有已有成功数据按迁移规则兼容。 |
| F-06 | **功能缺口** | `IngestionIssueService.recordIfLowConfidence()` | 实际自动生产者只覆盖 `OCR_LOW_CONFIDENCE`、`EXTRACTION_PARTIAL_FAILURE`；原需求里的低排序置信度、目录结构置信度等 Issue 没有自动产生路径。 | ORDER/OUTLINE/OCR 低置信度各能生成具有 source/page/revision 关联的 Issue，审核端可 resolve/ignore。 |
| F-07 | **功能缺口** | `source/outline/service/SourceOutlineNodeService`、`ai/content/AiContentStructureService` | Outline 有手动 CRUD，AI structuring 却只更新 block type/structured JSON，未生成 proposed SourceOutlineNode 树；实际“目录自动建议→人工审核”的流程缺失。 | 《数据库系统工程师教程》自动提出章/节/小节，标 NEEDS_REVIEW；人工 reparent/correct/save；不得将知识分类当资料目录。 |
| F-08 | **高风险** | `ingestion/image/ImageContentExtractionService` 无 ImageIO reader 的 WebP 分支 | WebP 没有 decoder 时设置 `width=0/height=0` 并继续 OCR，绕过宽高/像素上限，且无法返回真实 metadata。应使用可验证尺寸的 WebP 解码/元数据读取器；不能“magic 合法就当已解析”。 | WebP 宽高准确，超过 maxWidth/maxHeight/maxPixels 拒绝；畸形 RIFF 不会伪成功。 |
| F-09 | **高风险** | `ingestion/ocr/PaddleOcrProcessEngine` | `ProcessBuilder` 无可见超时/kill 栅栏；读取 stdout 不设字节上限；失败日志输出整段 stdout；confidence 解析失败回退 `1.0`；输出文件读取无上限。必须修整进程安全与 OCR 结果协议。 | OCR 超时可结束子进程；输出被限制；失败信息脱敏；无法解析 confidence 不得当 100% 高置信度。 |
| F-10 | **部署阻断** | 仓库根目录 `Dockerfile`、`deploy/docker-compose.yml` | 容器只含 JRE + Spring Jar；没有 PaddleOCR 可执行环境，也没有 OCR sidecar/模型/health wiring；真实 OCR Docker parity 尚未交付。 | 容器中真实中文图片与扫描 PDF OCR 成功，OCR 模型与资源限额可运维，应用无 OCR 依赖时失败行为可预期。 |
| F-11 | **功能缺口** | `ingestion/version/SourceVersionCompareService.compare` | 页面差异使用跨 revision 独立行的 `SourcePage.id` 计算，容易把新版本全部当作 added/removed；outlineAdded/Removed/Changed **硬编码 0**，`updatedContentBlocks` 建空 map 未填；不是真正的 revision diff。 | 两版本相同页面（不同 DB id）能识别对应；能检测 reorder/outline/block 变化并有 bounded diff。 |
| F-12 | **功能缺口** | `IngestionJobService.extractZipEntries()` | 每次展开按条目 `StorageService.store()` + `sourceAssetMapper.insert()`，未查已有 ZIP entry/path+sha 去重；重试可能重复产出 SourceAsset/RAW。 | 相同 ZIP retry、stage resume 不增加重复 asset；同路径不同 hash 创建明确新版本；失败有补偿/回收。 |
| F-13 | **功能缺口** | `SourceAssetService.upload()`、`checkDuplicate()` | upload 直接存 RAW、插入；`checkDuplicate()` 为单独查询，没有完整 pre-upload policy / importAgain decision，也没有稳定 duplicate response。 | 空间内同 hash、不同名；同名不同 hash；skip/importAgain；并发上传，均有确定行为。 |
| F-14 | **功能缺口** | `source/folder/service/FolderImportService.submitEntries()` | 仅保存 snapshot/entries 并按 SHA 标记 ADDED/UNCHANGED，新增条目仍 `sourceAssetId=null`；未提供待上传字节如何绑定到 entry、缺失/删除比较（removed=0）、重同步完成链。 | 第二次同步只上传变化 bytes、能识别新/改/未变/缺失；真实 SourceAsset/ingestion 可引用。 |
| F-15 | **最终契约缺口** | `SourceAssetController.downloadRaw()` | 已有鉴权 RAW stream，但只返回 `InputStreamResource`，没有显式 Range 解析/206/Content-Range/Accept-Ranges。最终 Freeze 清单要求 PDF/大文件 Range。 | 合法 byte ranges → 206；无效 → 416；同一 owner 认证；前端 PDF reader 不需直接访问 storageKey。 |
| F-16 | **阻断前端/HTTP** | `question/controller/VariantController.variantFromPractice()` | `save=true` 分支保存成功后 `throw new ResponseStatusException(HttpStatus.CREATED, saved.toString())`，没有返回 `SavedVariantResponse`。201 被误用作异常，无法取得正常 typed response。 | save=true 返回真正的 201 JSON body/新 DRAFT questionId；save=false 正常 200。 |
| F-17 | **答案正确性** | `question/service/AiVariantService.generateVariant/saveVariant()` | 变式题沿用**原 Question 的 answerDataJson**；生成选项/题干的 JSON 未见完整类型约束/正确答案映射校验。改变选项场景后不保证旧答案正确。 | 变式题有经服务端验证的“题干/选项/答案”一致性；无效模型输出零落库。 |
| F-18 | **核心数据安全** | `ai/content/AiContentStructureService.structureBlocks()` | AI 直接对已有 `ContentBlock` 执行 `updateById` 改 blockType/structuredDataJson；未见以 DRAFT revision / review candidate 隔离，可能直接修改已发布教材内容。 | AI 只写 DRAFT/NEEDS_REVIEW 建议，发布来源不可被 AI 静默改变。 |
| F-19 | **来源追溯/事务** | `ai/knowledge/AiKnowledgeExtractionService.extract()` | 直接 `knowledgePointMapper.insert`，不写 `KnowledgePointSource`，也不验证 source/revision/内容 provenance；没有方法级事务/完整 batch validation。 | 每个 SOURCE_DERIVED/AI_DERIVED KP 能追溯 source/page/block；坏 JSON 不能留下部分被视为正式资料的内容。 |
| F-20 | **来源追溯/事务** | `ai/question/AiQuestionGenerationService.generate()` | 只通过知识点生成 Question/Option，没有写 QuestionKnowledgePoint/QuestionSource；model options key/正确答案未完整校验；方法无 `@Transactional` 且 catch 后返回 null，可能留下孤立或不完整 DRAFT。 | 新题有 KP + Source 引用；validate then atomic persist；失败无部分题目；不得自动 PUBLISHED。 |
| F-21 | **Space 边界** | `AIGenerationJobService.listMine(requester, spaceId, page, size)` | 方法签名有 `spaceId`，却只调用 `selectByRequester(requester, offset, size)`；用户在 Space A 可通过列表看到自己其他 Space 的 AI job，不符合 current-space scoped contract。 | list/count/get/retry 均按 `{spaceId}+JWT subject`，跨 space 404/空列表，结果计数不泄漏。 |
| F-22 | **任务语义** | `AIGenerationJobController.CreateGenerationJobRequest`、`AIGenerationJobWorker` | API 允许 `exam-diagnosis-narrative`，worker switch 只处理 content-structure/knowledge-extraction/question-generation；该类型运行必走 unsupported 分支。worker 在 `processJob()` 设置 successCount 后，又 `+1` 写 finish，统计值偏大。 | 所有允许的 jobType 有真实 worker；成功/失败数反映实体写入数；任务进度可查询且不造假。 |
| F-23 | **调用记录** | `AiUsageRecordService` 全代码调用方 | 仅 Tutor/Explanation/Coach/test-connection 可见显式 usage service 注入；`AiContentStructureService`、`AiKnowledgeExtractionService`、`AiQuestionGenerationService`、`AiVariantService` 无统一 usage ledger 写入。 | 每一种真实 AI purpose 成功/失败均有准确 usage/latency/error 记录，且不泄密。 |
| F-24 | **规则引擎** | `exam/service/ExamBlueprintService` | `questionTypeDistribution`/`difficultyDistribution` 只用于过滤候选可接受的类型/难度，最终 `subList(0,totalCount)`，并未满足每类具体数量/分值比例；`resolveScore` 依赖 difficulty 常量，而非完整规则。 | 可用 fixture 验证蓝图分布和总题数/分值，无法满足规则返回明确 409 而非悄悄减少题数。 |
| F-25 | **潜在运行 SQL** | `source/content/mapper/ContentBlockMapper.selectBySpaceSourceAssetOwner` | 查询谓词使用 `cb.source_asset_id`，但原 `V011 content_block` 表没有此列（所属 asset 经 `source_page` 关联），现有 V039 也只加 outline index。该 mapper 方法当前未见被调用，仍是错误实现风险。 | 修改为经 `source_page` JOIN 关联，测试中直接调用该 mapper 验证 SQL；或删除确认无调用的死接口。 |
| F-26 | **同 Space 跨 Source 写入** | `source/content/service/ContentBlockService.update()` | Path 带 `sourceId`，但只用 `selectByIdSpaceOwner(blockId,spaceId,owner)`，没有校验 `existing.getSourceId()==sourceId`，跨本空间其他 Source 的 blockId 可能被修改；且直接 updateById 不校验 revision/status。 | sourceId 错配 404；只能修改当前允许的草稿/review 版本；发布内容不可静默就地变更。 |
| F-27 | **来源发布语义** | `IngestionRevisionPublisher.publish()`、`SourceService.review()`、`SearchMapper.xml` | Extraction revision 状态与 Source review/publish/status、当前 revision 指针和客户端查询没有一套完备可见性约束；前述 search/AI 直接读 page/block 的情况会受影响。 | PUBLISHED source/revision 唯一可见；未通过 review 内容不会进入 Question/AI 正式来源。 |
| F-28 | **OCR / 任务契约** | `ImageContentExtractionService.extractAndPersist()`、`PdfContentExtractionService` | OCR 失败可仅记录低置信度甚至继续无文本；未见统一区分“无文本可接受”“OCR process failed”“parser failed”并映射稳定 job error/issue；需要和最终产品失败语义对齐。 | 用户能区分无可提取文本/待人工审核/真正任务失败，返回 code 与 Issue 来源明确。 |
| F-29 | **长任务恢复** | `AIGenerationJobService.recoverStaleJobs()`、`AIGenerationJobWorker` | 超过 30 分钟无 heartbeat 的 PROCESSING 会被重新排队；worker 长 AI/多步任务未见 lease heartbeat，存在重复生成风险；worker 无统一有界任务执行器策略。 | 模拟长于 stale lease 的任务、重启/重复 worker、retry；只能产生一次确定的 draft 结果。 |
| F-30 | **错误码统一** | `common/problem/ApiExceptionHandler` 与多个 `ResponseStatusException` | ProblemDetail/错误码框架已出现，但各业务仍大量直接抛含自然语言 reason 的 ResponseStatusException，不能保证前端拥有稳定 machine-readable `errorCode`。 | 前端所有需分支处理的业务错误以 code 判断，不解析英文字串；敏感消息/内部异常不外露。 |
| F-31 | **阅读顺序** | `SourcePageService.batchUpdateOrder()` 与多资产 ingest | 可分批改变部分 pageOrder，但没有统一多资产自动排序证据与完整顺序校验；image page 默认 `pageOrder=1`，真实多页教材可能重复。 | 同 Source 所有 current revision page 排序稳定、无冲突；人工 confirmed order 重抽后保留。 |
| F-32 | **文档/状态** | `docs/current-task.md`、`docs/Final_Backend_Feature_Coverage_Audit.md` | current-task 仍写“仅部分落地”和旧测试统计；audit 仍保留 P，且部分项目现有实现状态已变化。文档不能当成最新通过证据，需完成后重新逐项核对。 | 最终审计报告与当前实际源码、迁移、tests、Git commit 完全一致。 |

### 不作为已确认错误直接修的事项

- `V033` 版本号缺位，但 Flyway **允许版本不连续**；不是 migration duplicate，也不是自动失败原因。当前 V001~V056 文件名没有同号重复。
- 2026-09-24 的 `full-test-7.log` 失败不能自动认定 2026-10-13 源码仍存在同一问题，必须针对最新工作区重跑后判断。
- 仅因 Java 类存在未用的 import、历史注释或跨模块 Mapper 引用，不足以单独认定运行时 Bug；应结合实际调用路径处理。

---

## B. 按最初需求逐项的最终剩余工作（含已建但未闭环模块）

以下按产品能力列出执行者需要交付的内容。状态 `[F]` 对应上表已有证据；`[P]` 表示现有功能有实现但仍需补全；`[R]` 表示保留并做回归；`[V]` 是尚无当前验收证据。**不得把“已实现待验证”全部误报成要重新开发。**

### B1. Identity / Security / LearningSpace

| 编号 | 状态 | 需求对应 | 必须完成的剩余动作 / 完成效果 |
|---|---|---|---|
| S-01 | [R,V] | R-AUTH-001/002/003 | Login/refresh/logout/roles、last admin guard、disable/change-password 后 refresh revoke 进行当前 2026-10 代码回归；继续双 JWT trust domain 分离。 |
| S-02 | [R,V] | R-SPACE-001/003/004 | Space create/rename/archive/restore 已有，验收 archive write policy 对所有 Source、Job、Knowledge、Practice、Exam、AI 生效，不能只挡部分 Controller。 |
| S-03 | [R,V] | R-SPACE-005/006 | SpaceScopeInterceptor 与所有 mapper/Service 共同证明 user A/space B 404；Admin 的跨所有者治理必须走单独授权路径。 |
| S-04 | [P,V] | Admin USER provisioning | 创建 USER、重置密码、自改密码已有接口，补密码策略、审计、并发/失效语义测试，真实管理员操作验收。 |
| S-05 | [V] | NFR-SEC-001/003 | 真实 BYOK AES-GCM、密钥轮替/解密失败、JWT expired/role change、401/403/404 语义，确认不输出凭据。 |

### B2. Source、上传、文件夹、RAW

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| D-01 | [F] | R-SOURCE-001 | 完成 Admin Manual Source 真正文入库（F-04），纳入统一 provenance/review/publish。 |
| D-02 | [R,V] | R-SOURCE-002/003/007 | RAW immutable，storageKey、originalName、MIME/sha、路径隔离与 upload compensation 按最新源码回归。 |
| D-03 | [F] | NFR-DATA-002 | sha256 按 space/source 去重策略、precheck/importAgain/skip 语义与并发冲突（F-13）。 |
| D-04 | [F] | R-SOURCE-006 | Folder snapshot → changed bytes upload → SourceAsset/ingestion → missing/removed 语义与重复 sync 幂等（F-14）；后端不读客户端磁盘绝对路径。 |
| D-05 | [F] | Final Freeze RAW | RAW 下载的 Range/206/416 与 Content-Disposition/Content-Type（F-15），权限/大文件内存安全。 |
| D-06 | [P,V] | Source lifecycle | 注册、处理中、待审、已发、拒绝、归档、恢复状态的合法矩阵；已有 Service 需加入严密校验（F-05/F-27）。 |
| D-07 | [V] | Original asset formats | ZIP/PDF/PNG/JPG/WebP/TXT/Markdown/DOCX 都需真实 upload→job→page/block，不只是文件名 allowlist。 |

### B3. Ingestion、OCR、目录、版本

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| I-01 | [P,V] | R-INGEST-001/002 | 后台 DB-backed worker 已有；补端到端快速返回、原子 claim、claim heartbeat、重启恢复、多 worker 并发、背压、过载、真实 status/progress 断言。 |
| I-02 | [F] | R-INGEST-010 | retry 阶段语义 + ZIP 原始 entry 幂等；不能重复 RAW/SourceAsset/revision（F-12）。 |
| I-03 | [P,V] | R-SOURCE-004/005 | ZIP 实际展开与 nested/zip bomb 保护、DOCX 段落/表格/内嵌图正确解析及补偿；真实混合教材联调。 |
| I-04 | [F] | R-SOURCE-004/R-INGEST-006 | WebP 获取真实宽高/像素、安全拒绝异常文件（F-08）。 |
| I-05 | [F] | R-INGEST-006 | PaddleOCR adapter 超时/输出资源上限/confidence 失败语义/脱敏（F-09）。 |
| I-06 | [P,V] | R-INGEST-006 | PDF text-first+scanned fallback 已有，需中文单页/混合页/加密/损坏/大页真实校验，Issue 与错误码（F-28）。 |
| I-07 | [F] | R-INGEST-004 | 多文件顺序证据、pageOrder 全局约束、人工确认重抽保留（F-31）。 |
| I-08 | [F] | R-INGEST-005 | ORDER_LOW_CONFIDENCE、OUTLINE_LOW_CONFIDENCE 等 Issue 生产者，解决/忽略/阻断发布（F-06）。 |
| I-09 | [F] | R-INGEST-007 | 自动目录提议、SourceOutlineNode 生成、confidence 与人工校正（F-07）。 |
| I-10 | [P,V] | R-INGEST-008 | ContentBlock 全类型的真实 DTO/structuredData/locator/outline 绑定；Admin block split/merge/edit/reorder 规则与多版本安全。 |
| I-11 | [F] | R-INGEST-009 | Review/Publish 审核状态机、未决严重 Issue、重跑版本隔离及 Search 可见性（F-05/F-27）。 |
| I-12 | [P,V] | NFR-DATA-001/NFR-TRACE-001 | ExtractionRevision 向 source_page/block/outline 的绑定、二次处理保留上一 published revision、不会破坏 KP/Note/Question 引用；用真实 FK 验证。 |
| I-13 | [F] | Final Freeze compare | SourceVersionCompare 页/块/目录对应与真实差异（F-11）。 |
| I-14 | [P,V] | File sizes/limits | TXT/Markdown Unicode/UTF-8/大行，DOCX、PDF、ZIP/图片资源上限与错误映射，重复写入时事务安全。 |
| I-15 | [F,V] | Ops OCR | OCR Docker/sidecar + CPU/模型/存储/health/日志/部署恢复（F-10）。 |

### B4. Knowledge、Note、Provenance、Search

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| K-01 | [R,V] | R-KNOW-001/003/004 | KnowledgeCategory/KnowledgePoint 生命周期、层级重挂、防环、Admin 直接整理、published edit 政策真实 DB 回归。 |
| K-02 | [R,V] | R-NOTE-001/002/003 | Note CRUD 与 Note↔KP/Source 已有；验证内容格式、owner/space、归档过滤、关联拒绝的事务全回滚。 |
| K-03 | [P,V] | R-KNOW-002/R-Q-004 | 统一 SourceReference 能跳到 Source→Revision→Page→Block→RAW，包含 archived/unavailable、locator。AI 派生路径补 provenance（F-19/F-20）。 |
| K-04 | [R,V] | R-KNOW-005 | KnowledgePointRelation CRUD 有实现；类型/环/同空间/重复/引用历史保持验收。 |
| K-05 | [F] | R-SEARCH-001 | NOTE type 的 UNION SQL 组装（F-01），类型过滤/分页计数/中文回归。 |
| K-06 | [F] | R-SEARCH-002 / R-AI-002 | 搜索及 AI 的已发布数据过滤和版本选用（F-02/F-27）。 |
| K-07 | [F] | R-SEARCH-003 | 中文 MySQL FULLTEXT 查询与 index 使用（F-03）；长文本和短词 fallback 性能对照。 |
| K-08 | [V] | NFR-TRACE-001 | 同一个教材段落创建 KP/Question/Note/AI citation 后导航回来，发布新 revision 后历史引用仍可追踪。 |

### B5. Question、Practice、WrongQuestion、Review

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| L-01 | [R,V] | R-Q-001/002/003/005 | Question lifecycle + 7 types（含 FILL_BLANK/ORDERING/MATCHING）请求约束、答案模型、options、发布审核和 snapshot 全路径回归。 |
| L-02 | [R,V] | R-PRACTICE-001/002/004 | Practice category/KP/difficulty/type/seed 选择、即时反馈、提交证据/重复提交、历史统计与两空间隔离。 |
| L-03 | [R,V] | R-WRONG-001/002/003 | WrongQuestion 用户状态、错题统计、dismiss/restore 与关联 KP、计划证据回归。 |
| L-04 | [R,V] | R-REVIEW-001/002/003 | WRONG_ANSWER/EXAM_DIAGNOSIS/LOW_MASTERY/MANUAL/SCHEDULED 五触发路径、幂等防重复及 ReviewRecord→Mastery。 |
| L-05 | [R,V] | R-REVIEW-004 | SM-2-like 状态与 due 计算已出现；需要历史数据/状态机/并发/时间边界回归，不能改掉用户 MANUAL task。 |
| L-06 | [F] | R-PRACTICE-003 | AI Variant save typed JSON 201（F-16）和完整可判分答案/选项校验（F-17）。 |
| L-07 | [V] | R-Q-004 | QuestionSource 的真实 source/reference 与历史 snapshot，不泄漏未提交答案。 |

### B6. Exam、Diagnosis、Statistics

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| E-01 | [R,V] | R-EXAM-001/002/003/004 | Exam 类型/草稿编辑/发布/归档、paper version、question snapshot 和绝不变更历史，按多个用户/space 实测。 |
| E-02 | [F] | R-EXAM-011 | ExamBlueprint 总题数/题型/难度/分值分布真正被执行，不能只过滤后 `subList`（F-24）。 |
| E-03 | [R,V] | R-EXAM-005/006/008 | timer、lazy/scheduler auto-submit、并发重复 finalize、结果/逐题输出，考中不泄漏答案/AI 提示。 |
| E-04 | [P,V] | R-EXAM-007 | 主观题 gradingStatus/PARTIALLY_GRADED、ADMIN 人工改分、regrade、审计、更新总成绩/Diagnosis/Mastery 的幂等闭环。 |
| E-05 | [R,V] | R-EXAM-009/010 | Diagnosis 四维：KP/Category/Type/Difficulty；Result→Review/Mastery/Plan。 |
| E-06 | [R,V] | Feature-map Stats | space overview / time-series / exam trend / question-type / mastery movement / cross-space admin aggregates 对空数据、中文、分页、权限全面验证。 |
| E-07 | [P,V] | 最终冻结清单中的主观题 AI 辅助评分 | 当前可定位 ADMIN 人工改分，仍需核对 AI-assisted grading suggestion 是否存在独立、只读、带来源的建议链；缺失则补建议接口及人工确认边界。AI 永不直接提交最终分数。 |

### B7. Mastery、StudyPlan

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| M-01 | [R,V] | R-MASTERY-001/002/003 | Practice+Wrong+Review+Exam 对同一 KP 的证据累计、算法解释、idempotency、乐观并发/upsert/重算一致性。 |
| M-02 | [R,V] | R-MASTERY-004 | calibration 版本/activation/recompute/system config 数值验证；真实数据重算前后溯源，跨用户重算 ADMIN 限制。 |
| M-03 | [R,V] | R-PLAN-001/002 | LEARN/PRACTICE/REVIEW/EXAM 四类真实候选与 bounded daily limit。 |
| M-04 | [R,V] | R-PLAN-003/004 | complete/skip/unskip/adjust/reorder/regenerate，用户决定优先于 AI；Mastery/Review/Exam 后任务优先级变化。 |

### B8. AI — Provider、问答、结构化、生成、Usage

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| A-01 | [R,V] | R-AI-001/002/003/004 | Tutor/grounding/BYOK/StepFun 已有；再次证明 Search 仅当前 user/space + published，references 与实际传给模型的上下文一致。 |
| A-02 | [F] | R-AI-005 | AI 内容结构化不能覆盖 published 原始 block（F-18），应写待审候选/新 revision。 |
| A-03 | [F] | R-AI-005/NFR-TRACE | AI Knowledge Extraction 的 owner/source/revision/provenance、结构化校验、原子 draft 写入（F-19）。 |
| A-04 | [F] | R-AI-005/NFR-TRACE | AI Question Generation 的 KP/QuestionSource、options/答案有效性、原子 draft 写入（F-20）。 |
| A-05 | [F] | R-PRACTICE-003 | Variant API 与结构化答案可用性（F-16/F-17）。 |
| A-06 | [F] | NFR-OBS-001 / R-AI-006 | GenerationJob 列表 space scope、worker 支持的 jobType、进度/计数、超时重试与长期 lease（F-21/F-22/F-29）。 |
| A-07 | [F] | R-AI-006 | Tutor/Coach/Explanation 之外的 generator/variant 等用途 success/failure/token/latency 记录（F-23）。 |
| A-08 | [V] | AI provider trust | 每用户 runtime config > env；密文/密钥失效/重启及 test-connection；真实 StepFun 对 structured generation 与 BYOK。 |
| A-09 | [R,V] | R-EXAM-010 / AI diagnosis | Exam deterministic structured diagnosis 先算，AI narrative 可选且只写建议，不重打分。 |

### B9. Admin、System Config、运维

| 编号 | 状态 | 对应 | 剩余动作 |
|---|---|---|---|
| G-01 | [R,V] | R-ADMIN-001 | Admin User/Space list/detail/update/roles、last-admin、用户密码安全、跨 owner 治理只经 ADMIN path。 |
| G-02 | [F] | R-ADMIN-002 | Admin Manual 正文处理 + Source/Job/Issue/Page/Outline/OCR/Block/Revision 治理（F-04/F-05/F-07）。 |
| G-03 | [R,V] | R-ADMIN-003/004 | Admin Knowledge/Question/Exam CRUD、审核、publish/archive、manual grading、Blueprint；不得绕过 domain Service 状态机。 |
| G-04 | [P,V] | R-ADMIN-005 | Admin AI/Ingestion job list/detail/retry、进度/失败语义；批量操作 bounded，不因直接 Mapper 绕过校验。 |
| G-05 | [R,V] | Feature-map System Config | Registry/typed whitelist、值域/重启要求、不可读取/修改 secret；ADMIN 访问、全局配置变更审计。 |
| G-06 | [F,V] | NFR-OBS-001 | Stable machine-readable errorCode、RequestId、闭集 metrics tags、AI/OCR job/queue metrics、readiness/liveness，实际负载场景验证。 |
| G-07 | [P,V] | Deployment | OCR CLI/sidecar、MySQL 8.4、Flyway、非 root、健康检查、volume、重启恢复、backup/restore、HTTPS/CORS、safe secret injection、graceful shutdown。 |
| G-08 | [R,V] | Operations | Storage reconciliation DRY_RUN/CLEANUP、symlink/Windows reparse、scoped ReferenceChecker、max-scan/min-age 与销毁隔离。 |
| G-09 | [P,V] | 原始 development-plan Phase 9/10 | 后端侧 backup/export basics、MySQL+storage 联合备份与恢复演练、Linux/Nginx/HTTPS 反代部署手册及真实最小 smoke；不要把一个可 build 的 Docker 镜像直接视为完整部署交付。 |

### B10. OpenAPI + shared api-client + DTO

| 编号 | 状态 | 剩余动作 |
|---|---|---|
| C-01 | [P,V] | 所有新增 REST API typed DTO/状态码/ProblemDetail，尤其 Entity 直接返回 vs DTO、nullable/optional、404/409、raw Range、AI Variant 201。 |
| C-02 | [P,V] | `packages/api-client/src/client.ts` 已有一批 wrapper；对当前所有 Desktop/Admin 路径做 `API route × wrapper` 对账，补真实缺口，禁止 direct fetch/复制 DTO。 |
| C-03 | [V] | 最终 live `/v3/api-docs` → 重新生成 `openapi.json`/`api.d.ts` → `npm run typecheck`；确认 FormData 不手动固定 Content-Type，Binary/Range 正常。 |
| C-04 | [V] | `client.ts` 生成类型、请求体、返回体对齐当前代码；客户端实际 smoke（不是只通过 TS 静态编译）。 |

---

## C. 本次仍必须完成的全部测试与验收门禁

**近期日志证据：** 存档 `full-test-7.log` 运行于 **2026-09-24 16:48**，最后结果 `Tests run: 660, Failures: 2, Errors: 0, Skipped: 2 / BUILD FAILURE`：

1. `ContentIngestionIntegrationTest.duplicateCreateOnSucceededAssetReturns409`：期望 409、实际 201；
2. `PdfImageIngestionIntegrationTest.imageRetryOnFailedThenSucceedDoesNotDuplicate`：期望立即 FAILED、实际 QUEUED。

这两个均涉及**同步旧测试与新异步任务契约冲突**。必须重新核对最新生产 API 正式契约：禁止仅为了让旧断言绿而把异步设计恢复成同步；也不能让重复成功 job 的幂等设计悄悄改变。ZIP 快照是 2026-10-13，且存在大量更改，**不能拿 9 月日志当成 10 月最新测试结果**。

当前 `server/src/test` 下主要仍是早期 vertical-slice 测试，约 **78 个 `*Test.java` 类**。针对新一轮最终功能尚缺足够独立验证的代表模块：FolderImport、Outline、Issue、Revision、VersionCompare、OCR、DOCX、Note/KP Relation、ExamBlueprint、AI Generation Job、AIUsage、AI Variant、SystemConfig、Admin bulk 等。现有旧 test 类名不能证明这些功能通过。

| Gate | 必须执行/证明 | 通过标准 |
|---|---|---|
| T-01 最新编译 | `test-compile`：当前所有已跟踪+未跟踪源码/测试包含在实际构建中 | BUILD SUCCESS；无 Maven/Java 21 签名/Bean/Mapper 问题 |
| T-02 Flyway | 空库 V001→V056；已有 V030→V056；二次迁移幂等；历史迁移 checksum；备份/恢复 | DB 迁移准确，既有数据保留，无版本冲突 |
| T-03 auth/profile | test/flyway-it/it/prod context；JWT/SPIKE/Actuator 链；mock mapper；CORS | context 全可启动，没有非预期 401/403/404 |
| T-04 Source + storage | upload, RAW, Range, duplicate, ZIP entry idempotency, Folder sync, archive/review/publish | 流程数据不丢，重复不多写，无路径泄漏 |
| T-05 Extraction | TXT/MD/DOCX, PDF text/scanned/mixed, PNG/JPEG/WebP, 中文 OCR/置信度/超时/限额 | page/block/issue/outline 持久且有 provenance |
| T-06 Worker | DB claim, afterCommit, crash/stale lease/recovery, retries, partial failure, queue bound | 无重复任务、无死队列、无交叉资产处理 |
| T-07 Revision | 多次 re-OCR、publishing、旧 revision 保留、version compare | 旧出处仍有效，compare 真实反映内容变化 |
| T-08 Knowledge/Note | Category/KP/Relation、Note CRUD + NoteSource、QuestionSource、跨 space invalid FK | 不越权，不丢引用 |
| T-09 Search | types=NOTE、混合 type、中文 FULLTEXT、短语/特殊字符、分页/count、published gate | SQL 不报错、无草稿/越权泄漏 |
| T-10 Question/Practice/Review | 全题型、grading、Practice 筛选、Wrong 转换、SM-2、Review→Mastery、AI Variant | 确定性成绩、可解释证据、状态正确 |
| T-11 Exam | Blueprint 真分布、Paper immutable、AutoSubmit/duplicate、manual grading、Diagnosis/Stats | 无题目串库、状态和分数一致 |
| T-12 Mastery/Plan | evidence、calibration、recompute 并发、四 task type、skip/adjust/regenerate | 相同数据可重算一致，用户决策保留 |
| T-13 AI/secret | Tutor/Coach/Explanation/Variant/Structure/KP/Question/Drafts、Usage、Job retry/lease、SSRF policy、BYOK | 无 secret/leakage、draft 不自动发布、全部 code 稳定 |
| T-14 Admin/Config | USER 403、ADMIN 操作、content review、bulk 限额、SystemConfig whitelist | 无跨空间数据损坏/权限提升 |
| T-15 Full clean | 当前完整工作树 `mvn clean test`，含两套 DB env 的规范隔离 | **0 Failures, 0 Errors**；已知 skip 需列明 |
| T-16 真实教材 E2E | 《数据库系统工程师教程》ZIP→RAW→Page→OCR→Outline→Review→Publish→KP→Q→Practice→Wrong→Review→Exam→Diagnosis→Mastery→Plan→AI | 每步成功且可追溯，第二空间无权访问 |
| T-17 OCR runtime | Windows + Linux/Docker 真实中文 JPG/WebP/扫描 PDF | 真 OCR 可运行，不是 mock 或无 reader 兼容分支 |
| T-18 StepFun | 环境变量调用 + 实际 per-user BYOK 加密 DB key/重启/删除；生成任务、错误/usage | 请求和计费记录正确且不泄露 key |
| T-19 Runtime | Spring prod/local boot, Actuator health/liveness/readiness, jobs recovery, Flyway, storage | 真实环境可持续运行 |
| T-20 Client | Live OpenAPI、api-client generate/typecheck、FormData/Range、Desktop/Admin 一套 contract | frontend 不需要手写 DTO/direct fetch |
| T-21 Docker/deployment | 多阶段构建、Paddle OCR 依赖/模型、non-root、MySQL、volume、backup/restore、重启 | 真容器 E2E PASS |
| T-22 Git/docs/secret | `git diff --check`、追踪 SQL/Java、检查 `.env`/log/smoke/temp、更新 decisions/plan/operations/docs | 所有最终改动提交，worktree clean，用户 push 到稳定 main |

**自动测试不允许触碰真实业务库**；必须使用专用 Flyway 隔离 schema 和锁。不要求默认全量性能压测，但对 OCR、Search、异步队列必须做至少一次真实有界资源与故障场景验收。

---

## D. 已有实现，避免重复开发的模块

代码存在下列模块，开发时应**补缺口而不是重建**：

- Spring Security JWT Access + refresh rotation、ADMIN RBAC、last-admin protection、bootstrap；
- LearningSpace lifecycle、SourceAsset RAW/stored metadata、LocalStorageService、安全 upload；
- IngestionJob + Executor + recovery runner、PDFBox 3 parser、图片 OCR 调用、DOCX parser、ZipArchiveInspector；
- SourcePage / ContentBlock / Outline / Revision / Issue / VersionCompare 基础类；
- Category/KP/KnowledgePointRelation / Note/NoteSource/NoteKP / QuestionSource；
- 7 种 Question 类型的已有校验/Practice/Wrong/Review（SM-2 state）；
- Exam/Paper/Attempt/Diagnosis/AutoSubmit/Blueprint/Stats；
- Mastery / Calibration / StudyPlan 四类 task 的基础实现；
- StepFun OpenAI-compatible、AI Settings/BYOK/Tutor/Coach/Explanation/AI job/usage、Provider abstraction；
- Admin User/Space/Knowledge/Question/Source/Exam/Bulk/SystemConfig Controllers；
- OpenAPI types、shared `client.ts`（当前已有 60+ 方法）、Docker/Actuator/metrics 基础。

**注意**：“代码存在”与“功能经过当前最终回归”是两件事。审计未运行 Maven、Docker、OCR 或真实 StepFun。

---

## E. 永久范围排除（不计入当前未完成）

与本项目最终冻结文档保持一致：Android-specific backend/摄像头专用流程、公共共享 LearningSpace、多租户/组织、社交/支付、实时协作、离线多副本冲突同步、云对象存储强制部署、chunk/resumable 上传、旧 `.doc`、完整音视频解析、Redis/MQ/Elasticsearch/Graph DB（除非经产品重新立项）、MasteryEvent event sourcing、Manual Mastery override、AI 自治发布正式内容、agent/多 agent、客户端绕开后端访问 DB 或 AI。不要将它们误计为“剩余产品后端任务”。

---

## F. 执行顺序、依赖与并行

**第 0 组——版本与契约核对（所有任务前置）**：核对真实工作区 HEAD/status、实际 SQL 和 package config，固定发布状态机与错误码、当前项目是否包含 10/13 ZIP 之后改动；不动已发布迁移。

**第 1 组——可并行的底层缺口**：

- 1A Source/Asset/Folder：F-04、F-12、F-13、F-14、F-15；
- 1B Search：F-01、F-02、F-03（对 Source/Revision publish 合同有依赖，先协调 SQL 条件）；
- 1C OCR/WebP/Docker：F-08、F-09、F-10、F-28；
- 1D AI/Variant/Usage：F-16、F-17、F-19、F-20、F-21、F-22、F-23、F-29；
- 1E ExamBlueprint：F-24；
- 1F ContentBlock/Source state：F-05、F-25、F-26、F-27、F-31（与 1B/1C 共用表时先协调）。

**第 2 组——依赖底层数据**：自动 Outline/Issue F-06/F-07，Revision/VersionCompare F-11，AI content structuring F-18，SourceReview/Publish 统一应用。

**第 3 组——跨模块闭环**：Question/Review/Exam/Mastery/StudyPlan、Admin 正式治理、统一 ErrorRegistry、全部 public DTO 和 shared client。

**第 4 组——集中验证**：T-01→T-15，只有前序全绿才跑 T-16~T-21。根因簇修复，不按单条测试磨；修复必须回归影响范围。

**第 5 组——交付冻结**：T-22、用户提交 Git、stable commit、clean worktree。

开发人员每个子任务完成后立即汇报：任务 ID / 实际源码和 SQL 依据 / 修改文件 / 当前状态 / 验证是否真的执行 / 新风险 / 下一步依赖；不允许把“未执行测试”写成 PASS。修改的类、方法、DTO 必须按项目 Javadoc 风格解释用途、参数/返回值约束、状态和异常；重要 DB/安全/部署决策在 docs 记录。执行者先读工作区最新源码，核对任务拆分后立即实施；仅对用户决策或不可逆风险请求确认。

---

## G. 完成宣告准则

**仅当 A/B 所有 `[F]` 修复、所有 `[P]` 闭环、所有 `[V]` 得到可复现证据，并且全部 T-01~T-22 实际 PASS、无非批准的功能残留、最终 stable commit+clean 状态确认以后**，才允许写：

```text
AIStudy FINAL BACKEND — FEATURE COMPLETE + VERIFIED + FROZEN
```

现阶段只能写：

```text
FINAL BACKEND IMPLEMENTATION PRESENT
CONFIRMED FUNCTIONAL GAPS REMAIN
FINAL CONSOLIDATED VERIFICATION NOT PASSED
```

审计性质：静态源码与已有证据交叉核对；**没有修改、编译、测试、部署当前用户项目**。本报告中包含源码可证的确定缺口，也包含仅待运行验证的条目，两者不可混淆。
