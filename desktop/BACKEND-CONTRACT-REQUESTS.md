# Backend Contract Requests

This file records A-class features that are blocked pending backend contract updates.
Each item maps to a specific checklist section.

> All items below are A-class. None may be marked deferred or postponed.
> Frontend implementation is ready to proceed immediately once the stable
> contract lands in `packages/api-client/src/generated/api.d.ts`.

## Status summary

Regenerated from the live server on 2026-10-14 — see the section below this one
for what that proved. Rows are the frontend's own audit items, not the C-numbers.

| Item | Status | Note |
|------|--------|------|
| A2.1 provenance nav | **Delivered, wiring now** | `KnowledgePointSourceResponse` gained `sourceId` + `sourcePageId`; the reader already accepts `?page=&block=`, so the provenance row becomes a real deep link instead of plain text. |
| A3.2 AI context | **Delivered, wiring now** | `SendMessageRequest` = `content, sourceId, sourcePageId, contentBlockId, knowledgePointId`. What the backend *validates* about those ids still has to be confirmed against its code before the UI promises anything. |
| A3.3 citation deep link | **Delivered, wiring now** | `ContextReference` gained structured `sourceId`/`sourcePageId`/`contentBlockId`; `locator` stays but is display-only, so the client stops parsing it (it never could reliably). |
| A8 Notes | **Built this round (unit-verified)** | CRUD + archive + paged list + keyword + context links + unsaved-changes guard are implemented and tested; runtime/E2E still needs a login. Four follow-ups are listed under "Notes module follow-ups". |
| A4.5 Question update | **Built this round, with a limit** | `PUT /questions/{questionId}` exists but `UpdateQuestionRequest` is only `stem`/`explanation`/`difficulty`; options and the correct answer cannot be edited, and the edit page says so. |
| A5.3 Practice result | **Delivered, wiring now** | `GET .../practice-sessions/{sessionId}/result` returns `PracticeResultView{sessionId,status,questionCount,answeredCount,correctCount,score,maxScore}`, so a session reopened from history can show its score. Per-question review comes from `GET .../answers` (whose `explanation` the backend confirmed is always null — a defect they will fix, not a design). |
| A6.2 Exam answer resume | **Delivered, wiring now** | `AttemptQuestionView` now carries `answer` (an `AnswerPayloadView`) and `answered`, so a reload can rebuild saved state per question. `ExamAnswerView` echoes `answer` + `submitted` as well. |
| A4.3 ORDERING / MATCHING | **Write side built; read side still missing (C09b)** | `CreateQuestionRequest.correctOrder`/`matches` + `AnswerPayloadView.orderingAnswer`/`matchingAnswer` exist and grade. `QuestionAnswerView` is still `{correctOptionKey, correctOptionKeys, correctBoolean, referenceAnswer}`: a stored standard order or pair set cannot be shown or pre-filled, and typed pairs (`C09` proper) plus real `questionType`/`status` enums are still open. |
| A4.2 Question paging | **Delivered and built** | `GET /questions` takes `page`/`size` (defaults 0/20) and returns `{items,page,pageSize,totalItems,totalPages}`; the list page drives the server and the guard pins the page size to the contract default. |
| A7.3 Search → Note | **Built** | `SearchResult.type` includes `NOTE` and the hit carries `noteId`; the type filter and the note deep link are implemented. |
| A6.1 Exam attempt history | **Delivered and built** | `GET /spaces/{spaceId}/exam-attempts?examId&status&page&size` → `{items:[AttemptSummaryView{id,examId,status,startedAt,submittedAt,score,maxScore}]}`. I asked for a nested `/exams/{examId}/attempts` path and got a query-param list instead; that is equivalent for the UI, and better, since one list covers both a paper's history and recent attempts across papers. **Correction to what I wrote here earlier:** the row said "graded vs ungraded must come from `status`". It cannot — `ExamAttemptService` gives the attempt exactly three statuses (NOT_STARTED / IN_PROGRESS / SUBMITTED, lines 42-44), and `AttemptSummaryView` carries no `gradingStatus`, so no `status` value can separate "submitted, awaiting grading" from "graded". With the fields on the wire the only reading available is the backend's stated rule that a score is sent only for a graded attempt, so the UI shows `—` + 等待评分 when `score` is not a number and the server's own number when it is. That is a prose dependency, not a contract fact: **if an ungraded attempt ever comes back with `score: 0`, that row will read as a real zero.** The fix is yours and it is small — put the attempt's existing `gradingStatus` (`GRADED` / `UNGRADED` / `NEEDS_REVIEW` / `PARTIALLY_GRADED`, lines 46-48 and 416) on `AttemptSummaryView`; `features/exams/exam-attempt-history.ts` is the single place the UI then switches. Same file also notes the summary has no `deadlineAt`, so a history row cannot say an in-progress attempt expired. |

---

## Status after regenerating from the live server (2026-10-14)

The backend session started AIStudy on `:8081` (`:8080` still belongs to another
project). `npm run api:generate` moved the snapshot from **67 paths to 195**, and
`npm run api:live` (`packages/api-client/scripts/check-api.mjs --live`) now proves
the committed snapshot equals the running server's document, so this section is
verification, not memory.

What that regeneration changed, with the contract evidence:

