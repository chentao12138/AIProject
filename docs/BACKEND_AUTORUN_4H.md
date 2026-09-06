# AIProject Backend Autonomous Runbook — 4–5 Hour Unattended Window

## 0. Purpose

This file is the authoritative execution plan for the next unattended backend work window. The operator may be away for 4–5 hours.

Do **not** rely on chat history. Repository state + persistent docs are the source of truth.

Known completed baseline (verify against actual Git before work):

- BUSINESS-001 LearningSpace — COMPLETE
- BUSINESS-002 Source metadata — COMPLETE
- BUSINESS-003 Knowledge Catalog — COMPLETE
- BUSINESS-004 RAW SourceAsset Upload — expected COMPLETE after Git closeout
- Shared OpenAPI TypeScript client exists
- Frontend is developed separately on another branch; backend work must not touch frontend code

Next backend dependency chain:

1. BUSINESS-005 — IngestionJob + ZIP Safety Foundation
2. BUSINESS-006 — Content Ingestion Foundation (prioritize TXT/Markdown)
3. BUSINESS-007 — KnowledgePoint Provenance
4. Later — Question → Practice → Wrong Question/Review → Exam → Mastery/Diagnosis → StudyPlan
5. Platform follow-up — formal Auth/User/Refresh/Admin, AI provider integration, operations/hardening

This unattended window should **not** attempt to finish the entire backend. The safe target is the next cohesive ingestion/provenance block.

---

# 1. Context-Size / Compression Policy

A previous Hermes session reached ~626k tokens and `/compact` failed because the summary itself hit the provider output cap.

Therefore:

## 1.1 Start this run in a NEW Hermes session

Do not continue the huge existing conversation. The new session starts by reading this file and repository docs only.

## 1.2 Chat memory is disposable

Persistent state must live in:

- `docs/current-task.md`
- `docs/development-log.md`
- `docs/development-plan.md`
- this runbook

Never depend on “I remember from earlier chat”.

## 1.3 Keep `current-task.md` bounded

`docs/current-task.md` is a rolling recovery capsule, not an append-only history file. Keep it concise, ideally <= ~250 lines.

It must contain only current authoritative state:

- Objective
- Current Phase
- Completed
- In Progress
- Key Design Decisions
- DB Contract
- API Contract
- Security / Space Isolation
- Files Added / Modified
- Tests Added / Modified
- Runtime Evidence
- Static Evidence
- Deferred
- Risks
- Next Actions
- Resume Instructions

Replace stale information instead of endlessly appending.

## 1.4 Never reread the whole development log

At each phase start:

- read `current-task.md` fully
- read only the last 120–200 lines of `development-log.md`
- read only relevant production/test files
- do not dump whole large files into the conversation unless necessary

Use targeted grep/find/head/tail.

## 1.5 Minimize conversational output

Do not print whole Java files, huge diffs, Maven logs, generated OpenAPI files, or the full development log. Prefer concise status summaries.

## 1.6 Proactive checkpointing

After every substantial phase:

1. update `current-task.md`
2. append one concise checkpoint to `development-log.md`
3. run `git status --short`
4. run `git diff --check`
5. reread `current-task.md`
6. continue

## 1.7 If context pressure becomes severe

Do **not** keep expanding the same conversation until another 600k-token emergency.

Immediately:

1. write a complete recovery checkpoint to `current-task.md`
2. append a concise handoff to `development-log.md`
3. stop at a phase boundary

If the environment supports opening a fresh Hermes session autonomously, resume from repository docs in that fresh session. If not, stop safely and wait for the operator.

Do not risk task-state loss merely to “keep going”.

---

# 2. Git / Runtime Authority Rules

## 2.1 Git

Allowed:

- `git status`
- `git diff`
- `git log`
- `git grep`

Forbidden:

- `git add`
- `git commit`
- `git push`
- `git reset`
- `git restore`
- force operations

The operator owns Git writes.

## 2.2 Runtime verification

Do not claim Maven/runtime PASS without user-provided runtime evidence.

New slices must remain:

`IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION`

until the operator provides runtime results.

Do not fabricate test counts.

---

# 3. Phase 0 — Recover the Real Baseline

Execute:

```text
git status --short
git status -sb
git log -5 --oneline
```

Then read:

- `docs/current-task.md`
- tail of `docs/development-log.md`
- `docs/development-plan.md`
- `docs/data-model.md`
- `docs/content-ingestion.md`
- `docs/api-guidelines.md`
- `docs/architecture.md`
- `docs/decisions.md`
- relevant Source / SourceAsset / Storage code

Verify BUSINESS-004 Git state.

If there are unknown user changes, STOP.

If the working tree contains only known BUSINESS-004 closeout changes, record them and continue only if `current-task.md` says they are expected.

Update `current-task.md` to the actual baseline before coding.

---

# 4. Unattended Window Scope

Preferred maximum for this 4–5 hour run:

## Target A — BUSINESS-005
IngestionJob + ZIP Safety Foundation

## Target B — BUSINESS-006
Content ingestion foundation for safe deterministic V1 inputs, prioritizing TXT/Markdown

## Target C — BUSINESS-007
KnowledgePoint provenance foundation, only if A and B are statically complete and the required model already exists

Do **not** proceed to Question/Practice/Exam during this unattended window.

Reason: Ingestion → ContentBlock → provenance is one cohesive dependency chain. Question/Practice/Exam belongs to the next business block and should begin after operator runtime verification of this ingestion block.

---

# 5. BUSINESS-005 — IngestionJob + ZIP Safety Foundation

## 5.1 Recover authoritative definitions first

Read repository docs for:

- IngestionJob
- SourceAsset
- status/stage/progress
- ZIP import safety
- limits
- failure semantics
- Source status transitions
- async/background expectations

Do not invent a duplicate model if docs already define it.

If docs materially conflict, record the conflict and STOP.

## 5.2 Persistence

Create the next migration number based on the actual repository.

Expected concept: `ingestion_job`.

Fields must come from docs, but generally need enough information for:

- id
- space_id
- source_id
- asset_id
- status
- stage
- progress if defined
- error code/message if defined
- created_at
- started_at
- finished_at
- updated_at

All references must remain space-safe.

No CASCADE unless an accepted ADR explicitly requires it.

Indexes should support source job history, active/pending lookup, and asset lookup.

## 5.3 Lifecycle

Keep V1 lifecycle minimal and explicit.

Example only if docs agree:

`PENDING -> RUNNING -> SUCCEEDED / FAILED`

Do not add a workflow engine.

Do not add Redis, MQ, Kafka, RabbitMQ, Quartz, Elasticsearch, Graph DB, or distributed scheduling unless accepted docs require them.

## 5.4 ZIP safety

ZIP safety must be reusable and must not trust entry names.

At minimum enforce docs-defined limits for:

- zip-slip/traversal (`../`, absolute/rooted names)
- entry count
- per-entry uncompressed size where defined
- total uncompressed size
- suspicious compression ratio where defined
- unsupported/encrypted entries where applicable

Do not extract directly into arbitrary paths.

Prefer validation/inspection first.

If extraction is not required by BUSINESS-005 docs, do not implement extraction yet.

## 5.5 API

Implement only the smallest stable API required by docs, typically:

- create/start ingestion for a Source/Asset
- get ingestion job
- list jobs for source if required

All endpoints must be owner/space scoped.

Unauthorized/nonexistent resources follow the existing 404 anti-probing behavior.

No `Map` responses. OpenAPI must be typed.

## 5.6 Tests

Add:

- real MySQL/Flyway integration coverage
- authorization/IDOR coverage
- job lifecycle coverage
- ZIP safety pure-unit tests
- OpenAPI contract tests
- Flyway migration tests
- old-test cleanup compatibility where new FKs require it
- old Spring context mapper compatibility where necessary

Do not use H2.

Do not use `FOREIGN_KEY_CHECKS=0`.

## 5.7 Checkpoint

When statically complete:

`BUSINESS-005 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION`

Do not mark COMPLETE.

Update docs and reread `current-task.md`.

---

# 6. BUSINESS-006 — Content Ingestion Foundation

Proceed only if BUSINESS-005 is statically coherent and no unresolved blocker exists.

The goal is not “support every document format”.

Safe V1 target: deterministic ingestion for TXT and Markdown.

PDF/OCR/image understanding may remain deferred.

## 6.1 Recover exact data model

Read docs for:

- SourcePage
- SourceOutlineNode
- ContentBlock
- sequence/order
- source/asset linkage
- provenance offsets/block indexes
- status semantics

Do not create tables not supported by the project model.

## 6.2 Persistence

Create migrations only for entities actually required by docs.

Every content entity must carry or derive `spaceId`.

Where ordinary FKs cannot prove same-space invariants, enforce them with owner/space-scoped SQL/service validation.

## 6.3 TXT / Markdown ingestion

Implement deterministic V1 parsing with no AI dependency.

Goals:

- load RAW bytes through `StorageService`
- decode using documented/default encoding policy
- preserve source provenance
- produce stable ordered blocks
- retain enough metadata for later KnowledgePoint provenance

Do not add AI extraction.

Do not silently normalize away source identity.

Do not implement PDF OCR unless an accepted parser dependency already exists in docs.

## 6.4 Ingestion job integration

If BUSINESS-005 introduced a lifecycle:

- job starts
- parser runs
- content persists
- job succeeds or fails
- failure records useful diagnostics

Keep transaction/filesystem semantics honest.

## 6.5 Tests

At minimum:

- TXT ingestion content roundtrip
- Markdown deterministic block ordering
- real MySQL persistence
- source/asset/space ownership
- cross-space IDOR
- failure lifecycle
- OpenAPI where exposed
- Flyway contract
- cleanup order for new FKs

