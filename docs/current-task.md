# Current Task

## Objective
Final Backend Feature Freeze — remaining backend gaps then freeze.

## Current Phase
**IN PROGRESS — Feature code (§5 Ingestion/OCR/Versioning now partially landed)**

## Latest round (§5)

### Schema V053
- `source.current_extraction_revision_id`
- `source_page/content_block/source_outline_node.extraction_revision_id`
- `ingestion_job.claimed_by/claimed_at/last_stage_status`
- `ingestion_issue.source_id/extraction_revision_id/safe_message`

### Ingestion job
- Atomic DB claim `QUEUED → IMPORTING` (single worker)
- Startup recovery runner requeues stale PROCESSING + drains QUEUED
- Stage-resume retry via `requeueForStageRetry` + `last_stage_status`
- No Thread.sleep progress faking
- Revision created BEFORE extract; pages/blocks stamped; source current revision updated
- Safe error message bounded

### OCR
- Image pipeline wired to `OcrEngine` (Paddle process adapter)
- PNG/JPEG/WebP magic; WebP without ImageIO reader still accepted via RIFF/WEBP magic
- OCR text → SourcePage + ContentBlock; low confidence flag
- PDF text-first + OCR fallback for sparse pages (PDFRenderer 150dpi)
- IngestionIssue producers: OCR_LOW_CONFIDENCE, EXTRACTION_PARTIAL_FAILURE

### Contract fixes
- RAW download: `GET .../assets/{assetId}/content` streams StorageService (no storageKey)
- Version compare rewritten to `selectByRevision` (not assetId-as-revisionId)
- SourceAsset allowlist webp/docx (prior round)

### Compile gate
`.\mvnw.cmd clean test-compile` — **BUILD SUCCESS** after this round.

## Latest round (§5–§12)

### Admin / SystemConfig
- `AdminSourceIngestionController`: list/detail sources, revisions, jobs, issues, retry, page reorder, block edit, RAW (ROLE_ADMIN)
- SystemConfig typed registry: server-owned type/range/restart; PUT value-only; no secret editor
- `GET /api/v1/admin/system-config/registry`

### api-client wrappers
- listConversationMessages / explainPractice / explainExam / studyCoach
- AI generation-jobs create/list/get/retry
- downloadSourceAssetRaw
- deleteFolderSyncSnapshot
- admin system-config registry/upsert
- admin sources list + job retry

### Compile gates
```
server mvn clean test-compile  → BUILD SUCCESS
api-client npm run typecheck    → PASS
```

## ARCH-HARDENING round (2026-09-20/21)

架构评审后落地的收口（决策见 ADR-047 ~ ADR-050）：

- **越权修复**：`/api/v1/spaces/{spaceId}/statistics/question-type-performance`
  与 `mastery-movement` 之前不带 principal / 忽略路径 spaceId，SQL 只按 space_id
  过滤；现按 `user_subject + space_id` 过滤并走 `getMine` gate。
- **隔离地板**：新增 `space/scope/SpaceScopeInterceptor`（+ `SpaceScopeWebConfig`），
  `/api/v1/spaces/**` 的 `{spaceId}` 在进入 handler 前统一校验，失败 404
  `SPACE_NOT_FOUND`。6 个单测覆盖 owner/越权/匿名/非空间路径/query 伪造。
- **错误契约**：新增 `common/problem`（`ApiException` / `ApiErrorCodes` /
  `ApiExceptionHandler`），§12 的 ProblemDetail + 稳定 code 首次真实存在；
  `IllegalStateException` 不再回显内部消息。4 个 MockMvc 单测。
- **边界棘轮**：新增 `architecture/ModuleBoundaryTest`（零新依赖，扫源码 import）：
  Controller→Mapper 名单（9，只准减少）、Controller 用 `JdbcTemplate`（0，硬性）、
  跨模块 Mapper import ≤85、模块环基线 = 1 个 12 模块大团。
- **摄取长任务**：专用有界 `ingestionWorkerExecutor` + `aistudy.ingestion.worker.*`
  配置；`afterCommit` 才入队；`IngestionRevisionPublisher` 把"revision 可见"四步
  收进一个事务；终态与 error 诊断同条 UPDATE（`markTerminalFailure`）；租约心跳
  `refreshClaim`；回收从"仅启动"改为周期 tick（test/flyway-it 关闭）；DOCX/TXT
  重抽先删未定稿页/块；删除 `resetForRetry` / `countActiveOrSucceeded` 死词表 SQL；
  修正 `IngestionJob` 实体里过期的 PENDING/RUNNING/SUCCEEDED 生命周期注释。
