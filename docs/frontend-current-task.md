# Frontend Current Task

## Objective

FE-001 + FE-001.5 + FE-002A + FE-002B + FE-AI-001 + FE-QUESTION/PRACTICE/EXAM/SEARCH final completion.

## Current branch

- `feat/fe-001` (clean, uncommitted frontend work committed in logical batches)
- `packages/api-client` up to date with current stable backend contract

## Implemented (A-class)

| Feature | Routes / Scope | Status |
|---------|----------------|--------|
| Spaces | `/spaces` | Implemented |
| Sources + ingestion | `/spaces/:spaceId/sources`, `/spaces/:spaceId/sources/:sourceId` | Implemented (TXT/MD/PDF/PNG/JPEG) |
| Knowledge | `/spaces/:spaceId/knowledge`, `/spaces/:spaceId/knowledge/:knowledgePointId` | Implemented |
| AI Tutor | `/spaces/:spaceId/ai`, `/spaces/:spaceId/ai/:conversationId` | Implemented |
| AI Settings | `/settings/ai` | Implemented |
| Questions | `/spaces/:spaceId/questions`, `/spaces/:spaceId/questions/new`, `/spaces/:spaceId/questions/:questionId` | Implemented — create (all seven types), publish, edit, archive/restore. The contract's `UpdateQuestionRequest` only carries stem/explanation/difficulty, so options and the correct answer stay read-only and the UI says so |
| Notes | `/spaces/:spaceId/notes`, `/spaces/:spaceId/notes/:noteId` | Implemented — paged list + keyword, create, edit with unsaved-changes guard, archive/delete, knowledge-point and content-block links |
| Practice | `/spaces/:spaceId/practice`, `/spaces/:spaceId/practice/:sessionId` | Implemented — resume reads `GET .../answers`, and a finished session's score reads `GET .../result` on every visit; nothing is kept from the `finish` response and no number is counted client-side (ADR-031) |
| Exams | `/spaces/:spaceId/exams`, `/spaces/:spaceId/exams/:examId`, `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | Implemented |
| Search | `/spaces/:spaceId/search` | Implemented |
| Auth | `/login` | Implemented |

## Closed by the contract wave — frontend wiring, not a backend gap

| Feature | Contract that closed it (verified in the committed snapshot; `api:live` green) | Frontend status |
|---------|-------------------------------------------------------------------------------|-----------------|
| Practice history score (A5.3) | `GET .../practice-sessions/{sessionId}/result` → `PracticeResultView` | Wired — score comes only from that read; the two 409 codes (`PRACTICE_SESSION_NOT_SUBMITTED`, `PRACTICE_RESULT_NOT_RECORDED`) each get their own copy |
| Question list paging (A4.2) | `QuestionPageResponse` + `page`/`size` on `GET /questions` | Wired — page from `?page=` |
| Exam answer resume (A6.2) | `AttemptQuestionView{answer,answered}` on the attempt read | Wiring this round |
| Exam attempt history (A6.1/W07) | `GET /spaces/{spaceId}/exam-attempts?examId=&status=&page=&size=` | Wiring this round |
| Full provenance nav (A2.1) | `KnowledgePointSourceResponse.sourceId` / `.sourcePageId` | Wiring this round |
| AI context chips (A3.2) | `SendMessageRequest{sourceId,sourcePageId,contentBlockId,knowledgePointId}` | Wiring this round |
| AI reference deep-links (A3.3) | `ContextReference{sourceId,sourcePageId,contentBlockId}` beside `locator` | Wiring this round |

## Still blocked on the contract

| Feature | Blocker | Contract request |
|---------|---------|------------------|
| Show a stored standard order / pair set | `QuestionAnswerView` has neither `correctOrder` nor `matches` (re-verified against the 197-path snapshot) | C09b, `desktop/BACKEND-CONTRACT-REQUESTS.md` |
| Notes archive is one-way and invisible | `POST /notes/{id}/archive` exists but there is no restore, `NoteView` has no `archivedAt`, and the list filters archived rows out | C-Notes-archive / C-Notes-reverse / C-Notes-link-noop, same file |
| Every status/kind field | Still bare `string` — `questionType`, `status`, and the conflict `code`s are not enums in the contract, which is why the vocabulary guards read Java constants instead | C09 (enums) / C12 (codes), same file |
| Whether a PUBLISHED question may be edited | The backend's prose says DRAFT-only; `QuestionService.update()` (QuestionService.java:122-126) rejects only ARCHIVED, and the operation carries no description or non-200 response. Recorded as **unverified**, and the UI keeps only the gate it can prove | C05 precondition, same file |

Notes (A8) and question edit (A4.5) left the blocked table on 2026-10-14: the
regenerated contract carries both, and the frontend implements them now.

## Out of scope (B-class)

See `AISTUDY-FRONTEND-FINAL-COMPLETION-MASTER-CHECKLIST.md` section 12.

## Security contract

- BrowserWindow: `contextIsolation=true`, `nodeIntegration=false`, `sandbox=true`, `webSecurity=true`
- Renderer: no direct fetch/axios/XHR; no localStorage/sessionStorage token; no Node APIs
- AI: renderer never contacts provider directly; keys backend-owned
- Preload: `contextBridge` only; no `ipcRenderer` leak — the exposed surface
  (vault save/load/clear + read-only `aistudyConfig.getApiBaseUrl`) is asserted
  by `src/preload/index.test.ts`
- Backend target resolved at runtime by main (ADR-051):
  `AISTUDY_API_BASE_URL` -> `<userData>/config.json` -> `VITE_API_BASE_URL` ->
  default; CSP `connect-src` and the renderer client share that one value
- Single-instance lock: no second process may race the refresh-token vault
- Packaged builds: DevTools off, default menu removed (ADR-037)

## Final gates

Gate commands and their measured output live in one place:
`desktop/docs/FINAL-FEATURE-MATRIX.md` ("Verification evidence"). Counts are not
repeated here, because a second copy of a number is how this document claimed
"449 tests" for three weeks while the suite had grown past 700.

What must be green before any release claim:

- `npm run lint`, `npm run typecheck`, `npm run test:run`, `npm run build`
- `npm run api:check` — snapshot ↔ generated types ↔ manifest
- `OPENAPI_URL=<server>/v3/api-docs npm run api:live` — snapshot ↔ the running
  server (added 2026-10-14 after a stale snapshot shipped an exam field name the
  server never bound)

## Remaining work

1. Frontend wiring for the rows that moved out of blocked above (exam resume,
   attempt history, provenance deep links, AI context ids, citation navigation),
   then the full gates and a rebuilt artifact.
2. Backend asks that are still real: C09 (typed matching pairs + `questionType`
   and `status` enums), C09b (read side of `correctOrder` / `matches`), C12
   (discriminable codes — including the two practice-result codes, which exist
   only in a chat message because this workspace's `server/` copy stops at V057),
   the C-Notes follow-ups, C05's precondition, and the `answers` list returning
   `explanation: null` always (the backend called that a defect they will fix).
3. A10 installed-app auth smoke (manual, needs a desktop test account).
4. A11.3 critical E2E smoke (manual, same).
5. R02 install/uninstall of the real NSIS artifact — done once; re-run against the
   next build.