## 6.6 Checkpoint

State:

`BUSINESS-006 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION`

Do not mark COMPLETE.

---

# 7. BUSINESS-007 — KnowledgePoint Provenance Foundation

Proceed only if ContentBlock (or authoritative equivalent) now exists.

If ContentBlock still does not exist, do not fake provenance.

## 7.1 Recover docs

Read exact definitions for:

- KnowledgePointSource
- SOURCE_DERIVED
- AI_DERIVED
- provenance
- source/content block linkage
- review/publish semantics

## 7.2 Persistence

Implement provenance relation exactly as supported by docs.

Critical invariant: a KnowledgePoint and its provenance targets must belong to the same LearningSpace.

Ordinary FKs usually do not prove this. Enforce it with scoped SQL/service validation.

## 7.3 Do not invent AI extraction

This phase is provenance infrastructure, not the AI generation engine.

Do not add provider calls merely to populate provenance.

## 7.4 API / tests

Expose only APIs actually needed by the product contract.

Test:

- same-space provenance accepted
- cross-space source/block rejected
- non-owner rejected
- detail/list does not leak another space’s provenance
- OpenAPI typed
- Flyway migration verified

## 7.5 Checkpoint

State:

`BUSINESS-007 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION`

---

# 8. Remaining Backend Roadmap After This Window

Do not implement these during this unattended run unless the operator explicitly changes scope.

Likely remaining major business slices:

1. Question bank
2. Practice sessions / attempts
3. Wrong-question analysis
4. Review scheduling
5. Exam / mock exam
6. Mastery / diagnosis
7. StudyPlan
8. AI provider integration for extraction/tutoring/analysis
9. Search integration where needed
10. Formal User/Auth/Login/Refresh/Admin model
11. Admin APIs
12. background progress/notification mechanics where needed
13. error contract / ProblemDetail normalization
14. local developer profile cleanup
15. operational hardening / observability / configuration / deployment

Exact order must follow `development-plan.md` and dependency reality.

---

# 9. Resume Capsule Template

At every phase close, ensure `docs/current-task.md` contains equivalent content:

```text
## Resume Instructions

Repository and docs are authoritative. Ignore old chat memory.

Baseline HEAD:
<actual hash>

Current Phase:
<phase>

Completed in this run:
- ...

In Progress:
- ...

Files added:
- ...

Files modified:
- ...

Latest migration:
Vxxx

Critical decisions:
- ...

Runtime evidence:
NONE for new slices unless user supplied it.

Static evidence:
- git diff --check ...
- ...

Known risks:
- ...

Next exact actions:
1. ...
2. ...
3. ...

Do not redo:
- ...
```

A fresh Hermes session must be able to resume from this section alone plus relevant code.

---

# 10. Development Log Checkpoint Format

Append one concise block per phase:

```text
## <date/time> <phase> checkpoint

WHAT
WHY
FILES
DB
API
SECURITY
TESTS
STATIC EVIDENCE
RUNTIME EVIDENCE MISSING
DECISIONS
DEFERRED
RISKS
NEXT
```

Do not paste huge diffs.

---

# 11. Final Static Closeout

Before stopping:

1. reread `current-task.md`
2. tail last 200–250 lines of `development-log.md`
3. read `development-plan.md`
4. `git status --short`
5. `git diff --check`
6. `git diff --stat`

Verify:

- no frontend changes
- no git writes
- no generated TS manual edits unless explicitly required by a live regenerated contract
- no duplicate domain models
- no global CSRF disable
- no broad permitAll
- no H2
- no Redis/MQ/ES/Graph DB unless docs require it
- no cross-space unscoped reads
- no fake runtime PASS
- no Question/Practice/Exam work in this unattended window

Update `development-plan.md` exactly to what is implemented vs awaiting runtime verification.

---

# 12. STOP CONDITIONS

Stop only for a genuine blocker:

1. unknown user changes in working tree
2. accepted ADR conflicts with required implementation
3. destructive migration risk
4. mutually incompatible product definitions
5. missing product decision that affects persistent/public contract
6. context pressure severe enough that continuing risks losing state and the environment cannot start a fresh Hermes session

Ordinary compile/import/test-design problems are not stop conditions. Diagnose them and continue.

---

# 13. Final Report

Output:

```text
BACKEND AUTONOMOUS WINDOW REPORT

1. Baseline
2. BUSINESS-005 status
3. BUSINESS-006 status
4. BUSINESS-007 status
5. Migrations added
6. APIs added
7. Security/space isolation
8. Tests added
9. Old-test compatibility
10. Documentation checkpoints
11. Static evidence
12. Runtime evidence still required
13. Deferred
14. Known risks
15. git status
16. Exact user verification commands
17. Resume instructions

FINAL STATE:
<IMPLEMENTED / PARTIAL / BLOCKED>
— AWAITING USER RUNTIME VERIFICATION
```

Do not start Question/Practice/Exam after this report.