| Ask | Landed? | Evidence in `openapi.json` |
|-----|---------|-----------------------------|
| C01 Notes | yes | `/spaces/{spaceId}/notes` GET(page,size,keyword)+POST, `/{noteId}` GET+PUT+DELETE, `/{noteId}/archive` POST(204), `/{noteId}/knowledge-points`(+`/{kpId}`), `/{noteId}/source-blocks`(+`/{blockId}`); `NotePageResponse{items,page,pageSize,totalItems,totalPages}` |
| C05 Question update | yes, narrower than asked | `PUT /spaces/{spaceId}/questions/{questionId}`; `UpdateQuestionRequest{stem,explanation,difficulty}` only. **Unverified precondition:** the backend's message says "仅 DRAFT 可改", but this workspace's `QuestionService.update()` (QuestionService.java:122-126) rejects only ARCHIVED → 404 (QuestionController.java:72-76), and the regenerated op carries no description and lists only a 200 response. A prose rule is not a contract: the UI keeps the ARCHIVED gate it can prove and does not add a PUBLISHED gate it cannot, so editing a PUBLISHED question is still offered. Asked which the running server does and with which status code; until it lands in the contract (`@Operation(description=…)` or a structured 409 + code) this row stays **未验证**, not "delivered". |
| C07 Question archive/restore | yes | `POST /questions/{id}/archive`, `POST /questions/{id}/restore` (same pair exists for exams, spaces, sources, knowledge points) |
| C09 ORDERING / MATCHING write side | yes | `CreateQuestionRequest.correctOrder` (`array<int32>`, `maxItems:100`) and `.matches` (`string`, `maxLength:4000`); `AnswerPayloadView.orderingAnswer` (`array<int32 items minimum:1>`, `maxItems:100`) and `.matchingAnswer` (`string`, `maxLength:10000`) |
| C06 Practice result + per-question review | **yes, both halves** | `GET .../practice-sessions/{sessionId}/answers` → `PracticeAnswerView{id,practiceSessionQuestionId,questionId,isCorrect,correctAnswer,explanation,score,answeredAt}` (错题回顾), and `GET .../practice-sessions/{sessionId}/result` → `PracticeResultView{sessionId,status,questionCount,answeredCount,correctCount,score,maxScore}` (成绩). The backend states the totals are written inside the `finish` transaction (V066 added `score`/`max_score`/`answered_count`/`correct_count`), not recomputed per read, and that `result` deliberately does NOT repeat the answer shape — per-question detail stays in `answers`. Two 409 branches: `code:"PRACTICE_SESSION_NOT_SUBMITTED"` (not finished yet) and `code:"PRACTICE_RESULT_NOT_RECORDED"` (finished before V066, so no stored score). Neither code exists anywhere in this workspace's `server/` copy (its migrations stop at V057), so they are pinned as named constants sourced from the backend's message, and C12 remains the ask that would pin them in the contract. |
| C-Search `NOTE` | yes | `SearchResult.type` enum includes `NOTE`; the hit gained `noteId` (and `sourceId`/`sourceAssetId`/`sourcePageId`/`pageNumber`/`knowledgePointId`/`questionId`) |
| C02 provenance ids | **yes** | `KnowledgePointSourceResponse{id,spaceId,knowledgePointId,contentBlockId,sourceId,sourcePageId,relationType,relevanceScore,createdAt}` — `sourceId`/`sourcePageId` are on the wire, which is why the `provenanceExtras` cast is gone |
| C03 AI context | **yes** | `SendMessageRequest{content,sourceId,sourcePageId,contentBlockId,knowledgePointId}` — the context ids are sendable, so the UI attaches them instead of prefixing prose into `content` |
| C04 citation ids | **yes** | `ContextReference{type,id,title,snippet,locator,sourceId,sourcePageId,contentBlockId}` — the ids are structured; `locator` is still published and stays free text, so navigation reads the ids and never parses `locator` |
| C10 exam answer read-back | **yes** | `AttemptQuestionView{examQuestionId,questionId,sortOrder,score,questionType,stem,options,answer,answered}` on `GET .../exam-attempts/{attemptId}` — the saved `answer` is an `AnswerPayloadView` and `answered` is the server's own flag. There is still no `GET .../exam-attempts/{attemptId}/answers` subresource (only POST), which is the shape we originally asked for; the read-side on the attempt is sufficient, so we adapt to it. |
| C11 learner attempt list | **yes** | `GET /spaces/{spaceId}/exam-attempts?examId=&status=&page=&size=` → `AttemptPageResponse{items,page,pageSize,totalItems,totalPages}` of `AttemptSummaryView{id,examId,status,startedAt,submittedAt,score,maxScore}`. One space-level list with an optional `examId` filter covers both shapes we discussed (a paper's attempts, and recent attempts across papers), so we asked them NOT to add `/exams/{examId}/attempts`. Open question: whether `score`/`maxScore` are null or 0 for an attempt that is not graded yet — the UI distinguishes "no score yet" from "0 points" and never computes either (ADR-031). |
| C-Paging question list | **yes** | `QuestionPageResponse{items,page,pageSize,totalItems,totalPages}` and `GET /questions` takes `page`/`size`; the list page reads its page from `?page=` instead of faking a gap |
| FILL_BLANK per-blank grading | **no** (accepted) | `textAnswer` is one `string` (`maxLength:4000`) and `QuestionAnswerEvaluator.gradeFillBlank` does whole-string `trim().equals`, so a multi-blank answer is only correct if it matches the reference's exact punctuation. The UI copy says so instead of promising per-blank marking. |

### Two asks this round added (they are new consequences of the widened write side)

- **C09b — read side for order and pairs.** `QuestionAnswerView` still has only
  `correctOptionKey`/`correctOptionKeys`/`correctBoolean`/`referenceAnswer`, so a
  question's standard order/pair set can be written once and never read: the
  detail page cannot show it, and an editor cannot pre-fill it. Needed:
  `correctOrder` and `matches` (or a redacted view) on the authoring read model.
- **C-Ordering-space — confirm and constrain the integer space.**
  `AnswerPayloadView.orderingAnswer.items` declares `minimum: 1`, while
  `CreateQuestionRequest.correctOrder` items declare nothing, and
  `gradeOrdering` compares the two with exact `List.equals`. So 1-based option
  positions are the only interpretation both sides can share, but nothing stops
  an import or an AI-generated question from storing 0-based values, which makes
  every answer wrong with no error anywhere. Needed: `minimum: 1` on
  `correctOrder` items and a check that its values are a permutation of the
  question's option positions.

---

## Status after merging `main` (2026-10-13, merge 960ca1f)

Reading the server sources that just landed changed the picture for three items.
The generated client in `packages/api-client` is still the older 67-path
snapshot, so none of this is usable from TypeScript yet — but these are no
longer *backend coding* asks:

| Item | Verified in `main` code | What is actually left |
|------|-------------------------|-----------------------|
| C01 Notes | `note/controller/NoteController.java`: list+create, `GET`/`PUT`/`DELETE /{noteId}`, `POST /{noteId}/archive`; `note/dto/NoteView\|NoteCreateRequest\|NoteUpdateRequest\|NotePageResponse`; `V057__add_note_context_links.sql`; `NoteOpenApiContractTest` asserts the paths reach the contract | Start the server, then `npm run api:generate` here; then the frontend builds the Notes module |
| C05 Question update | `QuestionController` has `@PutMapping("/{questionId}")` | Regenerate, then re-register the edit route and the form's edit branch |
| C07 Question delete/archive | `QuestionController` has `/archive` and `/restore` | Regenerate, then expose archive/restore. The A/B "informational" ambiguity is resolved: this is A and the endpoint exists |

Everything else in this file stays a real contract ask until a regenerated
`api.d.ts` proves otherwise (`packages/api-client` still has no Notes paths):
C02 provenance ids, C03 AI context, C04 citation ids, C06 practice result,
C09 ordering/matching payloads, C10 saved exam answers, C11 attempt list,
C12 discriminable conflict codes, C-Paging, C-Search `NOTE`, C13 content-block
hit id semantics, C14 per-asset page filtering, C15 jobs readable at queue time.

### Re-checked against the server sources after the merge (2026-10-13)

Reading the Java that `main` actually ships split the list above into three
different kinds of work. Field names below are copied from the sources, not
invented:

**(a) Already implemented — needs only a running server + client regeneration**

- **C09 seven question types.** `question/eval/AnswerDataCodec.AnswerPayloadView`
  is `(selectedOptionKeys, booleanAnswer, textAnswer, orderingAnswer:
  List<Integer>, matchingAnswer: String)`; stored correct answers use
  `{"correctOrder":[3,1,2]}` and `{"matchesJson":"[{\"left\":\"A\",\"right\":\"1\"}]"}`,
  and `CreateQuestionRequest` accepts `correctOrder: List<Integer>` plus
  `matches: String`. FILL_BLANK uses comma-delimited `referenceAnswer`.
  So ORDERING and MATCHING can be authored *and* answered today; the frontend
  blocked them only because this client snapshot predates them. The earlier plan
  of encoding an order through `selectedOptionKeys` array order is superseded —
  use `orderingAnswer`.
- **C06 practice per-question review (partially).** `PracticeAnswerController`
  exposes `GET .../practice-sessions/{sessionId}/answers` per the backend's own
  report, which the snapshot does not list; that alone restores submitted state.
  The score summary (`score`/`maxScore`/`correctCount`) is still not on the
  session detail, so the `/result` ask stands for the history case only.
- **C13 content-block hit semantics (partially).** `search/dto/SearchResult`
  carries `type: SearchEntityType` and `sourcePageId`, so the deep link can be
  built from real fields once the enum and ids are confirmed from the generated
  contract.

**(b) Confirmed still missing in code — genuine backend work**

- **C02 provenance ids.** `knowledge/source/dto/KnowledgePointSourceResponse` is
  `(id, spaceId, knowledgePointId, contentBlockId, relationType, relevanceScore,
  createdAt)` — no `sourceId`, no `sourcePageId`. KP → exact page deep link
  stays blocked.
- **C-Paging question list.** `QuestionController.list` takes only
  `status`, `questionType`, `knowledgePointId` and returns a plain `List`.
  No `page`/`size`, so the question list cannot paginate honestly.

**(c) Confirmed missing in the server sources too**

- **C03 AI context.** `ai/dto/AiDto.SendMessageRequest` is `(String content)` —
  no context ids of any kind.
- **C04 citation ids.** `AiDto.ContextReference` is `(type, id, title, snippet,
  locator)` with `locator` still free text and a convenience constructor passing
  `null`.
- **C10 saved exam answers and C11 attempt list.** `ExamAttemptController`
  exposes only `GET /exam-attempts/{attemptId}` and `.../result`: no answers
  sub-resource, no attempt list. Neither reload recovery nor "reopen a previous
  attempt" can be built today.

**(d) Resolved by the sources — frontend work only, no backend ask**

- **C-Search `NOTE`.** `search/type/SearchEntityType` already declares `NOTE`
  ("User-owned learning notes (archived notes excluded by search)"), and
  `SearchResult` carries `type,id,title,snippet,sourceId,sourceAssetId,
  sourcePageId,knowledgePointId,questionId`. There is no `noteId`, so a note hit
  is identified by `id` exactly as a content-block hit is: once Notes exists the
  search page maps `NOTE` to the note route off `hit.id`. The only remaining
  request is documentation — confirm in the OpenAPI description that `id` is the
  note id for `NOTE` rows, same open question as C13 for `CONTENT_BLOCK`.

**Root cause located in the sources (this is a mechanical fix, not a design
question).** `common/problem/ApiErrorCodes.java` already defines
`EXAM_ATTEMPT_NOT_IN_PROGRESS` (:28) and `EXAM_DEADLINE_EXCEEDED` (:29), and
`common/problem/ApiException.java` already carries a `code` field
(`ApiException(HttpStatus, String code, String detail)`). The two exam throw
sites just do not use them:

- `exam/service/ExamAttemptService.java:163-166` —
  `new ResponseStatusException(CONFLICT, "exam attempt is not IN_PROGRESS
  (EXAM_ATTEMPT_NOT_IN_PROGRESS): " + status)`
- `exam/service/ExamAttemptService.java:604-607` (`assertNotExpired`) —
  `new ResponseStatusException(CONFLICT, "exam deadline exceeded
  (EXAM_DEADLINE_EXCEEDED)")`

Because the code is interpolated into the message, the advice layer can only
report the generic `CONFLICT`, and the frontend is left parsing English text.
Switching these to `ApiException(HttpStatus.CONFLICT,
ApiErrorCodes.EXAM_ATTEMPT_NOT_IN_PROGRESS, <detail>)` — and the same for
`EXAM_DEADLINE_EXCEEDED` — is enough; no new vocabulary is needed. Please do the
same for the C10 case (a `SUBMITTED` attempt refusing a new answer) once that
endpoint exists.



**Still unverified** — C14 (per-asset page filtering) and C15 (job visible at
queue time): the page-listing endpoint was not found in `SourceController`, so
these stay open questions rather than claims.

**Not a frontend dependency.** The backend's own audit lists F-15 (the asset
content endpoint returns the whole body with no `Range`/`206` support). Checked
against this client: the renderer never points a browser at a raw asset URL —
there is no `<img src>`, `<iframe>`, `<object>` or `<embed>` referencing
`/assets/{assetId}/content`, and the workbench reader renders extracted
`ContentBlock` text. So no seek/progressive-load path exists and Range support is
not needed for the Desktop scope as written. It is not in the queue below.

