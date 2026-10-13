# AIStudy Desktop Final Feature Matrix

## Implemented (A-class)

| Feature | Routes | Status |
|---------|--------|--------|
| Spaces | `/spaces` | Implemented |
| Sources | `/spaces/:spaceId/sources`, `/spaces/:spaceId/sources/:sourceId` | Implemented |
| Knowledge | `/spaces/:spaceId/knowledge`, `/spaces/:spaceId/knowledge/:knowledgePointId` | Implemented |
| AI Tutor | `/spaces/:spaceId/ai`, `/spaces/:spaceId/ai/:conversationId` | Implemented |
| Questions | `/spaces/:spaceId/questions`, `/spaces/:spaceId/questions/new`, `/spaces/:spaceId/questions/:questionId`, `/spaces/:spaceId/questions/:questionId/edit` | Implemented — all seven types authorable, edit (stem/explanation/difficulty only, as the contract allows), publish, archive/restore |
| Notes | `/spaces/:spaceId/notes`, `/spaces/:spaceId/notes/:noteId` | Implemented — server-paged list + keyword, create with cross-entity prefill, editor with unsaved-changes guard, archive/delete, knowledge-point and content-block links |
| Practice | `/spaces/:spaceId/practice`, `/spaces/:spaceId/practice/:sessionId` | Implemented — server resume (`GET .../answers`), and the score of a finished session read from `GET .../result` at any later time, never from the `finish` response and never counted here (ADR-031) |
| Exams | `/spaces/:spaceId/exams`, `/spaces/:spaceId/exams/:examId`, `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | Implemented — create with the field the server binds (`timeLimitMinutes`), paper editing, attempt start/answer/submit, server-reported resume, and a server-paged attempt history that reopens any past attempt |
| Search | `/spaces/:spaceId/search` | Implemented |
| Auth | `/login` | Implemented |
| AI Settings | `/settings/ai` | Implemented |

## Closed by the contract wave — frontend work, not a backend gap (2026-10-14)

Every row below was Blocked on a contract that has since shipped. The evidence
column is what the committed snapshot now contains (`api:live` green against the
running server), so none of these is a reason to stop. Nothing here says
"Implemented": that claim belongs in the section above, and only once the UI is
wired, the gates pass and the live journey has been run.

| Feature | Route | Contract that closed it | Frontend status |
|---------|-------|-------------------------|-----------------|
| Practice history score | `/spaces/:spaceId/practice/:sessionId` | `GET .../practice-sessions/{sessionId}/result` → `PracticeResultView{sessionId,status,questionCount,answeredCount,correctCount,score,maxScore}` (C06 / A5.3) | Wired — the finished view reads ONLY this endpoint; `finish` keeps nothing, and the two 409 codes (`PRACTICE_SESSION_NOT_SUBMITTED`, `PRACTICE_RESULT_NOT_RECORDED`) each get their own copy instead of a generic read failure |
| Question list paging | `/spaces/:spaceId/questions` | `QuestionPageResponse{items,page,pageSize,totalItems,totalPages}` + `page`/`size` on `GET /questions` (C-Paging) | Wired — the page comes from `?page=`, the pager drives server requests |
| Exam answer resume | `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | `AttemptQuestionView{answer,answered}` on `GET .../exam-attempts/{attemptId}` (C10 / A6.2) | Wired — entry rebuilds every type's saved answer from the server (`orderingAnswer` positions and `matchingAnswer` pairs included), `answered:false` stays unanswered, and a newer local save is never rolled back by a later read |
| Exam attempt history | `/spaces/:spaceId/exams/:examId` | `GET /spaces/{spaceId}/exam-attempts?examId=&status=&page=&size=` → `AttemptPageResponse` (C11 / W07) | Wired — server-paged from `?page=`, rows link back into their own attempt, and a `SUBMITTED` row with no score reads 已交卷，等待评分 with `—` instead of `0 / 0`. Graded vs ungraded can only be told by whether the server sent a number, because `AttemptSummaryView` carries no `gradingStatus` — recorded as an ask, not papered over |
| Knowledge → source deep link | `/spaces/:spaceId/knowledge/:knowledgePointId` | `KnowledgePointSourceResponse.sourceId` / `.sourcePageId` (C02 / A2.1) | Wired — a provenance row links to the exact page with `?block=` only when the server sent both ids; without `sourceId` it stays text, and nothing is inferred from the display label |
| Ask AI with context | `/spaces/:spaceId/ai` | `SendMessageRequest{sourceId,sourcePageId,contentBlockId,knowledgePointId}` (C03 / A3.2) | Wired — header/page/block/knowledge-point entries each attach the narrowest id the server needs (a block fixes its own page server-side), the composer states what is attached and offers 不附带, and a foreign id surfaces the 400 without leaving a half-written turn |
| Citation navigation | `/spaces/:spaceId/ai/:conversationId` | `ContextReference{sourceId,sourcePageId,contentBlockId}` alongside `locator` (C04 / A3.3) | Wired — every citation type in the contract's own `CHECK` list resolves through one `citationHref` against the registered routes, `locator` is printed and never parsed (a test pins that `P12` cannot reach a route), and `WRONG_QUESTION` stays text because the contract gives it no `questionId` |

