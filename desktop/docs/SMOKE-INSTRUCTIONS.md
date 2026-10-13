# AIStudy Desktop Smoke Instructions

This document captures the remaining manual smoke steps required before
declaring `FRONTEND V1 FUNCTIONALLY COMPLETE`.

## A10 Installed-app Auth Smoke

### Prerequisites

- Windows x64 machine
- Backend running at `http://localhost:8080` with a valid MySQL schema
- Valid test username/password

### Steps

1. Build installer:
   ```powershell
   cd D:\AIProject-frontend\desktop
   npm run dist:win
   ```
2. Install `dist/AIStudy Setup.exe` (double-click, accept defaults).
3. Launch AIStudy from Start Menu.
4. Verify Electron boot:
   - Window title = `AIStudy`
   - No startup crash / uncaught exception
5. Login:
   - Enter test username/password
   - Click Login
   - Verify redirect to `/spaces`
6. Close app (window X or system tray close).
7. Reopen app from Start Menu.
8. Verify session restore:
   - App returns to `/spaces` automatically (refresh token restore + `/auth/me`)
   - No manual re-login required
9. Logout:
   - Click User / Logout in AppShell
   - Verify redirect to `/login`
10. Reopen app:
    - App shows `/login` (anonymous)
11. Shutdown / uninstall:
    - `Add or Remove Programs` → AIStudy → Uninstall
    - Verify `%APPDATA%\AIStudy` (or equivalent Electron userData) is removed or acceptable

### Pass criteria

- Login → close → reopen → authenticated
- Logout → reopen → anonymous
- No token leakage to localStorage / logs / disk in renderer

---

## A11.3 Critical E2E Smoke

### Prerequisites

- Backend running at `http://localhost:8080`
- Valid test user with at least one LearningSpace
- `E2eBackendHarness` (or equivalent test JWT mechanism) available if backend requires seeded data

### Core user journeys

#### 1. Login

- Open app → `/login`
- Submit credentials
- Expect: redirect to `/spaces`

#### 2. Create/Open Space

- Create a new LearningSpace (name only)
- Expect: auto-redirect to `/spaces/{id}/sources`
- Re-open the space from `/spaces`
- Expect: same space data loads

#### 3. Source upload + ingestion

- Open Sources page
- Upload a TXT file
- Wait for ingestion SUCCEEDED
- Verify content area shows extracted text
- Upload a PDF (multi-page)
- Verify page navigation + text extraction

#### 4. Knowledge provenance

- Open Knowledge page
- Create a KnowledgePoint linked to a source
- Open point detail
- Click provenance link
- Expect: Source workbench opens with target page/block highlighted

#### 5. Question

- Open Questions page
- Create a Single-Choice question
- Verify list shows the new question
- Open detail → Publish
- Verify status badge changes to PUBLISHED

#### 6. Practice

- Open Practice page
- Start a session (5 questions)
- Answer questions + submit
- Finish session
- Verify result view shows score / answered / correct

#### 7. Exam

- Open Exams page
- Create an exam
- Open attempt
- Answer questions + save
- Submit exam
- Verify result view shows score / review items

#### 8. Search

- Open Search page
- Enter a query that matches a source, knowledge point, and question
- Verify results render with deep-links
- Click each result type → correct target page opens

#### 9. Notes (BLOCKED)

- No Notes endpoints in current backend contract
- Skip until A8 contract lands in `packages/api-client`

#### 10. AI Settings/Chat

- Open AI Settings
- Configure provider + API key
- Save → verify key not returned by GET
- Open AI Tutor
- Send a message
- Verify response renders + references (if any) are clickable

#### 11. Logout/restore

- Logout from AppShell
- Reopen app
- Verify anonymous state (`/login`)

### Pass criteria

- All enabled journeys above complete without runtime errors
- 401/403/404/5xx/network show normalized error states with retry
- Back/forward/refresh restore correct state
- No Node API / direct fetch / token leakage observed

---

## A11.4 Production Security Scan

### Static scan targets

- `desktop/src/renderer/src/**/*.tsx`:
  - No `localStorage` / `sessionStorage`
  - No `fetch(` / `XMLHttpRequest` / `axios`
  - No `require('electron')` / `ipcRenderer` / `process.env`
  - No `dangerouslySetInnerHTML`
- `desktop/src/main/**`:
  - `contextIsolation=true`, `nodeIntegration=false`, `sandbox=true`, `webSecurity=true`
  - No `webSecurity=false`
- `desktop/src/preload/**`:
  - No `ipcRenderer` leak outside `contextBridge`

### Command