**One scope decision for the product owner, not a contract gap.** Because the
reader shows extracted text only, an image source (or an image-only PDF, which
OCR is excluded from processing) currently ends at the notice
「图片已成功处理。当前版本不提供 OCR 文本提取。」 with no visual rendering of the
uploaded image itself. Master A1–A11 does not ask for it, and B12.1 excludes OCR
while B12.3 excludes a PDF canvas/thumbnail/annotation UI — but "show the
uploaded image" is not named in either list. Per the A/B discipline it must be
decided explicitly rather than assumed: either it is B (permanently out, and the
notice is the finished behaviour) or it is A and needs a decision plus the
content-URL plumbing. Flagged, not built, not silently dropped.

**Coordination:** asks go to the backend session one at a time, waiting for each
reply. The one currently standing is "start the server + a desktop login
account"; the regeneration itself is frontend work, not theirs.

---

## Contract verification, 2026-10-13 — SUPERSEDED

> Kept because it records what was known at the time, but the table below was
> read off the **67-path snapshot**. The "Status after regenerating from the live
> server (2026-10-14)" section above replaces every row in it: C01, C05, C07,
> C06 (review half), C09 (write half) and C-Search all landed, and the asks that
> survived are listed there with current evidence. Do not quote a row below as
> the present state of the contract.