## Blocked (A-class, pending backend contract)

| Feature | Routes | Blocker |
|---------|--------|---------|
| *(none left in this table)* | | The last two rows left on 2026-10-14: C09b landed (`QuestionAnswerView.correctOrder` / `.matches`, verified live — `GET /questions/{id}` returned `correctOrder:[1,2,3]`), and the Notes follow-ups landed (`archivedAt` on `NoteView`, `POST /notes/{id}/restore`, `archived`/`knowledgePointId`/`sourceId`/`contentBlockId`/`questionId` filters on `GET /notes`, and link/unlink now 404s with a code instead of answering 201 while dropping a foreign id). Both are wiring work again, listed below. |

### Newly unblocked by this delivery (wiring, not a backend gap)

| Feature | Contract that closed it | Frontend status |
|---------|-------------------------|-----------------|
| Notes archive visible + restorable, and entity → notes lists | `NoteView.archivedAt`, `POST /notes/{id}/restore` (204), `GET /notes?archived=&knowledgePointId=&sourceId=&contentBlockId=&questionId=` | **Wired** — 只看已归档 drives the server's own filter through `?archived=1`, and 恢复 appears only on a row the server marked `archivedAt`; a failed restore says so and keeps anti-IDOR copy rather than pretending the row came back. The reverse lists (`knowledgePointId=` etc.) are available on the contract and not yet surfaced as entry points |
| Question editability | PUT description + `409 QUESTION_NOT_EDITABLE` + the `status` enum | **Wired and measured live** — `编辑` is gone for PUBLISHED/ARCHIVED/REJECTED, the edit form is read-only with the code named, and the 409 itself is handled |
| Stored order / pair set on the detail page | `QuestionAnswerView.correctOrder` / `.matches` | **Wired and measured live** — the two "cannot be read back" notices are deleted, with a guard that fails if either returns |

Closed by the 2026-10-14 contract wave (previously in this table): Notes CRUD /
editor / context links (C01), question edit (C05) and archive/restore (C07),
practice per-question review and refresh-resume (C06 half), search `NOTE` hits
(C-Search), and ORDERING / MATCHING authoring + answering + grading on the write
side (C09).

## Measured decisions (A9.5 / audit W18)

Route-level code splitting was measured before deciding, not assumed:

```
npm run build -> electron-vite build
out/renderer/assets/index-*.js   775.91 kB │ gzip: (bundle is read from local app://)
out/main/index.js                 13.93 kB
out/preload/index.js               1.65 kB
build time ~1.5 s, 145 modules
```

One 776 KB renderer chunk is parsed from disk inside the packaged app — there is
no network fetch to hide latency behind, and no route is measurably slow to
reach. `React.lazy`/`Suspense` would add loading states and a new failure mode
to every route for no observed gain, so the checklist item is closed as
**measured, no action**. Re-open it only with a startup or navigation
measurement that shows a regression.

## Verification evidence, 2026-10-14 (branch `feat/fe-001`, after the contract wave)

Real command output at this commit, not a historical claim:

| Gate | Command | Result |
|------|---------|--------|
| Lint | `npm run lint` | 0 errors, 0 warnings |
| Typecheck | `npx tsc --noEmit -p tsconfig.node.json` / `-p tsconfig.web.json` | 0 errors |
| Contract gate | `npm run api:check` | `openapi.json and api.d.ts are in sync` |
| **Live contract gate** | `OPENAPI_URL=http://localhost:8080/v3/api-docs npm run api:live` | `committed snapshot equals http://localhost:8080/v3/api-docs`. **It cannot run right now** — the backend restarted at ~03:2x to publish the `gradingStatus` field and `127.0.0.1:8080` has been refusing since, so the 198-path snapshot below is verified against the last reachable instance, not this minute's. Negative proofs still on file: a fixture with
`timeLimitMinutes` renamed to `durationMinutes` (no path change) → exit 1; a
fixture with `/api/v1/spaces` removed → refused as a non-AIStudy document; and
this round the gate caught a real field addition with an unchanged path set,
reporting it at char 7896 of the canonical forms. `servers[].url` is excluded from
the comparison: springdoc echoes the requested Host there, so `localhost` vs
`127.0.0.1` would make the gate permanently red on identical content. |
| Unit | `npm run test:run` | 73 files / 1078 tests passed |
| Coverage | `npm run test:coverage` | statements 92.22%, branches 87.26%, functions 84.09% |
| Build | `npm run build` | main 13.95 kB + preload 1.65 kB, renderer **929.24 kB** (+ CSS 30.31 kB) |
| Package | `npm run dist:win` (with the two mirror variables) | `dist/AIStudy Setup 0.1.0.exe`, **111,472,382 bytes, built 2026-10-14 02:53 at this commit**. NOT yet installed: R02 (silent install → launch → shortcuts → uninstall) was run against the 01:55 artifact (111,468,074 bytes), whose renderer differs from this one by the three lanes committed since. The build log's `signing with signtool.exe` lines do **not** mean it is signed (see the signing note below). |
| Package | `npm run dist:win` | see "what this does not prove" — the artifact is produced, and installing it is a separate gate |

The previous table (2026-10-13: 65 files / 777 tests, renderer 775.91 kB) is kept
here as the delta, because the audit is written against those numbers: +7 files,
+277 tests, +147 kB of renderer. Since the first 2026-10-14 measurements
(70 files / 975 tests, renderer 889.64 kB) the growth is +79 tests and +34 kB, and
it is all wiring of fields and endpoints the regenerated contract already carried —
attempt history and resume, the practice result read, provenance and citation
navigation, server-driven question paging. No new dependency, no new route family.

Packaging inputs and workspace hygiene (audit A1.3 / risk 15) were checked in the
real checkout rather than in the shared ZIP the audit was written from:
`build/icon.ico` and `build/icon.png` **are tracked** (`git ls-files build/`,
and `git check-ignore` reports neither as ignored), so a clean clone can run
`dist:win` without generating icons first. `dev.log`, `dev-smoke*.log` and
`*.tsbuildinfo` exist on disk but are ignored and untracked, and
`git status --short` is empty at every commit in this pass — nothing stray is
being carried into version control.

What this does **not** prove, and remains open A-class work:

- **R02 install → launch → shortcuts → uninstall: RUN, 2026-10-14, and it passed.**
  Re-run against the final artifact (111,467,968 bytes, built 01:45 at the commit
  whose gate numbers are above): silent per-user install exited 0, the exe was
  present afterwards with `productVersion 0.1.0.0`, and launching it produced a
  window titled `AIStudy` with 4 processes and no crash. An earlier pass on the
  same installer also confirmed both shortcuts are created and the silent
  uninstall removes the directory, both shortcuts and the `HKCU\...\Uninstall`
  entry while **keeping** `%APPDATA%\AIStudy\config.json` — the
  `deleteAppDataOnUninstall: false` policy.
  One finding for whoever runs this by hand: the NSIS uninstaller is
  **asynchronous** — its process returned before anything was deleted, so
  checking the exit code (or the files, seconds later) reads as a failed
  uninstall. Wait for the directory to disappear.
- **Not proven by that run:** that the installed renderer *paints the app*, and
  that a configured backend origin is actually used. Anonymous by design, the app
  issues **zero** requests before login (there is no health probe, per PHASE 7),
  so neither could be observed without credentials — and the packaged build has
  DevTools disabled (ADR-037), so there is no console to read. That is what R03
  still needs: a desktop test account.