- **admin 裸 SQL 下沉**：`AdminSourceIngestionController` 的 12 处 `JdbcTemplate`
  （含 `UPDATE ingestion_job` / `UPDATE content_block`）移入
  `admin/source/service/AdminSourceIngestionService` + 各属主模块新增 mapper 方法；
  admin retry 现在真正重新派发（原来只把状态改回 QUEUED，要等重启才跑）。
- **测试基础设施**：`test` profile 之前**整套全上下文测试都起不来**（`provenance`
  的 `SourceReferenceService` 构造注入 `JdbcTemplate`，而该 profile 排除了
  DataSource；`AiStudyApplicationTests` 的手写 mock 清单也漏了 17 个新 mapper）。
  改为 `testsupport/MapperMockRegistrar` + `TestProfileMapperMockAutoConfiguration`
  （仅 `test` profile）自动按 `@Mapper` 注解供给 mock。DB-free 子集：
  121 error → 131 pass / 4 fail。
- **文档**：architecture.md §2/§4/§5.2.1/§6.2/§10/§16.1、api-guidelines.md §12.1、
  operations.md（stale job 一节改写）、runtime-configuration.md（worker 旋钮）、
  decisions.md（ADR-047~050）。

### 本轮遗留（需后续处理）

1. 4 个 OpenAPI 契约测试失败，属**未提交批次自带的漂移**：
   `SourcePageController` 直接返回实体 `SourcePage`（违反 api-guidelines §14
   "Entity 不直接作为 API DTO"，测试期望 `SourcePageResponse`）；
   `CreateExamRequest.durationMinutes`、`MasteryResponse.practiceEvidenceCount`
   字段从 schema 上消失。
2. `V031`~`V056` 共 25 个 migration 与 `admin/`、AI job 等 101 个源文件**仍未纳入
   git**（另 110 个已修改未提交）：ADR-025/046 的"版本化 SQL 为唯一事实"当前不成立，
   干净克隆建不出可用库，Flyway checksum 因机器而异。
3. 33 个 `flyway-it` 集成测试需要真实 MySQL，本机 Docker 未运行 → **未验证**。
   拦截器与 IAE→400 的状态码变化只在该子集里才可能被观察到。
4. ZIP 重抽会重复插入 `source_asset` 行（缺唯一键 + upsert），未修。
5. `spike/` 仍在生产上下文并持有 `/health`、`/v3/api-docs` 的 `@Order(1)` 安全链，
   空间鉴权组件 `SpikeSpaceAccess` 依赖 spike 表；收紧 `/v3/api-docs` 会连带
   `api:generate`（需本地起服务匿名取 spec），属于需要产品/工作流决策的改动，未动。
6. Mastery 事务内聚合 + SELECT-then-INSERT 无 upsert/乐观锁；DERIVED 生产侧不写
   provenance 关联；生命周期状态无统一策略；Search 12 处重复谓词与 ngram 索引未使用。

## Round 2026-10-13/14 — gap audit + frontend contract work (backend)

Input: `docs/AIStudy_2026-10-13_Final_Backend_Gap_Audit.md` + `desktop/BACKEND-CONTRACT-REQUESTS.md` (C01–C12).
Work was built in parallel lanes (search / source lifecycle / asset-folder-RAW / OCR / AI jobs) and integrated into `main`.

### Done AND executed against real MySQL (`aistudy_flyway_test`)
- **F-01/F-02/F-03 + C-Search**: valid UNION assembly (every branch suffixes the separator), publish visibility gate
  (`source.status='PUBLISHED'` + current revision, NULL pointer = legacy escape; KP/Question `status='PUBLISHED'`),
  ngram FULLTEXT wired in. `SearchResult.type` now includes `NOTE` and carries `noteId`.
  Root cause of the Chinese-search bug: InnoDB natural-language relevance measured **1.9e-8** on a small corpus, so the
  `ROUND(relevance * 5)` boost was always 0 and `score > 0` discarded the only kind of hit Chinese search can produce.
  Fixed by giving a FULLTEXT-only match its own lowest tier per branch and using relevance only as `ft_relevance`
  ordering key (scale-free). 34/34 search ITs + 20/20 SQL guards.
