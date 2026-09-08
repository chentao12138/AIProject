# AIProject Backend Autonomous Continuation — BUSINESS-014 → 016 + Cross-Phase Audit

## 0. Why this continuation exists

The previous AUTORUN-5H-X3 session stopped because of the Hermes tool-iteration limit.

Actual repository inspection shows:

- HEAD: `594d37a feat: implement source ingestion pipeline`
- BUSINESS-008~013 production code and tests are present.
- V013~V018 are present.
- V019 mastery migration + Mastery production code are present.
- BUSINESS-014 has NO dedicated tests yet.
- BUSINESS-015/016 are not implemented.
- `docs/current-task.md` is stale: it still says BUSINESS-010 IN PROGRESS.
- `docs/development-plan.md` is also stale: status headline still says only BUSINESS-008/009 implemented.
- No test-profile Spring context currently contains `MasteryMapper` as `@MockitoBean`.
- No `@ResourceLock` exists in the repository even though prior docs claim shared flyway-it tests use one.

This continuation must FIRST reconcile those facts, then finish 014, implement 015/016, and perform a full static audit.

Do NOT rely on old chat.

Repository + current source + development-log tail are the truth when docs conflict.

---

# 1. Phase 0 — Recovery and documentation repair

Run/read:

```text
git status --short
git status -sb
git log -8 --oneline
git diff --check

docs/current-task.md
docs/development-log.md tail 240
docs/development-plan.md
docs/data-model.md
docs/api-guidelines.md
docs/architecture.md
docs/decisions.md
```

Inspect:

```text
server/src/main/java/com/aistudy/server/question
server/src/main/java/com/aistudy/server/practice
server/src/main/java/com/aistudy/server/wrong
server/src/main/java/com/aistudy/server/exam
server/src/main/java/com/aistudy/server/mastery

server/src/test/java/com/aistudy/server/question
server/src/test/java/com/aistudy/server/practice
server/src/test/java/com/aistudy/server/wrong
server/src/test/java/com/aistudy/server/exam

V013~V019
```

Repair docs BEFORE continuing:

```text
BUSINESS-008 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-009 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-010 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-011 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-012 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-013 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-014 IN PROGRESS
BUSINESS-015 NOT STARTED
BUSINESS-016 NOT STARTED
```

Do NOT mark 008~013 COMPLETE: user has not run runtime tests for them yet.

Record that the prior Hermes session hit the tool-iteration cap after writing V019/mastery production code but before 014 checkpoint/test wiring.

---

# 2. Global test infrastructure correction — do this before Mastery tests

## 2.1 MasteryMapper test-profile wiring

Actual source inspection found 16 full Spring contexts with:

```text
@SpringBootTest
@ActiveProfiles("test")
```

and ZERO currently contain `MasteryMapper`.

Because `MasteryService` is an unconditional `@Service` and the `test` profile does not provide formal business mappers, every such full-context test must remain bootable.

Audit ALL 16 actual classes and add exact:

```java
@MockitoBean
private MasteryMapper masteryMapper;
```

unless the class intentionally mocks `MasteryService` instead.

Do not rely on the previous report saying “only 3 contexts remain”; actual source contradicts that report.

Do not widen mapper scanning.

## 2.2 Shared flyway-it DB serialization

Prior docs state shared flyway-it tests use a common ResourceLock, but actual repository inspection found no `@ResourceLock`.

This must be corrected now.

All test classes that mutate the shared `aistudy_flyway_test` schema must use the SAME exclusive JUnit resource:

```java
@ResourceLock("aistudy-flyway-test")
```

At minimum audit every:

```text
@SpringBootTest
@ActiveProfiles("flyway-it")
```

test class that writes/migrates/cleans that schema, including FlywayMigrationIntegrationTest.

Use ONE exact resource name everywhere.

Do not use sleeps.
Do not synchronize production code.
Do not create multiple lock names.

## 2.3 Cleanup graph

Actual source currently has 12 classes containing `cleanBizTestRows()`.

Before V019 runtime verification, add:

```text
DELETE mastery
```