Every row above was re-checked field by field against
`packages/api-client/src/generated/openapi.json` (67 paths) rather than from
memory. The precise deltas the backend has to close:

| ID | Schema / endpoint as it exists today | What must be added |
|----|--------------------------------------|--------------------|
| C01 | no `/notes` path at all | `GET`+`POST /spaces/{spaceId}/notes`, `GET`+`PUT`+`DELETE /spaces/{spaceId}/notes/{noteId}`, `NoteView`/`NoteCreateRequest`/`NoteUpdateRequest` per the A8 section below |
| C02 | `KnowledgePointSourceResponse{id,spaceId,knowledgePointId,contentBlockId,relationType,relevanceScore,createdAt}` | `sourceId`, `sourcePageId` |
| C03 | `SendMessageRequest{content}` | optional `sourceId`, `sourcePageId`, `contentBlockId`, `knowledgePointId` |
| C04 | `ContextReference{type,id,title,snippet,locator:string}` | structured `sourceId`/`sourcePageId`/`contentBlockId`; `locator` is free text and cannot be parsed safely |
| C05 | `POST /questions`, `POST /questions/{id}/publish`, no update verb | `PUT /spaces/{spaceId}/questions/{questionId}`, DRAFT-only, 409 otherwise |
| C06 | `PracticeSessionDetail{id,spaceId,status,questionCount,startedAt,finishedAt,createdAt,updatedAt,questions}` — no score | `GET .../practice-sessions/{sessionId}/result` returning `PracticeSubmitView` + `PracticeAnswerView[]` |
| C09 | `AnswerPayloadView{selectedOptionKeys,booleanAnswer,textAnswer}`, `QuestionAnswerView{correctOptionKey,correctOptionKeys,correctBoolean,referenceAnswer}`, `QuestionOptionInput{optionKey,content,sortOrder}` | ordered answer + pair structures for ORDERING/MATCHING, multi-blank text for FILL_BLANK, and `questionType`/`status` as real enums |
| C10 | `ExamAnswerView{id,examAttemptId,examQuestionId,gradingStatus,answeredAt}` | the submitted payload itself (same shape as `AnswerPayloadView`) plus a `submitted` flag, readable from `GET /exam-attempts/{attemptId}` |
| C-Paging | `GET /questions{status,questionType,knowledgePointId}`, no paging params | `page`/`size` and a paged response envelope |
| C-Search | `SearchResult.type ∈ [SOURCE, CONTENT_BLOCK, KNOWLEDGE_POINT, QUESTION, WRONG_QUESTION]` | `NOTE` plus a `noteId` so a note hit is openable |

