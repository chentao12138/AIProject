# AIStudy Desktop Final Feature Matrix

## Implemented (A-class)

| Feature | Routes | Status |
|---------|--------|--------|
| Spaces | `/spaces` | Implemented |
| Sources | `/spaces/:spaceId/sources`, `/spaces/:spaceId/sources/:sourceId` | Implemented |
| Knowledge | `/spaces/:spaceId/knowledge`, `/spaces/:spaceId/knowledge/:knowledgePointId` | Implemented |
| AI Tutor | `/spaces/:spaceId/ai`, `/spaces/:spaceId/ai/:conversationId` | Implemented |
| Questions | `/spaces/:spaceId/questions`, `/spaces/:spaceId/questions/new`, `/spaces/:spaceId/questions/:questionId` | Implemented (create + detail + publish; there is no edit route, see Blocked) |
| Practice | `/spaces/:spaceId/practice`, `/spaces/:spaceId/practice/:sessionId` | Implemented |
| Exams | `/spaces/:spaceId/exams`, `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | Implemented |
| Search | `/spaces/:spaceId/search` | Implemented |
| Auth | `/login` | Implemented |
| AI Settings | `/settings/ai` | Implemented |

## Blocked (A-class, pending backend contract)

| Feature | Routes | Blocker |
|---------|--------|---------|
| Notes | `/spaces/:spaceId/notes`, `/spaces/:spaceId/notes/:noteId` | No Notes endpoints in `api.d.ts`. See `BACKEND-CONTRACT-REQUESTS.md`. |
| Question edit | *(no route — `route-contract.test.ts` rejects unreachable routes)* | No `PUT`/`PATCH /questions/{questionId}`; POST would duplicate the question. |
| AI context chips | N/A (request body extension) | `SendMessageRequest` lacks `sourceId`/`sourcePageId`/`contentBlockId`/`knowledgePointId`. |
| Full provenance nav | `/spaces/:spaceId/knowledge/:knowledgePointId` | `KnowledgePointSourceResponse` lacks `sourceId`/`sourcePageId`. |
| AI citation deep link | `/spaces/:spaceId/ai/:conversationId` | `ContextReference.locator` is free text; no page/block ids, so `CONTENT_BLOCK` cannot navigate. |
| Practice result history | `/spaces/:spaceId/practice/:sessionId` | `PracticeSessionDetail` has no score fields and no per-question result; only the `finish` response carries them. |
| Exam answer resume | `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | `ExamAnswerView` returns no answer content, so a reload cannot rebuild saved answers. |
| Question paging | `/spaces/:spaceId/questions` | `GET /questions` accepts no `page`/`size`; client-side paging over a full fetch is not allowed. |
| Search → Note hit | `/spaces/:spaceId/search` | `SearchResult.type` enum has no `NOTE`. |
| ORDERING / MATCHING authoring | `/spaces/:spaceId/questions/new` | `AnswerPayloadView`/`QuestionAnswerView` cannot express an ordered answer or pairs. |

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

## Verification evidence, 2026-10-13 (branch `feat/fe-001`, after merging `main`)
Real command output, not a historical claim:

| Gate | Command | Result |
|------|---------|--------|
| Lint | `npm run lint` | 0 errors, 0 warnings |
| Typecheck | `npx tsc --noEmit -p tsconfig.node.json` / `-p tsconfig.web.json` | 0 errors |
| Contract gate | `npm run api:check` | `openapi.json and api.d.ts are in sync` |
| Unit | `npm run test:run` | 65 files / 772 tests passed |
| Coverage | `npm run test:coverage` | statements 90% (7275/8083), branches 84.4%, functions 81.34% |
| Build | `npm run build` | main 13.93 kB, preload 1.65 kB, renderer 775.91 kB |
| Package | `npm run dist:win` (with the two mirror variables in README) | `dist/AIStudy Setup 0.1.0.exe`, 111 MB, timestamp matches the built commit |

Packaging inputs and workspace hygiene (audit A1.3 / risk 15) were checked in the
real checkout rather than in the shared ZIP the audit was written from:
`build/icon.ico` and `build/icon.png` **are tracked** (`git ls-files build/`,
and `git check-ignore` reports neither as ignored), so a clean clone can run
`dist:win` without generating icons first. `dev.log`, `dev-smoke*.log` and
`*.tsbuildinfo` exist on disk but are ignored and untracked, and
`git status --short` is empty at every commit in this pass — nothing stray is
being carried into version control.

What this does **not** prove, and remains open A-class work:

- The installer was produced, not installed. R02/A11.1 (install → launch →
  shortcut → uninstall → user-data policy) is unverified.
- **What has been launched:** `dist/win-unpacked/AIStudy.exe` (the unpacked
  build, not the installed app) starts, creates the `AIStudy` window, and the
  renderer paints. With the backend down it lands on the documented bootstrap
  failure state — "无法连接到后端服务。请确认服务已启动后重试。" with a 重试
  action and a "用其他账号登录" escape — rather than a blank window or a crash,
  which also exercises the `app://` load path and the production CSP.
  This is not the R02 pass criterion: the NSIS install/uninstall cycle and the
  signed-in journeys are still unrun.
- R03 installed-app auth (login → restart → restore → logout → anonymous
  restart) needs a running backend plus a test account, as do the R04/R05
  end-to-end journeys; every contract-gated row above is still gated.
- 755 passing tests cover renderer behaviour against mocked client responses.
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