- **One limit on the source-read guards:** `backend-vocabulary-contract.test.ts`
  and the ingestion guards read `../server/**.java`, and this workspace's copy of
  that tree is **behind the running instance** — its migrations stop at
  `V057__add_note_context_links.sql` while the server has been migrated to V066,
  and `SendMessageRequest` there is still the single-`content` record the contract
  no longer shows. Those guards therefore prove the frontend is not guessing at
  vocabulary the older code declares; they do **not** prove anything about a field
  or code the newer server added. `lib/exam-error-codes-contract.test.ts` handles
  that explicitly: it pins the exam conflict codes to `ApiErrorCodes.java`, asserts
  every code the copy declares is handled, and holds a documented list of the seven
  shipped codes the copy cannot see — a list that must empty itself when the copy is
  synced, so the exemption cannot rot into a silent pass. Facts that are still only
  in the backend's prose (the two practice-result 409 codes) are recorded as such in
  `BACKEND-CONTRACT-REQUESTS.md` instead of being dressed up as checked constants.
- R03 installed-app auth (login → restart → restore → logout → anonymous
  restart) and the R04/R05 end-to-end journeys are gated on that same account;
  the contract-gated rows in the Blocked table above are gated on backend fields.
- 1054 passing tests cover renderer behaviour against mocked client responses.
  They cannot detect a contract that does not exist, so no E2E PASS is claimed
  from this table (see `docs/SMOKE-INSTRUCTIONS.md`).

## Production security review (R07 / A11.4), 2026-10-13

Checked against the **built** renderer (`npm run build` at this branch), not only
the source:

| Check | Result |
|-------|--------|
| CSP (`src/main/csp.ts`, production) | `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' <api-origin>; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'`. The API origin comes from `resolveApiBaseUrl()` → `apiOriginFromBaseUrl()`, so it is always `scheme://host:port` and can never widen to `*`. `'unsafe-inline'` appears **only** in the dev branch, for Vite's inline preamble. |
| Renderer → provider directly | None. `api.stepfun.com/v1` occurs twice in the bundle as the **AI Settings preset default/placeholder** (`features/ai/AiSettingsPage.tsx:31,191`) — a value the user may save into backend settings, never a fetch target. All traffic goes through `createApiClient(apiConfig.baseUrl, …)`. |
| `localStorage` | 0 occurrences in the bundle and 0 in renderer source; the convention scan rejects `localStorage`/`sessionStorage`/`document.cookie` anywhere under `src/renderer/src` (ADR-026/037). |
| `sessionStorage` (3 bundle hits) | Attributed to `react-router-dom` / `@remix-run/router` internals (34 and 45 occurrences in those packages); none in our source, none in `openapi-fetch`. |
| `Bearer ` | Only the shared client's `Authorization` header builder, fed from the in-memory session, never from storage. |
| `eval(` | 0 in the bundle. |
| `dangerouslySetInnerHTML` | 0 in our source; the bundle count is React DOM's own prop name (`react-dom.production.min.js` contains it 8×). |
| Node / Electron from renderer | Rejected by the convention scan (`from 'node:` / `require(` / `window.require` / `process.` across `src/renderer/src`), and the guard test bites. |
| Secrets in tracked files | `git grep` over `desktop` + `packages` for `sk-…`, private-key blocks, plaintext passwords and long bearer literals: the only hit is the generated `LoginRequest.password` **field name**. `.env`, `.env.local`, `.env.production` are ignored; `.env.example` carries only a sample URL. |
| Code signing (A11.2) | **Not signed, and the build log misleads**: electron-builder prints `• signing with signtool.exe …` although no certificate is configured (`build.win.certificateFile`/`cscLink` absent), so the shipped binaries are unsigned and SmartScreen warns. README now says so and documents injecting `CSC_LINK`/`CSC_KEY_PASSWORD` from outside the repository instead of storing credentials in it. |

Still not covered here, and open: verifying these headers and the network graph
in a running packaged app (DevTools / real requests) needs the backend up, which
is also what R02 install, R03 auth and R04/R05 E2E are waiting on.

## Contract endpoints deliberately not wired (A/B decision, not an oversight)

These exist in `packages/api-client` today and have **no call site** in the
renderer. Master A1–A11 does not ask for them, so under the "only A or B" rule
they are recorded as out of this round rather than built because the endpoint
happens to exist:

| Client method | Why it is not wired |
|---------------|---------------------|
| `studyCoach` | No A item defines a coach surface; the AI tutor routes already cover asking questions. |
| `explainPracticeAnswer` / `explainExamAnswer` | Review already shows the server's stored `explanation` on each answer row (`ExamResultItemView.explanation`, `PracticeAnswerView.explanation`), which is what A5.3/A6.3 ask for. An on-demand AI re-explanation is a different feature. |
| `getExamDiagnosis` | Its summary is a backend-composed English string — `ExamDiagnosisService.java:79` does `setSummary("Exam score " + resultScore + "/" + resultMaxScore)` — which cannot be shown in this UI; the structured-fields request is outstanding. The graded result page does not depend on it. |
| `GET /wrong-questions` | No wrong-question list is in A1–A11; a wrong-question *search hit* still opens its question via `SearchResult.questionId`. |

The 2026-10-14 regeneration (67 → 195 paths) added a second wave of endpoints
that no A-item asks for. They are listed here for the same reason — existing is
not the same as requested:

| Endpoint group | Why it is not wired |
|----------------|---------------------|
| `/spaces/{id}/archive`, `/restore`, `/rename` | A1–A11 asks for space create/list/detail only (A1/A9). Space lifecycle is not in this round. |
| `/knowledge-points/{id}/archive`, `/restore`, `/reject`, `/relations`, `/mastery` | Knowledge curation lifecycle was requested for **questions** (A4.4/G09), not for knowledge points. Symmetry is an argument, not a requirement — see "awaiting an A/B decision" below. |
| `/statistics/*` (9 endpoints) | B12.17 excludes an independent dashboard / recommendation feed. |
| `/study-plan/*`, `/review-tasks/*`, `/mastery/calibration/*`, `/ai/usage`, `/exam-blueprints/*` | No A-item names a study-plan, review-queue, calibration, usage-meter or blueprint surface. |
| `POST /practice-sessions/{id}/questions/{id}/variant` | Variant generation is an AI-produces-new-question feature; A5 asks for answer/progress/resume/finish, not derivation. |
| `/admin/**` (~25 endpoints) | B12.7 — the admin web is a separate project; the desktop client must not become it. |
| `PUT /exams/{examId}`, `POST /exams/{examId}/archive` | A6.1 asks for exam setup / list / detail / start and A6.3 for result / review; nothing in A1–A11 asks to edit or retire a paper. `UpdateExamRequest` exists, but a draft that needs different questions is rebuilt in the create form, which is what the audit describes. These wrappers were added when the contract landed and **removed again** after a call-site check found nothing using them — an endpoint existing is not a requirement. |
| `/auth/me/password`, `/admin/users/{subject}/reset-password` | B12.8 — self-service password reset / registration is excluded. |
| `/sources/{id}/folder-sync/*`, `/versions/compare`, `/outline/*`, `/revisions` | Ingestion authoring surfaces belong to the admin/ingestion lane; A2.3 asks the client to verify upload → pages → blocks for the five supported formats, which it does. |

### Awaiting an A/B decision

Two rows are neither closed as A nor excluded as B, and I am not deciding them
unilaterally:

1. Whether an **image source** should show the uploaded image itself (named in
   neither A1–A11 nor B12.1/B12.3).
2. Whether **knowledge-point archive/restore/reject** should join the question
   curation surface the audit already demands (A4.4), or be excluded alongside
   the other admin-side curation flows.

If the product owner promotes any of these to A, it becomes this round's work —
the row is here so the omission is a decision that can be reversed, not a gap
nobody noticed.

## Out of scope (B-class)

See `AISTUDY-FRONTEND-FINAL-COMPLETION-MASTER-CHECKLIST.md` section 12 for the full B-class list:
- OCR, WebP ingestion, PDF canvas/annotation, rich WYSIWYG notes
- Offline-first / local database
- Real-time collaboration / sharing
- Desktop Admin
- Self-registration / password reset / MFA / SSO
- AI streaming / voice / direct provider attachments
- Drag-drop / multi-file bulk upload
- Tray / native notifications / global shortcuts
- Auto updater
- macOS/Linux release certification
- Dark mode / theme system
- Multi-language / i18n
- Analytics / telemetry / crash SaaS
- Independent Dashboard / recommendation feed
- Bookmark / Favorite / Spaced Repetition
- Import/export/backup tools