These requests were sent to the backend development session together with the
running-service handover (restart + a login account for integration testing).
Until they land in `api.d.ts`, the corresponding frontend flows stay gated —
no field is invented to make a page look complete.


---

## A8 Notes CRUD (A8.1 + A8.2 + A8.3)

**Checklist:** A8.1, A8.2, A8.3  
**Current gap:** No Notes endpoints exist in `packages/api-client/src/generated/api.d.ts`. The frontend has no Notes routes or UI.

### Required contract

**Endpoints**

1. `GET /api/v1/spaces/{spaceId}/notes` — list notes in a space
   - Query params: `page?`, `size?`, `keyword?`
   - Response: paginated list of Note summary views

2. `POST /api/v1/spaces/{spaceId}/notes` — create note
   - Request body: see `NoteCreateRequest` below
   - Response: `NoteView`

3. `GET /api/v1/spaces/{spaceId}/notes/{noteId}` — get note detail
   - Response: `NoteView`

4. `PUT /api/v1/spaces/{spaceId}/notes/{noteId}` — update note
   - Request body: see `NoteUpdateRequest` below
   - Response: `NoteView`

5. `DELETE /api/v1/spaces/{spaceId}/notes/{noteId}` — delete/archive note
   - Response: 204 No Content

**Schemas**

```ts
// Request: create note
NoteCreateRequest {
  title?: string
  content: string          // plain text or Markdown
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
  questionId?: number
}

// Request: update note (all fields optional; only changed fields need be present)
NoteUpdateRequest {
  title?: string
  content?: string
  sourceId?: number | null
  sourcePageId?: number | null
  contentBlockId?: number | null
  knowledgePointId?: number | null
  questionId?: number | null
}

// Response: note detail / list item
NoteView {
  id: number
  spaceId: number
  title?: string
  content: string
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
  questionId?: number
  createdAt: string
  updatedAt: string
}
```

**Frontend readiness**
- Routes reserved in `router.tsx` under `/spaces/:spaceId/notes` and `/spaces/:spaceId/notes/:noteId` (currently absent).
- UI modules will be created in `desktop/src/renderer/src/features/notes/`.
- Notes list/create/edit/delete wired through `@aistudy/api-client`.

---

## A2.1 KnowledgePoint → Source provenance navigation

**Status (2026-10-14): delivered and built.** `KnowledgePointSourceResponse` now
carries `sourceId` and `sourcePageId`, and the knowledge detail page links to the
exact page with the block in the query. The gap text below is the 2026-10-13 reading.

**Checklist:** A2.1  
**Current gap:** `KnowledgePointSourceResponse` (returned by `GET /knowledge-points/{id}/sources`) only contains `contentBlockId`. The frontend can deep-link to Source with `?block=` but cannot navigate to the exact Source page or confirm ownership.

### Required contract

**Schema change**

```ts
KnowledgePointSourceResponse {
  id: number
  knowledgePointId: number
  contentBlockId: number
  sourceId: number            // ADD — required for Source navigation
  sourcePageId: number        // ADD — required for page-level deep-link
  relationType?: string
  createdAt?: string
}
```

**Frontend usage**
- `KnowledgePointDetailPage.tsx` reads `sourceId`, `sourcePageId`, `contentBlockId` from each provenance link.
- Navigate to `/spaces/:spaceId/sources/:sourceId?page=<sourcePageId>&block=<contentBlockId>`.
- If `sourcePageId` is absent/invalid, fallback to `/spaces/:spaceId/sources?block=<contentBlockId>` (workbench-level block highlight).