- **Ingestion async contract**: `create()` had no duplicate guard after the dead SUCCEEDED vocabulary was deleted.
  A job for an asset that is still in flight now returns 409 `INGESTION_ALREADY_RUNNING`; re-ingesting a terminal
  asset stays legal and produces a NEW revision (old revision rows remain addressable). The two tests named in audit §C
  now await the worker instead of expecting a terminal status on the create response. 38/38 ingestion ITs.
- **C06**: `GET .../practice-sessions/{sessionId}/result` backed by a stored summary (V066),
  `PRACTICE_SESSION_NOT_SUBMITTED` instead of a fabricated zero. 26/26 practice ITs.
- **Flyway**: V001→V066 applies on a clean schema; the C02/C03/C04 branch's migration collided on V058 and was
  renumbered V065. 5/5 migration ITs.
- Merged and compiling/verified: F-04/F-05/F-25/F-26 (source review state machine, admin manual body, block write
  guards), F-12/F-13/F-14/F-15 (ZIP idempotency, duplicate policy, folder sync chain, RAW Range/206/416),
  F-08/F-09/F-28/F-31/F-10 (WebP sizing, OCR process fences, OCR sidecar + Docker, page-order invariant),
  F-21/F-22/F-29 (AI job space scope, honest counts/type allow-list, claim-token lease),
  F-16/F-17 (typed 201 for saved variant + inherited-answer gate), C01/C02/C03/C04/C05.
- **Contract surface regenerated from the live service**: `packages/api-client` 67 → **195 paths**, `npm run typecheck`
  passes. Handed to the frontend session with the answers it asked for (answers-list `explanation` is hardcoded null —
  a defect, tracked; message paging is `created_at ASC` so page 0 is the OLDEST message).

### Known-red, NOT caused by this round
- `ModuleBoundaryTest.crossModuleMapperImportsMustNotGrow`: measured **91** vs cap **85**. At `f685ef8` (before any
  merge in this round) it already measured **90**, so the ratchet was broken by earlier commits, not here. The guard now
  prints its offender list. Attribution of the 6 over cap:
  - **5** from the Notes feature (`note.service.NoteService` → `knowledge.point`, `question`, `source`, `source.content`,
    `source.page` mappers) — commit `e829e76`.
  - **1** from this round's merge (`admin.source.service.SourceExtractionGatewayService` / ingestion gateway seam).
  - This round **removed** the ones its own new code would have added: `ai.knowledge` → `knowledge.source.mapper`,
    `ai.question` → `knowledge.point`/`question` mappers (now via `KnowledgePointSourceService.linkDerivedSource` and
    `QuestionService.createDerived`), and `ingestion.job` → `source.asset.mapper` (now via `SourceAssetService`).
  Fixing the remaining 6 means routing those reads/writes through the owning services. Do NOT raise the cap to 91.

### Verified state of this round's second half (real MySQL, `aistudy_flyway_test`)
- Full `mvn -o test`: **1000 tests, 0 errors, 1 failure** (only the boundary item above), 2 skipped.
- Frontend contracts now live in the regenerated `packages/api-client` (**197 paths**, `npm run typecheck` green):
  **C-Paging** (`page`/`size` + `QuestionPageResponse`, SQL-side paging), **C06**
  (`GET .../practice-sessions/{id}/result`, stored summary in V066, `PRACTICE_SESSION_NOT_SUBMITTED`),
  **C10** (`ExamAnswerView.answer`/`submitted`, `AttemptQuestionView.answer`/`answered`),
  **C11** (`GET /spaces/{spaceId}/exam-attempts` paged, `score` null until a result exists),
  **C12** (14 exam conflict sites now throw `ApiException` with stable codes instead of writing the code inside English).
- **F-19/F-20/F-23**: the batch generators validate the whole provider payload before any write, inside one
  transaction, write provenance through the owning module, and book every provider call (success and failure) in
  `ai_usage_record`; `AiGenerationProvenanceTest` (7 tests) pins it.
- Also fixed on the way: `AiGenerationJobWorker.java` was on disk as `AiGenerationJobWorker` with class
  `AIGenerationJobWorker`, which broke incremental compilation.

