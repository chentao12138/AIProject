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
| Practice | `/spaces/:spaceId/practice`, `/spaces/:spaceId/practice/:sessionId` | Implemented |
| Exams | `/spaces/:spaceId/exams`, `/spaces/:spaceId/exams/:examId`, `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | Implemented |
| Search | `/spaces/:spaceId/search` | Implemented |
| Auth | `/login` | Implemented |

## Blocked (A-class, pending backend contract)

| Feature | Blocker | Contract request |
|---------|---------|------------------|
| AI context chips (A3.2) | `SendMessageRequest` only has `content` | C03 in `desktop/BACKEND-CONTRACT-REQUESTS.md` |
| Full provenance nav (A2.1) | `KnowledgePointSourceResponse` missing `sourceId`/`sourcePageId` | C02, same file |
| AI reference deep-links (A3.3) | `ContextReference.locator` is free text; no page/block ids | C04, same file |
| Exam answer resume (A6.2) | `/exam-attempts/{id}/answers` is POST-only and `AttemptQuestionView` has no answer | C10, same file |
| Practice history score (A5.3) | Session read model has no score; only the `finish` response does | C06, delivered only for the per-question review |
| Question list paging (A4.2) | `GET /questions` accepts no `page`/`size` | C-Paging, same file |
| Show a stored standard order / pair set | `QuestionAnswerView` has neither `correctOrder` nor `matches` | C09b, same file |

Notes (A8) and question edit (A4.5) left this table on 2026-10-14: the
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

1. Contract asks C02/C03/C04/C06(score)/C09b/C10/C11/C-Paging — backend side.
2. A10 installed-app auth smoke (manual, needs a desktop test account).
3. A11.3 critical E2E smoke (manual, same).
4. R02 install/uninstall of the real NSIS artifact — produced but never installed.
