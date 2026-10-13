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
