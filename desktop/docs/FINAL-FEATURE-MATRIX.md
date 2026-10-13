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
| Unit | `npm run test:run` | 63 files / 755 tests passed |
| Coverage | `npm run test:coverage` | statements 89.89% (7241/8055), branches 84.38%, functions 81.5% |
| Build | `npm run build` | main 13.93 kB, preload 1.65 kB, renderer 775.91 kB |
| Package | `npm run dist:win` (with the two mirror variables in README) | `dist/AIStudy Setup 0.1.0.exe`, 111 MB, timestamp matches the built commit |

What this does **not** prove, and remains open A-class work:

- The installer was produced, not installed. R02/A11.1 (install → launch →
  shortcut → uninstall → user-data policy) is unverified.
- R03 installed-app auth (login → restart → restore → logout → anonymous
  restart) needs a running backend plus a test account, as do the R04/R05
  end-to-end journeys; every contract-gated row above is still gated.
- 755 passing tests cover renderer behaviour against mocked client responses.
  They cannot detect a contract that does not exist, so no E2E PASS is claimed
  from this table (see `docs/SMOKE-INSTRUCTIONS.md`).

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
