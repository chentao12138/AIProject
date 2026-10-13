# Backend Contract Requests

This file records A-class features that are blocked pending backend contract updates.
Each item maps to a specific checklist section.

> All items below are A-class. None may be marked deferred or postponed.
> Frontend implementation is ready to proceed immediately once the stable
> contract lands in `packages/api-client/src/generated/api.d.ts`.

## Status summary

| Item | Status | Note |
|------|--------|------|
| A2.1 provenance nav | Partial | Deep-link via `?block=` added; full nav needs `sourceId`/`sourcePageId` in `KnowledgePointSourceResponse`. |
| A3.2 AI context | Blocked | `SendMessageRequest` only contains `content`; context chips require optional context ID fields. |
| A8 Notes | Blocked | No `/notes` endpoints in `api.d.ts`. UI not implemented. |
| A4.5 Question update | Blocked | No `PUT`/`PATCH` on `/questions/{questionId}`; the form is create-only and the edit route was removed. |
| A5.3 Practice result | Blocked | `PracticeSessionDetail` carries no score; only the `finish` response does, so a past session cannot show its result. |
| A6.2 Exam answer resume | Blocked | `ExamAnswerView` echoes no answer content, so a reload cannot rebuild what was saved. |
| A4.3 ORDERING / MATCHING | Blocked | `AnswerPayloadView` and `QuestionAnswerView` cannot express an order or pairs. |
| A4.2 Question paging | Blocked | `GET /questions` accepts no `page`/`size`; client-side full-list paging is forbidden by the checklist. |
| A7.3 Search → Note | Blocked | `SearchResult.type` enum has no `NOTE`, so a note hit cannot be opened. |

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

**(c) Not yet checkable from the sources I could find** — C03 AI context,
C04 citation ids, C10 saved exam answer content, C11 attempt list, C12 conflict
codes, C-Search `NOTE`, C14 per-asset page filtering, C15 job visibility at
queue time. These get answered by a regeneration plus one focused look, not by
guessing.


**Coordination:** asks go to the backend session one at a time, waiting for each
reply. The one currently standing is "start the server + a desktop login
account"; the regeneration itself is frontend work, not theirs.

---

## Contract verification, 2026-10-13

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

## Resolution order

1. A8 Notes — highest priority (entire feature blocked).
2. A2.1 provenance — unblock full Source/Page/Block navigation.
3. A3.2 AI context — unblock context-aware AI Tutor.
4. A3.3 AI reference IDs — polish citation deep-links (can follow A3.2).
5. A4.5 Question update — unblocks question correction before content is published.
6. A5.3 Practice result — makes a finished practice session readable again.

Once the above contracts are reflected in `packages/api-client/src/generated/api.d.ts` and typecheck passes, the frontend will implement the remaining UI in a single follow-up pass.