```powershell
cd D:\AIProject-frontend\desktop
npm run lint
npx tsc --noEmit -p tsconfig.node.json && npx tsc --noEmit -p tsconfig.web.json
npm run test:run
npm run build
```

### Pass criteria

- Lint: 0 errors / 0 warnings
- Typecheck: exit 0
- Tests: all pass
- Build: exit 0
- Manual source grep confirms no forbidden patterns in renderer production code

---

## A11.5 Final Documentation Checklist

Only items whose file actually exists and was re-read against the code are
checked. A step is evidence, not a checkbox.

- [x] README matches implementation (scope section now names the contract-blocked
      flows and states that verification is manual smoke + Vitest, not Playwright)
- [x] `docs/ROUTE_MAP.md` lists all routes
- [x] `docs/FINAL-FEATURE-MATRIX.md` lists implemented / blocked / out-of-scope
- [x] `BACKEND-CONTRACT-REQUESTS.md` lists the blocked contracts
      (A8 Notes, A2.1, A3.2, A3.3, A4.5, A5.3 + C09/C10/C-Paging/C-Search)
- [x] Packaging instructions in README
- [x] Smoke instructions in this file
- [ ] `docs/frontend-current-task.md` — file does not exist in this repo
- [ ] `docs/frontend-development-log.md` — file does not exist in this repo

---

## Smoke steps, 2026-10-14

`BACKEND-CONTRACT-REQUESTS.md` records what the regenerated contract delivered.
That split these steps into two different kinds of waiting: some still need the
backend to publish a field, others need nothing but a running server plus a test
account. Nothing here is marked passed — an unrun step is an unrun step.

### Blocked by the contract (frontend cannot pass these yet, whatever it builds)

| Step | Blocked by | What a pass looks like |
|------|-----------|------------------------|
| Knowledge point → exact Source page + block highlight | C02 (`KnowledgePointSourceResponse` still has no `sourceId`/`sourcePageId`) | Provenance row is a working deep link, not plain text |
| Source / KP → Ask AI with context | C03 (`SendMessageRequest` = `content` only) | The message carries the id, and the answer demonstrably uses that material |
| AI citation → page/block | C04 (`ContextReference.locator` is free text) | Every supported reference type navigates |
| Reload mid-exam and continue | C10 (`/exam-attempts/{id}/answers` is POST-only; `AttemptQuestionView` has no answer) | Previously saved selections are shown; saved state is per question |
| Reopen a finished practice and see its score | C06, the half that did not land (`PracticeSessionSummary`/`Detail` have no score; only the `finish` response does) | Score comes from the server at any later time, not from a response held in memory |
| Question list paging | C-Paging (`GET /questions` accepts no `page`/`size`) | Pager drives server requests; no full-list fetch |
| Exam attempt history | C11 (attempts are only listable under `/admin/exams/{examId}/attempts`) | From the paper, a learner re-opens a past attempt and its result |
| Show the standard order / pair set of a stored question | C09b (`QuestionAnswerView` has neither `correctOrder` nor `matches`) | An editor pre-fills and a reader sees what the author set |

### Unblocked by the regeneration — waiting only on a running backend and a test account

These are implemented (or being implemented this round) against the real
contract; they need execution, not a backend change.

| Step | Unblocked by |
|------|--------------|
| Notes list / create / edit / delete, unsaved-changes guard, links to Source / KP / Question | C01: full Notes CRUD + archive + paged list + context links |
| Modify an existing draft question | C05: `PUT /questions/{id}` — note `UpdateQuestionRequest` is only `stem`/`explanation`/`difficulty`, so options and the correct answer stay locked and the UI says so |
| Archive / restore a question | C07: `POST /questions/{id}/archive` and `/restore` |
| Reopen a finished practice and review each answer | C06 (review half): `GET .../practice-sessions/{sessionId}/answers` |
| Search hit of type NOTE | C-Search: enum has `NOTE`, hit carries `noteId` |
| 排序/配对 authoring + answering + grading | C09 write side: `correctOrder`/`matches`, `orderingAnswer`/`matchingAnswer` |
| Exam created with a time limit, and the countdown that depends on it | Field-name correction: the server binds `timeLimitMinutes`; the frontend had been sending `durationMinutes`, so no exam ever got a limit and `deadlineAt` came back null |
| Installed-app login / restore / logout (A10, steps 1–11 above) | Nothing but a running backend and a desktop test account — these must be executed on `dist/AIStudy Setup*.exe`, not on the unpacked build |

Before any of the above is run: `OPENAPI_URL=<server>/v3/api-docs npm run api:live`
must be green. The `durationMinutes` incident is exactly what that gate now
catches — a snapshot, its types and its manifest can all agree with each other
while disagreeing with the server.

