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
| *(no backend blocker left)* | | The exam ORDERING blocker (B1) is **fixed and measured live** in `dbf4c28` / tag `backend-2026-10-14-e04e07-b1`: the answer now echoes `orderingAnswer:[1,2,3]`, the mixed paper submits and lands `PARTIALLY_GRADED` with the objective-only `3 / 8`, the grade call answers 200 (it used to 404 for every answer) moving the row to `GRADED 8 / 8`, and re-grading answers 200 again at `7` instead of 500. Root cause as the backend reported it: the exam path used a 3-arg `buildAnswerPayloadJson` overload that had no slot for `orderingAnswer` / `matchingAnswer`, so the write dropped them while practice (5-arg) worked — which is exactly the asymmetry the two comparison runs showed. What remains is ours: note entry points for source / block / question, and running the packaged app inside this window. |

### Newly unblocked by this delivery (wiring, not a backend gap)

| Feature | Contract that closed it | Frontend status |
|---------|-------------------------|-----------------|
| Admin grading (E-04) and AI grading suggestion (E-07) | the three `ROLE_ADMIN` paths under `/admin/exams/{examId}/attempts/{attemptId}/…` | Deliberately **not** wired here: the desktop has no admin surface and none in the audit — these belong to `admin-web`. Their codes (`EXAM_GRADING_INCOMPLETE`, `EXAM_ANSWER_NOT_SUBJECTIVE`, `EXAM_ANSWER_NOT_FOUND`, `EXAM_GRADE_SCORE_OUT_OF_RANGE`, `AI_GRADING_SUGGESTION_NOT_AVAILABLE`) are therefore also absent from `features/exams/exam-error.ts`, because branching on a code a learner route can never return is dead code. Answers given to the backend: no paging needed on the grading queue (the paper caps at 100 items by contract), the queue **should** carry `referenceAnswer`/`explanation` as explicit admin-only fields or a grader is guessing, and the AI suggestion belongs on the individual answer with no batch endpoint. **New ask from this delivery:** `ExamResultView` has no `gradingStatus`, so a learner opening a `PARTIALLY_GRADED` attempt gets a partial number with nothing saying it is partial — the history row can say it, the result page cannot. |
| Attempt expiry on the history row | `AttemptSummaryView.deadlineAt` ("startedAt + exam timeLimitMinutes, null before the attempt has started") | **Wired** — an in-progress row whose own deadline passed reads 已超过截止时间 and is no longer offered as 继续作答; a missing or unparsable deadline is treated as **no** expiry, so untimed papers keep their affordance. Shares `deadlineTime()` with the countdown so the list and the answering page cannot disagree |
| A provisional score on the result page | `ExamResultView.gradingStatus` + `pendingGradeCount` | **Wired** — `PARTIALLY_GRADED` or `pendingGradeCount > 0` renders 当前为部分成绩：N 题仍待人工评分 and labels the number 目前得分; `GRADED` says plain 得分. The client predicts nothing about when grading finishes |
| Notes archive visible + restorable, and entity → notes lists | `NoteView.archivedAt`, `POST /notes/{id}/restore` (204), `GET /notes?archived=&knowledgePointId=&sourceId=&contentBlockId=&questionId=` | **Wired and measured live** — `?archived=true` returned the archived row with `archivedAt` while the default list still hid it, and `POST /notes/{id}/restore` returned 204 with the note back in the default list. The reverse lookup is wired on the knowledge-point page through `?knowledgePointId=` (a failed read stays distinct from an empty one); the source / block / question entry points use the same filter and are still to be added |
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
| **Live contract gate** | `OPENAPI_URL=http://localhost:8081/v3/api-docs npm run api:live` | `committed snapshot equals http://localhost:8081/v3/api-docs` after regenerating to **201 paths** (the three admin grading endpoints). The port moved again within one day, which is why the command always pins a URL and the gate always checks the `/api/v1/spaces` + `/api/v1/auth/login` anchors rather than trusting a remembered port. Negative proofs on file: a fixture with `timeLimitMinutes` renamed to `durationMinutes` (no path change) → exit 1; a fixture with `/api/v1/spaces` removed → refused as a non-AIStudy document; and twice this round the gate caught a real change with an unchanged path set, reporting the first differing character (7896 for `gradingStatus`). `servers[].url` is excluded — springdoc echoes the requested Host there, so `localhost` vs `127.0.0.1` would make the gate permanently red on identical content. |
| Unit | `npm run test:run` | 73 files / 1078 tests passed |
| Coverage | `npm run test:coverage` | statements 92.22%, branches 87.26%, functions 84.09% |
| Build | `npm run build` | main 13.95 kB + preload 1.65 kB, renderer **924.53 kB** (+ CSS 30.31 kB) |
| Package | `npm run dist:win`, then `scripts/r02-installed-app.ps1` | **111,474,655 bytes, rebuilt at `ac8ec53`, R02 cycle PASS and R03/R04/R05 measured on it.** Silent per-user install exit 0; installed `AIStudy.exe` `productVersion 0.1.0.0`; launching it produced 4 processes and a window titled `AIStudy`; the silent uninstall removed the install directory and the HKCU `Uninstall` entry while keeping the `%APPDATA%\AIStudy` directory (the `deleteAppDataOnUninstall: false` policy). The script waits for the directory to disappear because the NSIS uninstaller returns before it has finished. R03 evidence: 18 rows in `docs/SMOKE-INSTRUCTIONS.md`. |
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
- **Proven afterwards, on the rebuilt artifact:** that the installed renderer
  paints the app and that a configured backend origin is really used. Anonymous
  by design, the app issues **zero** requests before login, and the packaged build
  has DevTools disabled (ADR-037) — so this needed Electron's
  `--remote-debugging-port`, not a console. `scripts/r03-installed-app.mjs` (10/10)
  and `scripts/r03-auth-lifecycle.mjs` (8/8) measured it, and the first run found a
  real defect: `app.getPath('userData')` came from package.json `name`
  (`aistudy-desktop`) while `productName` and ADR-051 say `AIStudy`, so the
  packaged app never read `config.json`, answered the build default, and the CSP
  derived from that blocked the configured origin. Fixed at `21ac56e`.
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
- R03 installed-app auth (login → restart → restore → offline → logout →
  anonymous restart) is **measured on the installed build**: 18 rows across the two
  R03 scripts, re-run on the current artifact.