### Environment recipe that cost two false reds this round
- `DB_URL` and `FLYWAY_DB_URL` point at **different schemas for different purposes**: the `it`-profile spike tests need
  `aistudy_spike` (its `spike_record` table is created by `deploy/local/mysql/init`, not by Flyway), while every
  `flyway-it` test and the locally running app need `aistudy_flyway_test`. Pointing `DB_URL` at the Flyway schema makes
  `SpikeRecordMapperIntegrationTest` fail with `Table ... spike_record does not exist`; pointing it at the spike schema
  for a *run* makes Flyway refuse to start (`no schema history table`).
- Never source `AISTUDY_BOOTSTRAP_ADMIN_ENABLED` into a test run: `BootstrapAdminRunner` is a `@Component` gated on it,
  and with the `test` profile's mocked mappers it fails the context ("Bootstrap admin account was created but could not
  be loaded"), which shows up as ~129 "ApplicationContext failure threshold exceeded" errors, not as one real bug.
- Do not run the suite while the `:8080` dev server is up. MySQL caps connections at 151 by default and the container
  inherits it; the running app's Hikari pool plus one cached Spring context per IT class per connection blows past it
  and the failures come back as `Too many connections` / `Unable to obtain connection` on unrelated classes
  (`FlywayMigrationIntegrationTest`, `SpikeRecordMapperIntegrationTest`). Stop the listener first, then restart it.

### Not done yet
- F-30: closed. The last 8 classes (study plan, AI conversation/explanation/job retry, system config, exam
  diagnosis, knowledge-point relation cycle, mastery calibration, outline delete) now carry per-condition codes, and
  `ErrorCodeRatchetTest` runs on an **empty baseline** — it rejects both an untyped 409 and any
  `ApiException(..., ApiErrorCodes.CONFLICT, ...)`, so the generic answer cannot return. Codes are published in the
  OpenAPI document and asserted by `conflictCodesArePublishedInTheContract`.
- MATCHING pairs are still a JSON string on the wire (validated at authoring, parsed by the same parser grading
  uses); a typed pair structure was offered to the frontend and not yet requested.
- Gates not executed: T-16 real textbook E2E, T-17 OCR on Windows+Docker, T-18 real StepFun/BYOK, T-21 container E2E,
  T-22 (tracked worktree is clean, nothing is pushed).
- `login.json` / `spec-live.json` sit untracked in the repo root from someone's probe — not created by this round,
  left in place; check them before any commit that includes the repo root.

## Round 2026-10-14 (later) — F-24, F-11, F-06 + the frontend's second ask list
Full suite on real MySQL: **1046 tests, 0 failures, 0 errors, 2 skipped**. Backend restarted on `:8080` at this code;
`packages/api-client` regenerated from the running server (198 paths) and its typecheck passes.
- F-24 blueprint distributions are enforced quotas (scarcest-first cell fill, exact total, 409
  `EXAM_BLUEPRINT_UNSATISFIED`, 400 for contradictory quotas at create, `scoreByDifficulty` honoured, distribution
  sizes the paper when `totalQuestionCount` is omitted) + 8 DB-free unit tests + 6 real-MySQL ITs.
- F-11 version compare keys pages on page number, blocks on page+position, outline on title path; `updatedContentBlocks`
  is now filled (bounded to 200) and outline counts are real. New IT `SourceVersionCompareIntegrationTest`.
