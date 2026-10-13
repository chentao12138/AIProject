# AIStudy Desktop Route Map

Generated from `src/renderer/src/app/router.tsx`. `src/renderer/src/app/route-contract.test.ts`
fails if a page navigates to a path that is not registered here, so this table
cannot silently drift from the links.

## Public

| Path | Component | Description |
|------|-----------|-------------|
| `/login` | `LoginPage` | Username/password login |

## Authenticated shell (`RequireAuth`)

| Path | Component | Description |
|------|-----------|-------------|
| `/` | `Navigate` | Redirects to `/spaces` |
| `/spaces` | `SpacesPage` | Space list / create |
| `/settings/ai` | `AiSettingsPage` | AI provider settings |

## Space-scoped (`/spaces/:spaceId`)

| Path | Component | Description |
|------|-----------|-------------|
| `/spaces/:spaceId` | `Navigate` | Redirects to `sources` |
| `/spaces/:spaceId/sources` | `SourcesPage` | Source list / create |
| `/spaces/:spaceId/sources/:sourceId` | `SourceWorkbenchPage` | Source reader + workbench |
| `/spaces/:spaceId/knowledge` | `KnowledgePage` | Knowledge catalog |
| `/spaces/:spaceId/knowledge/:knowledgePointId` | `KnowledgePointDetailPage` | Knowledge point detail + provenance |
| `/spaces/:spaceId/ai` | `AiChatPage` | AI tutor (new conversation) |
| `/spaces/:spaceId/ai/:conversationId` | `AiChatPage` | AI tutor (existing conversation) |
| `/spaces/:spaceId/questions` | `QuestionsPage` | Question list / filters |
| `/spaces/:spaceId/questions/new` | `QuestionForm` | Create question |
| `/spaces/:spaceId/questions/:questionId` | `QuestionDetailPage` | Question detail + publish |
| `/spaces/:spaceId/practice` | `PracticePage` | Practice start + history |
| `/spaces/:spaceId/practice/:sessionId` | `PracticeSessionPage` | Active practice session |
| `/spaces/:spaceId/exams` | `ExamsPage` | Exam list + create |
| `/spaces/:spaceId/exams/:examId` | `ExamDetailPage` | Exam paper review + publish + start attempt |
| `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | `ExamAttemptPage` | Active exam attempt + result |
| `/spaces/:spaceId/search` | `SearchPage` | Global/space search |

There is no question *edit* route: the contract has no update verb, so
`QuestionForm` is create-only (see `BACKEND-CONTRACT-REQUESTS.md` A4.5).

## Catch-all

| Path | Component | Description |
|------|-----------|-------------|
| `*` | `NotFoundPage` | 404 fallback |

## Deep-link contract

- Source reader accepts `?page=<sourcePageId>&block=<contentBlockId>` to jump to a specific page and highlight a content block.
- Question list accepts `?status=&questionType=&knowledgePointId=`; the knowledge-point
  filter has a UI control, and arriving from a knowledge point detail page uses the
  same key, so no screen asks the user to type an id.
- Practice start accepts `?knowledgePointId=`, which selects the knowledge point in
  the start form once the server list has loaded; question and knowledge point
  detail pages link to it (W16/A9.2).
- Search results navigate to entity routes using real backend IDs.
- Exam flow: list → `exams/:examId` (review / publish) → `createExamAttempt` →
  `exams/:examId/attempts/:attemptId`. The attempt ID always comes from the
  backend response, never from a guess.
- AI citations navigate to Source / KnowledgePoint / Question routes.

## Out of scope (B-class)

- Notes routes (`/notes`, `/notes/:noteId`) — blocked; no backend contract.
- macOS/Linux specific routes or platform flows.