before deleting `knowledge_point` / `learning_space`.

When V020/V021 are added later in this run, extend all destructive cleaners child-first for the new diagnosis/plan tables.

Never use:

```text
FOREIGN_KEY_CHECKS=0
TRUNCATE
DROP FK
CASCADE for test convenience
```

If duplication becomes dangerous, introducing a test-only shared cleaner is allowed, but avoid a gratuitous rewrite if a consistent scripted update is safer.

---

# 3. BUSINESS-014 — Finish Mastery correctly

Current production files already exist:

```text
V019__create_mastery.sql
Mastery.java
MasteryMapper.java
MasteryScoringPolicy.java
MasteryService.java
MasteryController.java
MasteryResponse.java
```

Do not rewrite from scratch.

First review and fix the following evidence-contract issues.

## 3.1 Practice evidence must include SUBMITTED sessions only

Current `MasteryMapper.selectPracticeEvidence()` counts `practice_answer` rows without proving their practice session is SUBMITTED.

That allows an answer from an IN_PROGRESS practice session to affect a later mastery recomputation triggered by another event.

Fix query path:

```text
practice_answer
→ practice_session_question
→ practice_session
```

and require:

```text
practice_session.status = 'SUBMITTED'
```

Keep:

```text
pa.is_correct IS NOT NULL
space/user/KP scope
```

## 3.2 Exam evidence must include SUBMITTED attempts only

Current `selectExamEvidence()` joins `exam_attempt` but does not require SUBMITTED.

Require:

```text
exam_attempt.status = 'SUBMITTED'
```

so answers from an in-progress exam cannot affect mastery when some later recompute occurs.

## 3.3 Review evidence and lastEvidenceAt

V019 exposes:

```text
review_evidence_count
last_evidence_at
```

Current service counts review records, but `lastEvidenceAt` only takes max(practice, exam).

Change review evidence query to return both:

```text
count
MAX(review_record.completed_at)
```

Then:

```text
lastEvidenceAt = max(practice, exam, review)
```

The V1 score/confidence policy may remain based on graded objective practice+exam evidence only if docs/comments explicitly say review count is explanatory and not part of score.

Do not silently make review correctness change mastery score unless docs require it.

## 3.4 Owner-scoped mastery detail read

Current `getMine()` ultimately calls:

```text
selectByUserSpaceKp(userSubject, spaceId, kpId)
```

without a `learning_space.owner_subject` join.

Project contract requires business detail reads to be owner/space scoped.

Add an owner-scoped detail mapper query with:

```text
mastery
JOIN learning_space
JOIN knowledge_point (same space)
WHERE mastery.user_subject = authenticated subject
  AND mastery.space_id = path space
  AND knowledge_point_id = path kp
  AND learning_space.owner_subject = authenticated subject
```

Use it for public API reads.

Keep anti-probing 404.

## 3.5 Practice hook

Current `PracticeAnswerService.finish()` already calls Mastery after the session transition to SUBMITTED.

Verify:

```text
SUBMITTED update
→ Wrong/Review write
→ Mastery recompute
```

all remain in the same transaction.

Add tests proving only SUBMITTED practice evidence counts.

## 3.6 Exam hook

Current ExamAttemptService does NOT yet call Mastery.

After successful:

```text
exam_result insert
exam_attempt IN_PROGRESS → SUBMITTED
```

and still inside the same transaction:

- collect relevant questionIds from the paper slots
- call `masteryService.recomputeForQuestions(ownerSubject, spaceId, questionIds)`

Do this only after the attempt is SUBMITTED, so the new query status predicate sees the just-submitted evidence in the same transaction.

No mastery update on mere answer save.

## 3.7 Mastery tests

Add a dedicated pure policy test:

Suggested coverage:

```text
0 evidence -> score 0/confidence 0
1/1 -> 1.0
1/2 -> 0.5
confidence reaches 1.0 at 5
bounds
```

Add a real MySQL Mastery integration test, target roughly 12–18 meaningful tests:

- no evidence → no row until recompute policy decision
- practice SUBMITTED correct contributes
- practice SUBMITTED wrong contributes
- IN_PROGRESS practice answer does NOT contribute
- exam SUBMITTED contributes
- IN_PROGRESS exam answer does NOT contribute
- practice + exam aggregate
- SHORT_ANSWER/ungraded excluded
- question without KP ignored
- multiple KPs recompute independently
- review evidence count persists
- review completed_at participates in lastEvidenceAt
- owner list weakest-first
- detail same owner 200
- other user / wrong space / wrong KP 404
- API never accepts client mastery score

Add Mastery OpenAPI contract coverage for:

```text
GET /api/v1/spaces/{spaceId}/mastery
GET /api/v1/spaces/{spaceId}/knowledge-points/{kpId}/mastery
```

typed response + bearer auth.

Update Flyway test for V019 structure.

Checkpoint:

```text
BUSINESS-014 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
```

Then continue.

---

# 4. BUSINESS-015 — Structured ExamDiagnosis, aligned with repository docs

IMPORTANT:

The earlier AUTORUN document proposed a generic space-level DiagnosisPolicy with WEAK/DEVELOPING/MASTERED thresholds.

The authoritative repository data model instead explicitly defines:

```text
ExamDiagnosis
ExamDiagnosisItem
```

and development-plan Phase 7 requires:

```text
structured ExamDiagnosis
feedback to Mastery/Review/Plan
```

Therefore BUSINESS-015 must implement structured **ExamDiagnosis**, not invent a separate generic diagnosis domain unless docs are changed by the user.

## 4.1 Migration

Create next actual migration after V019, expected V020 if no other migration exists.

Tables:

```text
exam_diagnosis
- id
- exam_attempt_id
- user_subject
- space_id
- summary nullable
- created_at

exam_diagnosis_item
- id
- exam_diagnosis_id
- dimension_type
- dimension_id nullable
- label
- score
- max_score
- accuracy
- evidence_count
- severity nullable
- recommendation nullable
- created_at
```

Use explicit FKs, no CASCADE, utf8mb4.

Unique one diagnosis per exam attempt.

## 4.2 Deterministic structured generation

Generate diagnosis during successful exam submit, in the SAME transaction after the result is computed.

Required dimensions V1:

1. `KNOWLEDGE_POINT`
2. `QUESTION_TYPE`

CATEGORY can be deferred unless implementation is straightforward and source docs demand it.

For each dimension:

```text
score
maxScore
accuracy
evidenceCount
```

must be derived from actual submitted exam items.

Do not call AI.

`severity` and `recommendation` are optional in data-model.md.

Do NOT invent unsupported threshold semantics merely to populate them.

Leaving optional fields null in V1 is acceptable and more source-faithful than inventing a diagnosis taxonomy.

Question→KnowledgePoint mapping may use current relation at submit time; if so, document that as a V1 limitation. The diagnosis rows persist the computed historical snapshot afterward.

## 4.3 API

Add typed owner-scoped endpoint:

```text
GET /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}/diagnosis
```

Rules:

- attempt absent/not owner/wrong space -> 404
- attempt not SUBMITTED -> 409
- submitted attempt with missing diagnosis -> 409 (internal state inconsistency), unless service generates lazily by explicit accepted design

Do not expose arbitrary map payloads.

## 4.4 Tests

Target 10–15 meaningful integration tests:

- diagnosis auto-created on submit
- one diagnosis per attempt
- KP aggregation
- question type aggregation
- multi-KP question handling according to explicit V1 rule
- wrong/unanswered scoring
- SHORT_ANSWER handling consistent with exam scoring
- deterministic values
- repeat submit still 409 and no duplicate diagnosis
- pre-submit diagnosis 409
- other user / wrong space 404
- typed API
- no answer leak beyond what result/diagnosis intentionally exposes

Add OpenAPI coverage.

Add V020 tables to all test cleanups, child-first:

```text
exam_diagnosis_item
exam_diagnosis
```

before deleting exam_attempt.

Add new mapper mocks to every test-profile context.

Checkpoint:

```text
BUSINESS-015 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
```

---