- F-06 `ORDER_LOW_CONFIDENCE` / `OUTLINE_LOW_CONFIDENCE` producers wired into the pipeline behind
  `aistudy.ingestion.review.*`; new IT `IngestionIssueProducerIntegrationTest`, which also pins the fix for
  `IngestionIssueController` parsing the JWT subject as `resolved_by` (every real login 500'd on resolve/ignore).
- Frontend asks delivered: C05 DRAFT-only edit gate as `QUESTION_NOT_EDITABLE` + operation description, C09b
  `correctOrder`/`matches` read-back, C-Ordering-space 1-based permutation checks, C12 conflict codes published in
  the document (asserted), C-Paging ordering documented, C06 `explanation` fix, Notes archive tab + restore +
  entity filters + 404 link ids, `questionType`/`status` published as schema enums.
- Sent to the frontend session: the delivered list, the two behaviour breaks (PUBLISHED questions no longer editable;
  ORDERING now requires `options` + a full permutation) and three open questions (their ORDERING payload, whether
  they need `/knowledge-points/{id}/notes` as a subresource, when the verification window ends).





## Round 2026-10-14 (third) — C11 grading verdict, F-07, and the grading chain that never worked
Full suite on real MySQL again: **1056 tests / 0 failures / 0 errors / 2 skipped**; `:8080` restarted on this code and
`packages/api-client` regenerated (`gradingStatus` on `AttemptSummaryView` as `"GRADED" | "PARTIALLY_GRADED" | "UNGRADED"`).
- **C11 follow-up the frontend asked for**: they had asked whether an ungraded attempt's `score` is null or 0 (it is
  null), and proposed putting the attempt's own `gradingStatus` on the list row instead of inferring from "a number
  arrived". Done — score semantics unchanged, the row now labels itself.
- Getting that field asserted end-to-end (简述题 → submit → PARTIALLY_GRADED/score 0 → ADMIN grade → GRADED/score 4)
  exposed three broken paths in manual grading, all fixed: submit only wrote `grading_status` for PARTIALLY_GRADED and
  inside `catch (Exception ignore)`; `gradeAnswer` passed `(slotId, spaceId)` to a mapper declared
  `(spaceId, slotId)` so **every** grade answered 404; and re-diagnosing after a regrade always inserted into
  `exam_diagnosis`, whose unique key per attempt turned the second run into a 500, so an attempt could never leave
  PARTIALLY_GRADED. `ExamDiagnosisService.generate` is now idempotent (update summary, rebuild items so a stale WRONG
  row cannot survive). Re-grade artifacts are attributed to the examinee, not the acting ADMIN subject.
- **F-07**: AI structuring now proposes the outline tree. `SourceOutlineNodeService.replaceProposedTree` derives
  CHAPTER/SECTION/SUBSECTION from heading levels (clamped 1..3), chains parents to the nearest shallower heading,
  parses leading number labels, caps one tree at 300 nodes, stamps the extraction revision, and deletes only
  `status='NEEDS_REVIEW'` rows of that revision — so re-running a suggestion is safe and a human-confirmed node
  survives. The proposal comes from the document's headings, never from knowledge categories. Covered by
  `SourceOutlineProposalIntegrationTest` (6) and three new cases in `AiContentStructureIsolationTest`.

## Round 2026-10-14 (fourth) — F-30 finished, codes published
Converted the remaining ~30 untyped 409 sites, emptied the `ErrorCodeRatchetTest` baseline, and extended
`conflictCodesArePublishedInTheContract` to the whole vocabulary (40 codes). Full suite: **1056 / 0 / 0 / 2**;
`:8080` restarted on a5a7d97, api-client regenerated (descriptions only, no type breakage) and typechecked.
Two AI tests that asserted the old exception type now assert `ApiException` plus the code. Frontend informed and the
verification window re-opened (the shared schema was wiped by the final suite run, so data must be recreated).

## Round 2026-10-14 (fifth) — E-04 ADMIN grading chain, E-07 AI grading suggestion
Full suite on real MySQL: **1073 tests / 0 failures / 0 errors / 2 skipped**; `:8081` restarted on this code and
`packages/api-client` regenerated.

Audit E-04 said "ADMIN 人工改分、regrade、审计、更新总成绩/Diagnosis/Mastery 的幂等闭环". Reading the actual code
showed the chain was not merely unverified but unreachable, and fixing it exposed a scoring bug:

- **An ADMIN could not grade any learner.** Manual grading resolved the attempt with `user_subject = <caller>`, so the
  one role allowed to grade (`hasRole('ADMIN')` on `PUT .../answers/{answerId}/grade`) got 404 for every attempt that
  was not its own. Added `ExamAttemptMapper.selectByIdAndSpace` and `gradeAnswerAsAdmin`, which resolves the attempt by
  space and pins it to the exam when the caller came through `/admin/exams/{examId}/...` (an attempt of another exam in
  the same space is still 404). `applyGrade` derives the space from the resolved attempt, never from the URL.