**Current fallback** (already implemented, pending backend):
- When `sourceId` is absent, the UI still renders a Source workbench deep-link using `?block=` only.

---

## A3.2 Source / Knowledge → Ask AI context

**Status (2026-10-14): delivered and built.** `SendMessageRequest{content,sourceId,
sourcePageId,contentBlockId,knowledgePointId}`. The backend's own context resolver
(fresh checkout `D:\AIProject\server`, `ai/context/AiLearningContextService.java:142-193`)
checks each id for ownership AND space scope and answers 400 `VALIDATION_ERROR` on a
foreign one (`:344-347`), 400 on a `sourceId`/`sourcePageId` mismatch (`:349-352`), and
resolves the ids *before* persisting the user message (`AiConversationService.java:253-260`)
— so a refused attachment leaves no half-completed turn. Because a block already fixes its
page and source server-side, the client sends the narrowest single pointer, never a trio.
**New ask from this work:** `SendMessageRequest` has no `questionId`, so a question /
practice / exam entry cannot attach anything and must keep saying so.

**Checklist:** A3.2  
**Current gap:** `SendMessageRequest` only contains `content?: string`. The AI tutor cannot receive context identifiers, so it cannot answer questions scoped to a specific Source page, ContentBlock, or KnowledgePoint.

### Required contract

**Schema change**

```ts
SendMessageRequest {
  content: string
  // ADD — all optional context identifiers
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
  knowledgePointId?: number
}
```

**Frontend usage**
- `AiChatPage.tsx` will send `sourceId`, `sourcePageId`, `contentBlockId`, `knowledgePointId` alongside `content` when the user opens AI from a Source/Knowledge context.
- A UI chip will display the active context and allow removal.
- The backend should use these IDs to scope retrieval/context injection; the frontend will not send full text or large payloads.

---

## A3.3 AI reference/citation stable IDs

**Status (2026-10-14): delivered and built, with one leftover.** `ContextReference`
now carries `sourceId`, `sourcePageId` and `contentBlockId` alongside `locator`, and
navigation reads those ids — `locator` is a printed label only (the citation table's
`CHECK` list is `SOURCE, SOURCE_PAGE, CONTENT_BLOCK, KNOWLEDGE_POINT, QUESTION,
WRONG_QUESTION, NOTE` per `V065__ai_reference_structured_locators.sql:32-35`), and a
test asserts a `P12`-style locator can never leak into a route. Because those pointer
columns are `ON DELETE SET NULL` (`:24-27`), a citation can legitimately arrive with
them absent while `id` names a dead row, so nothing links off `id` for source-tree
types — that case renders as text.
**New ask:** `WRONG_QUESTION` has no navigable target — `ContextReference` carries no
`questionId`, and the desktop has no wrong-question route. Add `questionId` (or reuse
`knowledgePointId`-style structured pointers) and that citation becomes linkable.

**Checklist:** A3.3  
**Current gap:** AI response references (`references[]` on assistant messages) use `type` + `id` + `title` + `snippet`. The frontend can navigate to Source / KnowledgePoint / Question when `id` is present. However, for Source references the backend should also provide `sourcePageId` so the deep-link can jump to the exact page.

### Required contract

```ts
AiReference {
  type: 'SOURCE' | 'KNOWLEDGE_POINT' | 'QUESTION' | 'CONTENT_BLOCK'
  id: number
  title?: string
  snippet?: string
  // ADD for SOURCE / CONTENT_BLOCK references
  sourceId?: number
  sourcePageId?: number
  contentBlockId?: number
}
```

**Frontend usage**
- `AiChatPage.tsx` maps `SOURCE` → `/spaces/:spaceId/sources/:sourceId?page=<sourcePageId>&block=<contentBlockId>`
- `KNOWLEDGE_POINT` → `/spaces/:spaceId/knowledge/:id`
- `QUESTION` → `/spaces/:spaceId/questions/:id`
- `CONTENT_BLOCK` → same as SOURCE deep-link

---

## A4.1 Question authoring — DELETE/ARCHIVE endpoint (informational)

**Checklist:** A4.4  
**Current gap:** The Question endpoints support list/get/create/publish. If the final product requires users to delete or archive questions, the frontend needs a corresponding mutation.

**Request:** If question deletion/archiving is a product requirement, add:
- `DELETE /api/v1/spaces/{spaceId}/questions/{questionId}` or
- `POST /api/v1/spaces/{spaceId}/questions/{questionId}/archive`

The frontend `QuestionDetailPage.tsx` is ready to accept an additional `delete` mutation once the endpoint is stable.

---

## A5.3 Practice session result endpoint

**Status (2026-10-14): delivered.** `GET /spaces/{spaceId}/practice-sessions/{sessionId}/result`
is in the committed snapshot and `api:live` is green against the running server, returning
`PracticeResultView{sessionId,status,questionCount,answeredCount,correctCount,score,maxScore}`.
The gap text below is kept as the record of why the score was never derived client-side.

**Checklist:** A5.3 (Completion)
**Current gap:** `POST /practice-sessions/{sessionId}/finish` returns
`PracticeSubmitView` (`score`, `maxScore`, `answeredCount`, `correctCount`,
`questionCount`), but `GET /practice-sessions/{sessionId}` returns
`PracticeSessionDetail`, which carries **none** of those fields, and there is
no result/diagnosis resource for practice the way exams have
`/exam-attempts/{attemptId}/result`. The Desktop can therefore only show a
score during the same page visit in which the session was finished; reopening a
finished session renders an empty summary. Computing the score client-side is
forbidden by ADR-031.