# 5. BUSINESS-016 — StudyPlan / StudyTask, aligned with repository docs

Authoritative data model:

```text
StudyPlan
StudyTask
```

API guideline already shows singular:

```text
GET /api/v1/spaces/{spaceId}/study-plan
```

Prefer singular “current plan” semantics for V1 rather than inventing a collection API.

## 5.1 Migration

Next migration after ExamDiagnosis, expected V021.

Tables:

```text
study_plan
- id
- user_subject
- space_id
- name
- start_date nullable
- end_date nullable
- status
- created_at
- updated_at

study_task
- id
- study_plan_id
- user_subject
- space_id
- task_type
- target_type
- target_id nullable
- title
- reason nullable
- due_at nullable
- priority
- status
- completed_at nullable
- created_at
- updated_at
```

Use docs enum direction:

```text
taskType: LEARN / PRACTICE / REVIEW / EXAM
status: TODO / IN_PROGRESS / DONE / SKIPPED
```

Keep target polymorphism service-validated.

## 5.2 Conservative V1 lifecycle

Because API is singular and docs do not define multiple active plans:

- at most one ACTIVE/current plan per `(user_subject, space_id)`
- generating when an active plan already exists should return 409
- no silent auto-archive of an existing plan
- plan may become COMPLETED when all tasks are DONE/SKIPPED
- no client-submitted mastery mutations

If repository docs explicitly contradict this after re-read, follow docs.

## 5.3 Deterministic generation

No AI.

Generation should consume current backend facts:

1. pending/due ReviewTask items
2. weakest Mastery knowledge points
3. optionally latest structured ExamDiagnosis as explanatory reason

Stable deterministic ordering:

```text
due review tasks first
then mastery_score ASC
then confidence ASC
then stable id
```

Use a bounded `dailyItemLimit`.

Do not randomize.

Do not generate duplicate tasks for the same logical target within one plan.

A safe V1 generated task shape:

- pending ReviewTask -> `REVIEW`, target QUESTION or KNOWLEDGE_POINT based on review target
- weak/low-confidence Mastery -> `LEARN` or `PRACTICE`, target KNOWLEDGE_POINT

Do not fabricate an Exam task unless a concrete published exam is actually selected.

## 5.4 API

Keep singular path aligned with api-guidelines:

```text
POST /api/v1/spaces/{spaceId}/study-plan/generate
GET  /api/v1/spaces/{spaceId}/study-plan
POST /api/v1/spaces/{spaceId}/study-plan/tasks/{taskId}/complete
```

Typed DTOs.

`generate` request may contain:

```text
name
startDate?
endDate?
dailyItemLimit
```

Validate date order and positive bounded limit.

Completion:

```text
TODO/IN_PROGRESS -> DONE
DONE -> idempotent return OR 409
```

Choose one explicit contract and test it. Prefer idempotent return if docs do not specify otherwise.

Owner/wrong space/task -> 404.

## 5.5 Tests

Target 15–20 meaningful integration tests:

- generate empty-evidence plan behavior
- review tasks prioritized
- weakest mastery ordering
- stable deterministic ordering
- daily limit
- no duplicates
- date validation
- active-plan conflict 409
- GET current plan
- task completion
- repeated completion explicit behavior
- all tasks done -> plan COMPLETED if implemented
- other user/wrong space 404
- target isolation
- no client mastery mutation
- typed OpenAPI

Add V021 tables to every destructive cleaner:

```text
study_task
study_plan
```

must be deleted before mastery/review/question/space parents.

Add StudyPlan/StudyTask mappers to all test-profile contexts.

Checkpoint:

```text
BUSINESS-016 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
```

---

# 6. Cross-phase static audit — mandatory

After 014~016:

## 6.1 Correct-answer leakage

Audit Question/Practice/Exam DTOs.

Before practice/exam submit, no:

```text
answerData
correctOptionKey
correctOptionKeys
correctBoolean
isCorrect
score that reveals correctness
```

unless an endpoint is explicitly an authoring endpoint.

## 6.2 State transitions

Audit:

```text
Question publish
Practice start/finish
Review complete
Exam publish/start/submit
StudyTask complete
```

Invalid transitions -> explicit 409.

Idempotent operations must be explicitly documented/tested.

## 6.3 Owner/space isolation

Search new modules for:

```text
selectById(
getById(
selectOne(
```

No public business path may rely on an unscoped user-owned read.

All public detail/list mutations must include `spaceId + authenticated subject`.

## 6.4 Mastery evidence correctness

Confirm:

```text
practice evidence -> SUBMITTED sessions only
exam evidence -> SUBMITTED attempts only
short answer/ungraded excluded
review count + last timestamp accurate
```

## 6.5 Mapper test-profile audit

Enumerate every:

```text
@SpringBootTest
@ActiveProfiles("test")
```

Ensure every production mapper V001~latest needed for bean construction has an exact `@MockitoBean`.

Do not trust prior counts.

## 6.6 Shared DB lock audit

Enumerate every mutating:

```text
@SpringBootTest
@ActiveProfiles("flyway-it")
```

Ensure same:

```text
@ResourceLock("aistudy-flyway-test")
```

exists.

## 6.7 Cleanup audit

Actual FK child-first order must include at least:

```text
study_task
study_plan
exam_diagnosis_item
exam_diagnosis
mastery
review_record
review_task
wrong_question
practice_answer
practice_session_question
practice_session
exam_answer
exam_result
exam_attempt
exam_question
exam_paper
exam
question_source
question_knowledge_point
question_option
question
knowledge_point_source
content_block
source_page
ingestion_job
source_asset
knowledge_point
knowledge_category
source
learning_space
```

Adjust from actual FKs, not this text alone.

No global unsafe cleanup.

## 6.8 Flyway

Update FlywayMigrationIntegrationTest dynamically through latest migration.

Assert critical schema:

- V019 mastery unique/index/FKs
- V020 diagnosis FK/unique
- V021 plan/task FK/index

Do not reintroduce hardcoded stale final migration counts.

---

# 7. Static verification policy

At each phase end:

```text
mvnw offline test-compile if the environment/tool budget permits
git diff --check
git status --short
```

A static `test-compile` success is NOT runtime PASS.

If Hermes tool-iteration budget becomes tight:

1. finish current phase to a coherent boundary
2. write current-task recovery capsule
3. append development-log checkpoint
4. git diff --check
5. stop safely

Do not leave docs stale again.

---

# 8. Final docs state

At final closeout:

`docs/current-task.md` must say actual source truth, expected:

```text
BUSINESS-008 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-009 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-010 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-011 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-012 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-013 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-014 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-015 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
BUSINESS-016 IMPLEMENTED — AWAITING USER RUNTIME VERIFICATION
```

`development-plan.md` must not still claim only 008/009 are implemented.

Latest migration should match actual source, likely V021 if diagnosis is persisted as docs specify.

---

# 9. Final report

Output exactly:

```text
BACKEND AUTONOMOUS CONTINUATION REPORT

1. Baseline reconciliation
2. Stale docs repaired
3. BUSINESS-014 Mastery final status
4. Practice evidence status filter
5. Exam evidence status filter
6. Review lastEvidence handling
7. Exam->Mastery hook
8. Mastery tests/OpenAPI
9. BUSINESS-015 ExamDiagnosis status
10. Diagnosis dimensions and persistence
11. BUSINESS-016 StudyPlan status
12. StudyPlan deterministic generation
13. Migrations V019~latest
14. API endpoints
15. Security/space isolation audit
16. Correct-answer leakage audit
17. Test-profile mapper mock audit
18. flyway-it ResourceLock audit
19. FK cleanup final order
20. Tests added
21. Flyway coverage
22. Static compile evidence
23. git diff --check
24. git status
25. Runtime evidence still missing
26. Exact focused Maven command
27. Exact full Maven command
28. Resume instructions

FINAL:
BUSINESS-008~016 IMPLEMENTED
— AWAITING USER RUNTIME VERIFICATION
```

Do not start Auth/Admin/AI/PDF/Search after this report.