- **`POST /admin/exams/{examId}/attempts/{attemptId}/grade` fabricated a verdict.** It wrote `grading_status='GRADED'`
  with no score, no result recompute, and ignored `examId` ownership, then returned a bare `String`. It now
  `finalizeGradingAsAdmin`: recomputes from the answer rows and **refuses with 409 `EXAM_GRADING_INCOMPLETE` while a
  stored subjective answer still awaits a score**, returning a typed `AdminGradingView` with the real score.
- **A grade did not make the answer correct.** `gradeUpdate` wrote `score` but not `is_correct`, so a full-marks essay
  kept counting as wrong in `exam_result.correct_count/wrong_count` — and in the diagnosis the same recompute writes.
  The grade now writes `is_correct = score >= slot.score`.
- **The post-grading recompute lost marks.** `recomputeResultAfterGrade` summed the stored answer scores, but an
  objective answer's stored score is the evaluator's `1/0` marker, not the paper's points (submit uses the slot score).
  A paper worth 6 came back as 5 after any manual grade. Objective slots now contribute the slot score when correct,
  subjective slots the human score. Caught by the new IT, not by inspection.
- **Manual grading is restricted to subjective slots** (`409 EXAM_ANSWER_NOT_SUBJECTIVE`) and now **accepts a
  regrade** (`GRADED` → new score), with `previous_score/previous_feedback` keeping the first decision auditable and
  `graded_by` the human. Out-of-range scores answer `400 EXAM_GRADE_SCORE_OUT_OF_RANGE`.
- New ADMIN read: `GET /admin/exams/{examId}/attempts/{attemptId}/answers`, the grading queue. `ExamAnswerGradingView`
  gained `questionType`/`stem`/`maxScore`/`answerText`/`isCorrect` — a grader previously had neither the text nor the
  bound. All six endpoints are typed and their codes are published in the OpenAPI document
  (`conflictCodesArePublishedInTheContract` now covers 7 new codes).
- **E-07**: `ai/grading/AiGradingSuggestionService` + `POST .../answers/{answerId}/grading-suggestion`. Read-only by
  construction — it holds no write path, `finalScore` is always false, an out-of-range model score is clamped and
  flagged `clamped: true`, an unparseable response is a 409 (never a silent zero), the provider failure is a 502
  `AI_PROVIDER_UNAVAILABLE`, and every attempt is booked in `ai_usage_record` under the new purpose
  `GRADING_SUGGESTION`. The score only exists when an ADMIN posts it.
- `ExamVerticalSliceIntegrationTest` and the new `ExamAdminGradingIntegrationTest` (13 cases) cover this on real MySQL:
  cross-user grading, finalize refusal, full-marks correctness, regrade audit, objective rejection, bounds, suggestion
  advisory-only/clamping/unusable-response/objective refusal, and the learner 403.
- **Test-harness root cause fixed**: every distinct IT context caches its own ApplicationContext with a Hikari pool of
  10, so the suite exceeded MySQL's `max_connections=151` and failed unrelated classes with "Too many connections".
  `application-flyway-it.yml` now sets `maximum-pool-size: 3`; that is what took the run from 7 errors to 0. This was
  the environment trap noted in earlier rounds, and it is now a code fix rather than a procedure.

### Not done yet
- Gates that need external conditions: T-16 真实教材 E2E (needs the 数据库系统工程师教程 ZIP), T-17 OCR on Windows +
  Docker (needs the Paddle runtime + real scanned pages), T-18 real StepFun/BYOK (needs a live key), T-21 container E2E.
  T-22 push is the user's action.
- Frontend: C09 typed ORDERING/MATCHING pair structures and `questionType`/`status` enums on their side; the new ADMIN
  grading surface has no client wrapper yet (they own `client.ts` reconciliation, C-02).
- `login.json` / `spec-live.json` still sit untracked in the repo root from someone's probe — not this round's files,
  left in place; check them before any commit that includes the repo root.

## Round 2026-10-14 (sixth) — B1: the exam ORDERING answer was being thrown away
Full suite on real MySQL: **1077 tests / 0 failures / 0 errors / 2 skipped**. Reported by the frontend from a live
attempt, reproduced in code, and confirmed by reverse-running the new tests.

`ExamAttemptService.answer` built the stored payload with the **three-field** `buildAnswerPayloadJson` overload, which
has no `orderingAnswer`/`matchingAnswer` slots. So on the exam path an ORDERING answer was:
- accepted with **200** while the response echoed `answer.orderingAnswer = null` (and every later read too, with
  `answered: true`) — the server claimed an answer it had thrown away;