**Request:** add `GET /api/v1/spaces/{spaceId}/practice-sessions/{sessionId}/result`
returning the `PracticeSubmitView` shape (same fields, so the renderer can reuse
it), or extend `PracticeSessionDetail` with those fields once the session is
finished. A per-question list (`PracticeAnswerView[]`) in the same response
would additionally let the UI show which items were wrong without re-deriving
anything locally.

**Frontend state:** `PracticeResult` reads the summary from the finish
response held in component state and says so when it is absent
(`features/practice/PracticeSessionPage.tsx`); the derived types live in
`lib/types.ts` (`PracticeSubmitSummary`).

---

## A4.5 Question authoring — update endpoint

**Checklist:** A4.4
**Current gap:** the contract exposes `GET`/`POST /api/v1/spaces/{spaceId}/questions`
and `POST .../questions/{questionId}/publish`, but no update verb. An "edit
question" screen can therefore only POST a new question and leave the original
untouched — a silent duplicate.

**Request:** add `PUT /api/v1/spaces/{spaceId}/questions/{questionId}` taking the
same `CreateQuestionRequest` body (full replace, DRAFT-only), returning the
existing Question response schema; or `PATCH` with a partial body if partial
editing is what the product wants.

**Frontend state:** the unreachable `questions/:questionId/edit` route and the
`QuestionForm` edit branch were removed instead of being left half-wired, and
`route-contract.test.ts` now fails if a route is registered that nothing can
reach. When the verb lands: give `QuestionForm` an `initialValue`, add the update
mutation, re-register the route and link it from `QuestionDetailPage`.

---

## C11 Exam attempt history (audit W07)

**Status (2026-10-14): delivered, in a better shape than asked.**
`GET /spaces/{spaceId}/exam-attempts?examId=&status=&page=&size=` returns
`AttemptPageResponse` of `AttemptSummaryView{id,examId,status,startedAt,submittedAt,score,maxScore}`
— one space-level paged list covers both a single paper's history and recent attempts across
papers, so we told the backend not to add `/exams/{examId}/attempts` on top of it.

**Checklist:** A6.1, A6.3
**Verified gap (2026-10-13):** `ExamResponse` has no attempts field, and there is
neither `GET /spaces/{spaceId}/exams/{examId}/attempts` nor
`GET /spaces/{spaceId}/exam-attempts`. An attempt can therefore only ever be
created from the exam detail page; a previous attempt and its result cannot be
reached again from the UI.

**Request:** a paged attempt list for one exam (`id`, `status`, `startedAt`,
`submittedAt`, and whatever score fields the contract already exposes on
`ExamResultView`), or a recent-attempt summary embedded in `ExamResponse`.
The frontend will not substitute "create another attempt" for history.

## C12 Discriminable conflict codes

**Checklist:** A6.2, A9.3
**Verified gap:** exam conflicts all come back as `code: "CONFLICT"`; whether the
deadline passed, the attempt is not `IN_PROGRESS`, or the exam is unavailable is
only readable inside the free-text `detail`. Branching on that text is not a
contract, and the English copy must not be shown to the user.

**Request:** stable codes — e.g. `EXAM_DEADLINE_EXCEEDED`,
`EXAM_ATTEMPT_NOT_IN_PROGRESS`, `EXAM_NOT_AVAILABLE` — declared in the OpenAPI
responses (enum preferred), including the C10 case where a `SUBMITTED` attempt
refuses a new answer.

**Open question for the backend:** `ORDERING` answers are planned as an ordered
`selectedOptionKeys` array, because no ordered field exists yet. Confirmation is
needed that grading compares array order; otherwise wait for C09's
`orderedOptionKeys`/`pairs` rather than inventing a wire format.

**(e) FILL_BLANK is coarser than its own documentation claims (refines C09).**
`AnswerDataCodec.buildFillBlankAnswerData` is documented as "comma-delimited per
blank", but `QuestionAnswerEvaluator.gradeFillBlank` (:135) decides correctness
with `payload.textAnswer().trim().equals(reference.trim())` — one whole-string
comparison. There is no per-blank split, so:

- a learner who fills every blank correctly but writes `北京, 上海` (space) or
  `北京，上海` (full-width comma) is graded wrong;
- partial credit per blank is impossible;
- the answer-count of the stem cannot be validated at all.

The frontend now states the real rule in both places that could otherwise
mislead (the authoring hint in `QuestionForm` and the practice answer
placeholder), because teaching "逐项比对" would promise behaviour the grader does
not have. **Ask:** either implement per-blank grading behind `textAnswers:
string[]` (already requested in C09) or drop the "comma-delimited per blank"
javadoc so the contract and the code agree.


## Notes module follow-ups (found while building it, 2026-10-14)

- **C-Notes-archive — archived notes are unreachable.** Archive only sets
  `archived_at` (`NoteService.java:146-154`) and the list filters
  `archived_at IS NULL` (`NoteMapper.java:42,59`), while `NoteView` has no
  `archivedAt` field at all. So the action is one-way and invisible: the note
  vanishes from the list and neither the client nor the user can tell which notes
  are archived or bring one back. Needed: `archived` in the list query,
  `archivedAt` in `NoteView`, and a restore endpoint — questions, spaces, sources
  and knowledge points all have one; notes do not.
