# AIStudy Desktop Smoke Instructions

This document captures the remaining manual smoke steps required before
declaring `FRONTEND V1 FUNCTIONALLY COMPLETE`.

## A10 Installed-app Auth Smoke

### Prerequisites

- Windows x64 machine
- Backend reachable and **verified to be AIStudy**: `GET <base>/v3/api-docs` must
  list `/api/v1/spaces` and `/api/v1/auth/login`. **Never decide by port** — the
  same instance has been seen on `:8081` and on `:8080` within one day
  (`api:live` was green against `http://localhost:8080/v3/api-docs` at 02:33 on
  2026-10-14, after the backend session restarted it there). Pointing the app at
  an unverified origin once produced a "login works, everything else 404" session
  because that port belonged to a different project.
- Valid test username/password (requested from the backend session; the server
  ships no seed account, so this cannot be self-served)
- `OPENAPI_URL=<base>/v3/api-docs npm run api:live` green — the app must be built
  against the snapshot the server actually publishes

### Steps

1. Build installer (the mirror variables are required on this network; a failed
   download leaves the **previous** artifact in `dist/` and looks like success):
   ```powershell
   cd D:\AIProject-frontend\desktop
   $env:ELECTRON_BUILDER_BINARIES_MIRROR="https://npmmirror.com/mirrors/electron-builder-binaries/"
   $env:ELECTRON_MIRROR="https://npmmirror.com/mirrors/electron/"
   npm run dist:win
   ```
   Confirm `dist/AIStudy Setup 0.1.0.exe` has **today's timestamp** before installing.
   **Status at this commit:** the artifact on disk is 111,472,382 bytes, built
   2026-10-14 02:53, and it has **not** been installed. The recorded R02 pass
   (silent install → window titled `AIStudy` → uninstall keeps `%APPDATA%\AIStudy`)
   belongs to the 01:55 build (111,468,074 bytes), which is three lanes behind this
   one. Steps 2–11 must be re-run on the current file before R02 is claimed here,
   and steps 3 onward still need the test account that `:8080` currently refuses
   (`POST /api/v1/auth/login` with `dev` → `401 UNAUTHORIZED`, probed again this
   round after the backend's V066 Flyway run).
2. Install `dist/AIStudy Setup 0.1.0.exe` (double-click, accept defaults).
3. Point the installed app at the running backend. The build default is
   `http://localhost:8080`; precedence is
   `AISTUDY_API_BASE_URL` env → `%APPDATA%\AIStudy\config.json` → build-time
   `VITE_API_BASE_URL` → default. Use the config file, because it also proves the
   runtime path and that CSP `connect-src` follows it:
   ```powershell
   Set-Content "$env:APPDATA\AIStudy\config.json" '{"apiBaseUrl":"http://localhost:8081"}'
   ```
   The header must then show `后端地址：http://localhost:8081`. If it shows the
   default, the file is not being read — that is a finding, not a step to skip.
   (Found on 2026-10-14: `Set-Content -Encoding utf8` writes a UTF-8 BOM, and the
   parser used to discard the whole file over it, silently falling back to the
   packaged default. `parseRuntimeConfig` now strips a leading BOM, pinned by a
   test in `src/main/runtime-config.test.ts`.)
4. Launch AIStudy from Start Menu.
5. Verify Electron boot:
   - Window title = `AIStudy`
   - No startup crash / uncaught exception
6. Login:
   - Enter test username/password
   - Click Login
   - Verify redirect to `/spaces`
7. Close app (window X or system tray close).
8. Reopen app from Start Menu.
9. Verify session restore:
   - App returns to `/spaces` automatically (refresh token restore + `/auth/me`)
   - No manual re-login required
10. Logout:
   - Click User / Logout in AppShell
   - Verify redirect to `/login`
11. Reopen app:
    - App shows `/login` (anonymous)
12. Shutdown / uninstall:
    - `Add or Remove Programs` → AIStudy → Uninstall
    - `%APPDATA\AIStudy` is expected to SURVIVE: `build.nsis.deleteAppDataOnUninstall` is false, so notes and settings are not destroyed by an uninstall. Verify the directory is still there.
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
- [x] `docs/frontend-current-task.md` — it exists, but one level up:
      `../docs/frontend-current-task.md` (repo-root docs, not the desktop
      package). Same for the development log below. The earlier "does not exist
      in this repo" line was a wrong negative caused by looking only inside
      `desktop/`.
- [x] `docs/frontend-development-log.md` — `../docs/frontend-development-log.md`;
      it was last written for Stage C/D/E (2026-09-21) and is being brought
      forward with this round's entries.

---

## Live API journey, 2026-10-14 (real server, real token — not a GUI run)

With the account restored, the A-class flows were exercised against
`http://localhost:8081` through the same endpoints the renderer calls. This is
**API-level** evidence: it proves the wire contract and the server's behaviour,
not that the installed renderer paints anything.

| Result | Step |
|--------|------|
| PASS | `POST /auth/login` → access + refresh token pair |
| PASS | `GET /spaces` → the account's space (id 29) |
| PASS | `POST /notes` → note created (201) |
| PASS | `GET /notes/{id}` → `contentFormat` still `MARKDOWN` (no silent downgrade), `updatedAt` 6-decimal |
| PASS | `PUT /notes/{id}` with the full document replaces the content — the reason the editor never sends a partial body |
| PASS | `POST /notes/{id}/archive` → 204, and the note is gone from the paged list; page shape is `{items,page,pageSize,totalItems,totalPages}` |
| PASS | `POST /questions` ORDERING with 1-based `correctOrder: [2,1]` → 201 |
| PASS | `POST /questions/{id}/publish` → 200 |
| PASS | `POST /questions` MATCHING with `matches` = JSON `{left,right}` key pairs → 201 |
| **PASS** | `POST /exams` with `timeLimitMinutes: 45` → **`GET /exams/{id}` returns 45** — the field-name fix works against the server, which is the defect this round found |
| PASS | `GET /search?q=…&types=NOTE` → 200 (accepted; 0 hits because the only note is archived) |
| **not run** | practice grading of `orderingAnswer` |
| **not run** | exam attempt `deadlineAt`, and the C10 read-model check |

The last two did **not** run because the script skipped a step, not because the
product failed — recorded here so nobody reads them as defects or as passes:

- `POST /practice-sessions` returns `PracticeSessionSummary`, which has no
  `questions` array; the snapshot comes from `GET /practice-sessions/{id}`. The
  script read the create response, so it saw `undefined`. **The renderer takes
  the correct path** (it reads the detail), but the live check that the server
  grades a 1-based `orderingAnswer` as correct is therefore **still unproven**.
- `POST /exams/{id}/sessions` returned **400** — the exam was created but never
  published. So `deadlineAt` (the countdown's source) and the attempt read model
  remain unverified live.

Re-run to close those two: publish the exam, and read the practice session
detail before answering.

## Those two rows are now closed — live journey, 2026-10-14 03:1x

Reproduce it with `node scripts/live-journey.mjs` after exporting
`AISTUDY_API_BASE_URL`, `AISTUDY_USER` and `AISTUDY_PASS` (the script carries no
credentials; without them login fails and it aborts). It exits non-zero if any
assertion fails, and it writes into the backend's schema by design — run it only
inside a promised window.

Backend restarted main on `:8080`, re-seeded `dev`, and held the window (no IT, no
restart) until this finished. **22/22 assertions passed** against that instance:

| Result | Step (the server's own answer) |
|--------|-------------------------------|
| PASS | login 200; `GET /spaces` → `[]` (the schema had been wiped by IT) → `POST /spaces` 201 |
| PASS | `POST /questions` ORDERING with the shape the editor really sends — `options` A/B/C **and** `correctOrder:[1,2,3]` → 201 |
| PASS | the new rule at work: ORDERING with `correctOrder` and **no `options`** → **400 `VALIDATION_ERROR`**, so the editor's payload is the accepted one |
| **PASS** | C09b read-back: `GET /questions/{id}` → `answer.correctOrder == [1,2,3]` — the evidence that let both "cannot be read back" notices be deleted |
| **PASS** | C05 measured: `PUT` on DRAFT → 200; after publish, `PUT` → **409 `code: QUESTION_NOT_EDITABLE`**. The UI hides the edit entry for that state, so nobody fills a form whose save is doomed |
| **PASS** | first closed row: `GET …/practice-sessions/{id}/result` **before** `finish` → 409 `PRACTICE_SESSION_NOT_SUBMITTED`; after `start` + answering with `orderingAnswer:[1,2,3]` (`isCorrect=true`, graded server-side) + `finish` → 200 `{sessionId:28,status:"SUBMITTED",questionCount:1,answeredCount:1,correctCount:1,score:1,maxScore:1}` |
| **PASS** | the `explanation` fix landed: `GET …/answers` now returns `explanation:"顺序即判分依据。"`, not `null` |
| **PASS** | second closed row: `POST /exams` (`timeLimitMinutes:30`) → publish → attempt via `POST /exams/{examId}/sessions` (the app's verb; `…/attempts` is 404) → start → `GET /exam-attempts/{id}` → **`deadlineAt = 2026-10-14T04:31:12.962502`**, and its question reads `answered:false, answer:null` before any answer (C10 read model) |
| **PASS** | C11: `GET /exam-attempts?examId=&page=0&size=10` → `{items:[…],totalItems:1}` with **`score:null, maxScore:null`** on the in-progress attempt — so `—` + 等待评分 is the honest render, not `0 / 0` |

Not proven by this, and unchanged: anything about the **installed renderer**. R03–R05
still need the packaged app pointed at this account, and the artifact on `dist/`
postdates the last install run. R03–R05 (installed-app login/restore and the GUI
journeys) still need the same account plus a person or automation at the keyboard.

## Smoke steps, 2026-10-14

`BACKEND-CONTRACT-REQUESTS.md` records what the regenerated contract delivered.
That split these steps into two different kinds of waiting: some still need the
backend to publish a field, others need nothing but a running server plus a test
account. Nothing here is marked passed — an unrun step is an unrun step.

### Blocked by the contract (frontend cannot pass these yet, whatever it builds)

| Step | Blocked by | What a pass looks like |
|------|-----------|------------------------|
| Show the standard order / pair set of a stored question | C09b (`QuestionAnswerView` has neither `correctOrder` nor `matches` — they exist only on `CreateQuestionRequest`, re-verified against the current 197-path snapshot) | An editor pre-fills and a reader sees what the author set |
| See that a note is archived, and bring it back | C-Notes (archive sets `archived_at` and the list filters it out; `NoteView` has no `archivedAt` and there is no `POST /notes/{id}/restore`) | The archived note is findable and restorable |

Everything else that used to sit in this table has been unblocked by the
regenerations and moved below — including C02, C03, C04, C06's score half, C10,
C11 and C-Paging, all of which are now in the committed contract.

### Unblocked by the regeneration — waiting only on a running backend and a test account

These are implemented (or being implemented this round) against the real
contract; they need execution, not a backend change.

| Step | Unblocked by |
|------|--------------|
| Notes list / create / edit / delete, unsaved-changes guard, links to Source / KP / Question | C01: full Notes CRUD + archive + paged list + context links |
| Modify an existing draft question | C05: `PUT /questions/{id}` — note `UpdateQuestionRequest` is only `stem`/`explanation`/`difficulty`, so options and the correct answer stay locked and the UI says so |
| Archive / restore a question | C07: `POST /questions/{id}/archive` and `/restore` |
| Reopen a finished practice and review each answer | C06 (review half): `GET .../practice-sessions/{sessionId}/answers` |
| Reopen a finished practice and see its SCORE | C06 is fully closed: `GET .../practice-sessions/{sessionId}/result` → `PracticeResultView`. Unit-verified (the view calls only that endpoint, and a `finish` response that contradicts it is ignored); not yet run live. Two 409 branches to cover: `PRACTICE_SESSION_NOT_SUBMITTED` (session still open) and `PRACTICE_RESULT_NOT_RECORDED` (a session finished before V066 — expect this on any pre-existing session, since the workspace's `server/` copy is still at V057) |
| Question list paging | C-Paging: `QuestionPageResponse` + `page`/`size` on `GET /questions`; the page comes from `?page=` |
| Reload mid-exam and continue | C10 / A6.2: `AttemptQuestionView{answer,answered}` on the attempt read |
| Exam attempt history, and re-opening a past attempt | C11 / W07: `GET /spaces/{spaceId}/exam-attempts?examId=&status=&page=&size=` — **paged row + null-not-0 score measured live (22/22 run)**; the `gradingStatus` row that arrived afterwards is wired but NOT yet measured (see below) |
| Notes archived list + restore | C-Notes: `NoteView.archivedAt`, `POST /notes/{id}/restore`, `GET /notes?archived=true` — wired, **not run live**: the backend restarted mid-window to publish that field and `127.0.0.1:8080` refused afterwards, so only the earlier 22-assertion journey counts as measured |
| A `PARTIALLY_GRADED` attempt in the history list | Needs an attempt containing a subjective answer; would confirm `客观题已判分，主观题仍待人工评分` + its partial score against the server's own value. **Not run** — same restart. Contract evidence only: `AttemptSummaryView.gradingStatus` enum `GRADED / PARTIALLY_GRADED / UNGRADED` |
| `api:live` right now | **Cannot run** until the backend is back; `api:check` (snapshot ↔ types) is green, so the artifact is internally consistent but its "equals the running server" half is currently unverified |
| Knowledge point → exact Source page + block highlight | C02 / A2.1: `KnowledgePointSourceResponse.sourceId` + `.sourcePageId` |
| Source / KP → Ask AI with context | C03 / A3.2: `SendMessageRequest{sourceId,sourcePageId,contentBlockId,knowledgePointId}` |
| AI citation → page/block navigation | C04 / A3.3: `ContextReference{sourceId,sourcePageId,contentBlockId}` — navigate on the ids, never parse `locator` |
| Search hit of type NOTE | C-Search: enum has `NOTE`, hit carries `noteId` |
| 排序/配对 authoring + answering + grading | C09 write side: `correctOrder`/`matches`, `orderingAnswer`/`matchingAnswer` |
| Exam created with a time limit, and the countdown that depends on it | Field-name correction: the server binds `timeLimitMinutes`; the frontend had been sending `durationMinutes`, so no exam ever got a limit and `deadlineAt` came back null |
| Installed-app login / restore / logout (A10, steps 1–11 above) | Only a desktop test account now. `dev` / `devpassword123` on `:8081` (space 87) **was** issued on 2026-10-14 16:28 and has since been removed by the backend's own integration-test run — `/health` answers UP while login returns `401 UNAUTHORIZED`, and a restore has been requested. R02 (install → launch → uninstall) is proven and needs no account; these steps are not proven until one exists, and must be executed on `dist/AIStudy Setup*.exe`, not the unpacked build |

Before any of the above is run: `OPENAPI_URL=<server>/v3/api-docs npm run api:live`
must be green. The `durationMinutes` incident is exactly what that gate now
catches — a snapshot, its types and its manifest can all agree with each other
while disagreeing with the server.