- **fatal at submit**: the evaluator threw `IllegalArgumentException("ORDERING requires orderingAnswer")`, the global
  handler rendered it as `400 VALIDATION_ERROR / 请求包含无法处理的参数`, and the attempt stayed IN_PROGRESS with no way
  to find out which question was at fault. The practice path was unaffected because it uses the five-field builder.

Fixes:
- The exam answer write keeps all five payload fields (`orderingAnswer`, `matchingAnswer` included).
- A grading failure at submit now answers **400 `code: EXAM_ANSWER_PAYLOAD_INVALID`** with a detail that identifies the
  row unambiguously (`examQuestionId=…, sortOrder=…, type=ORDERING cannot be graded from the stored answer: …`) —
  `sortOrder` is exposed as stored, so the message names the id too instead of guessing the client's numbering base.
  The attempt still stays IN_PROGRESS, which is now actionable: re-post that one answer.
- `ExamResultView` carries **`gradingStatus` + `pendingGradeCount`** (frontend ask: a partially graded paper previously
  showed only `score`/`maxScore`, so a provisional number read as the verdict). The status is read back after the lazy
  auto-submit guard, not from the attempt loaded before it.
- `ExamAnswerView.submitted` is documented in the contract: it labels the **attempt's** state (stored answer is
  read-only or still editable), not whether the answer was saved.
- E-04 Q2: the ADMIN grading queue view gained `referenceAnswer`, `explanation`, `previousScore`, `previousFeedback`
  — a human grading without the rubric was "blind correction" with extra steps. These are on the ROLE_ADMIN surface
  only; the learner's result view is built elsewhere and keeps its own reveal rules.

Guards: `ExamOrderingAnswerIntegrationTest` (4 cases on real MySQL) covers the round trip (post → read back → submit →
graded), the wrong-permutation zero, the locatable code for a row the old build already wrote, and the mixed paper's
PARTIALLY_GRADED → ADMIN grade → GRADED transition. **Reverse-verified**: with the one-line fix reverted, 3 of the 4
fail with exactly the reported symptoms (null echo, submit 400). `ExamOpenApiContractTest` now publishes
`EXAM_ANSWER_PAYLOAD_INVALID`.

Decisions taken with the frontend: no pagination on the grading queue (paper composition is capped at 100 slots), and
no batch AI suggestion endpoint — a batch would multiply provider calls per attempt and the cost/bound design is not
worth it while grading is a per-row human act.

## 联调窗口（开放中，2026-10-15）
前端正在 :8081 上跑 live 验收（`scripts/live-journey.mjs` 33 条 + B7/B8 + 重判链 + 打包版 R03–R05）。
**窗口期间：不跑 IT、不重启后端。** IT 会清 `aistudy_flyway_test`（含 dev 账号与 space 29），重启会断他们的会话。
当前稳定提交以 **tag `backend-2026-10-14-e04e07-b1`** 为准（B1 修复与 E-04/E-07 都在它里面），full suite 1077/0/0/2。
写具体 SHA 会随历史改写失效，所以这里只留 tag；本分支与 `main` 都已在 2026-10-16 用
`filter-branch --index-filter 'git rm --cached --ignore-unmatch login.json spec-live.json'` 重写过：
`917a14f`（把真实 accessToken + refreshToken 提交进仓库的那次 `[Checkout-checkpoint]`）已不在任何分支历史里，
重写只删了这两个文件，提交数与其余内容逐字节不变。`main` 重写后为 `2252314`，并且已是本分支的祖先。
注意：改写历史并不能让那对 token 变安全——它之所以已经失效，是因为签发它的 `user_account` 与
`auth_refresh_session` 行随 `aistudy_flyway_test` 被 IT 清空而不复存在（已按 sha256 逐个 schema 核验为零）。
任何 `pre-cleanup-*.bundle` 备份里仍含明文 token，按机密对待。
联调账号：`dev` / `devpassword123`（ADMIN+USER），ACTIVE 空间 id=29（`integration-space`）。
前端已确认：本轮不再需要改分队列分页、不要 AI 建议 batch（逐题按钮即可）；需要 referenceAnswer/explanation 留在 ADMIN 面（已交付）。

## Git
User owns all git writes.