- **C-Notes-reverse — no entity → notes listing.** There is
  `GET /notes/{noteId}/knowledge-points` but no
  `GET /knowledge-points/{id}/notes` (nor a source/question equivalent), so a
  knowledge point page cannot show or count the notes attached to it. Only the
  note → entity direction is buildable, which leaves A8.3 half a loop.
- **C-Notes-link-noop — a bad link id is reported as success.**
  `NoteKnowledgePointService.java:145-150` and `NoteSourceService.java:57-62`
  `return` after a failed `getMine` lookup and the controller still answers
  **201 with no body**. A client cannot distinguish "linked" from "silently
  ignored because the id belongs to another space", so it cannot report a truthful
  result. Needed: 404/400 for an unknown or foreign id. The UI only offers ids
  read from the space's own catalog, which hides the defect rather than fixing it.
- **Documentation contradiction:** `NotePageResponse.java:6-8` says "`page` is
  1-based" while `NoteController.java:45-46` defaults `page` to 0 and
  `NoteService.java:86` computes `safePage * safeSize`. The wire is **0-based**
  and the client follows the code; please fix one of the two.

## Answer payload rules the renderer must obey (verified in the server, 2026-10-14)

Not asks — constraints our code has to satisfy, read out of
`practice/service/PracticeAnswerService.java:255-345` and
`exam/service/ExamAttemptService.java:660-690`:

1. **One field per answer.** Each type branch rejects any other populated field:
   `ORDERING answer must only carry orderingAnswer`, `TRUE_FALSE … only carry
   booleanAnswer`, and so on. A payload builder that keeps `selectedOptionKeys`
   filled while adding `orderingAnswer` gets a 400 on practice.
2. **ORDERING indices are 1-based on practice** —
   `PracticeAnswerRequest.orderingAnswer` is
   `List<@Min(1) Integer>`, so a 0 is a 400 there. The exam DTO
   (`ExamAttemptDto`:100) has only `@Size(max = 100)` and **no** `@Min`, and
   `ExamAttemptService`:675 still accepts the legacy `selectedOptionKeys` order
   for ORDERING. Ask (C-Ordering-space): make both surfaces agree, so a
   zero-based or dual-field payload is not silently acceptable in one path and a
   400 in the other.
3. **FILL_BLANK grading is whole-string.** `QuestionAnswerEvaluator.gradeFillBlank`
   does `payload.textAnswer().trim().equals(reference.trim())`, so blanks are only
   correct when they match the reference's exact punctuation and order. The
   practice error copy says `textAnswer (JSON array string)` but nothing parses a
   JSON array — `PracticeAnswerService`:319 forwards it verbatim into that
   string comparison. Ask (minor): either implement per-blank comparison or fix
   the message, because the two together teach a future reader that a JSON array
   is expected.

---

## Resolution order

Refreshed 2026-10-14 after the second and third regenerations (67 → 195 → 197 paths),
which delivered most of the original list. What is actually still open:

1. **A login account that survives long enough to verify against.** The backend runs
   Flyway on the shared `aistudy_flyway_test` schema, and its most recent run went to
   V066; `dev` / `devpassword123` has already been wiped twice by that path. Requested:
   re-seed and name a stable window (no integration tests inside it) so the two live
   journeys recorded as "not run" — practice grading through the new `result` endpoint,
   and `deadlineAt` on an exam attempt — plus R03–R05 can be executed.
2. **C05's precondition** — does the running server accept `PUT /questions/{id}` for a
   PUBLISHED question, or reject it, and with which code? Prose said DRAFT-only; the
   code and the contract say otherwise (see the table row above). Nothing may be gated
   on the prose.
3. **C09 read side and enums** — typed `matchingAnswer` pairs (instead of a JSON string),
   the authoring read-back of `correctOrder` / `matches`, and real `questionType` /
   `status` enums in the contract. Every status/kind field is still `string`, which is
   precisely why `backend-vocabulary-contract.test.ts` has to read Java constants to
   catch guessing.
4. **C12 discriminable codes** — the exam conflicts still collapse into `code:"CONFLICT"`
   with the reason only in free-text `detail`, and the two new practice-result codes
   (`PRACTICE_SESSION_NOT_SUBMITTED`, `PRACTICE_RESULT_NOT_RECORDED`) exist only in a
   chat message: neither appears anywhere in this workspace's `server/` copy, whose
   migrations stop at V057. Landing them in the contract is what turns the frontend's
   named constants into a checked fact.
5. **The `explanation` defect** — `GET .../practice-sessions/{id}/answers` always returns
   `explanation: null`; the backend confirmed that is a bug, not a design, and owns the fix.
6. **C-Paging for the remaining lists** — questions are paged now; the conversation and
   message pages need their ordering documented (the backend's answer: conversations sort
   `COALESCE(last_message_at, created_at) DESC`, messages sort `created_at ASC`, so page 0
   is the OLDEST message).

Once each item is reflected in `packages/api-client/src/generated/api.d.ts` and
typecheck passes, the frontend implements the matching UI in the same pass — no
item is deferred to a later version, and none is closed on the strength of a unit
test alone.