- R04/R05 are measured by `scripts/r04-r05-installed-journey.mjs`: **23/23 with 2
  skipped**. Paging is proven against a real second page (21 questions, fixture
  created and archived by the script itself), URL replay, back/forward, the 480px
  width, an unknown id, offline + recovery and logout all pass; the sources and
  knowledge rows pass on their components' empty states because space 29 has no
  sources or points. The two skipped rows are the source workbench and KP
  provenance — same reason — and their API equivalents are the 34/34 live journey,
  which is a weaker claim and recorded as such.
- Still open inside R03/R05: 主体切换不串缓存, the 401 race, duplicate-submit and
  keyboard-only traversal. The second account (`learner`, space 30) has now been
  issued, so the first two are unblocked rather than blocked.
- 1054 passing tests cover renderer behaviour against mocked client responses.
  They cannot detect a contract that does not exist, so no E2E PASS is claimed
  from this table (see `docs/SMOKE-INSTRUCTIONS.md`).

## Session credential committed to git history (2026-10-14, found after the backend delivery)

Commit `917a14f` (a Qoder `[Checkout-checkpoint]` snapshot) contains `login.json` with a
real `accessToken` (264 chars) and `refreshToken` for the ADMIN+USER `dev` subject, plus
`spec-live.json`. It is reachable from `main` and `batch-c-frontend-handoff`, **and both are
already pushed to origin** — so the token must be treated as leaked, not merely misplaced.
`feat/fe-001` does not contain it (verified with `merge-base --is-ancestor`), and the backend
tag `backend-2026-10-14-e04e07-b1` sits on a commit that does.

Order of operations, and why the first one is not optional: (1) invalidate the sessions —
delete that subject's `auth_refresh_session` rows or rotate `AUTH_JWT_SECRET`, which
invalidates every issued token; deleting the file without this leaves a valid 43-char refresh
token in a public-adjacent history. (2) Then, and only then, rewrite `main` / the batch
branch and re-create the tag. (3) `login.json` and `spec-live.json` are now in `.gitignore`
at the repo root, because the mechanism was an automatic checkpoint of an untracked file
rather than a deliberate `git add` — ignoring the name is what stops the next one.

**Steps 1 and 2 are now done (2026-10-16), on the user's authorization.** Step 1 was
performed and *measured* by the backend, not assumed: they hashed the leaked plaintext
refresh token and looked it up (bare, `hex:`-prefixed, partial-32) in every schema the server
can reach — `aistudy_flyway_test` (1 account, 3 sessions, all issued today for `dev`),
`aistudy_flyway_spike` (empty), `aistudy_spike` (no `user_account` table) — with **zero**
hits, and the access token's `exp` (1791910236) had passed on 2026-10-14. The pair is inert,
killed incidentally by the IT wipe rather than by an act of revocation, and
`AUTH_JWT_SECRET` was deliberately **not** rotated because that needs a restart and would
have cut the live `dev` session. Their stated caveat stands: inertness is proven only for
schemas inside that container, so a pre-2026-10-15 DB dump elsewhere could still replay it.

Step 2, split by ref so nobody's checked-out base moves under them: this session rewrote
`main` with `git filter-branch --index-filter 'git rm --cached --ignore-unmatch login.json
spec-live.json'` — `d01892a` → **`2252314`**, 231 commits on both sides, and
`git diff --name-status d01892a 2252314` is exactly two `D` lines and nothing else — then
pushed it with `--force-with-lease=refs/heads/main:d01892a…` and re-read the remote:
`origin/main = 2252314`, tree contains neither file. `refs/original/refs/heads/main` (the
filter-branch backup pointing at the dirty history) has been deleted. The pre-cleanup bundle
is not present on any searched path (`D:\`, both worktree roots, the user profile to depth 3,
`%LOCALAPPDATA%\Temp`); if it turns up, it holds the token plaintext and counts as a secret.
`batch-c-frontend-handoff` is rewritten and pushed by the backend themselves (their tip is
`58245ae`; origin still shows `37ed6c4` until they publish), and the tag
`backend-2026-10-14-e04e07-b1` is **held** — it was never pushed (`git ls-remote --tags
origin` is empty), and they will re-create it once both rewrites are out, after which the two
sides exchange final SHAs instead of each moving it.

**Closed afterwards, and re-checked from this side:** the backend rewrote and pushed
`batch-c-frontend-handoff` → `380748b` and published the tag on that tip; re-reading the
remote, `origin/main`, `origin/batch-c-frontend-handoff`, `origin/feat/fe-001` and
`backend-2026-10-14-e04e07-b1` all have neither file in their tree. The old objects are
still unreachable-but-present in the shared store until the backend runs
`gc --prune=now`, which it has offered to do once this session says it is finished.
Nothing in this repository writes credentials to disk any more: `scripts/live-journey.mjs`
and both R03 scripts read `AISTUDY_USER` / `AISTUDY_PASS` from the environment and keep the
bearer token in memory only.

---
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
