# AIStudy Desktop Route Map

Generated from `src/renderer/src/app/router.tsx`.

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
| `/spaces/:spaceId/questions/:questionId/edit` | `QuestionForm` | Edit question |
| `/spaces/:spaceId/practice` | `PracticePage` | Practice start + history |
| `/spaces/:spaceId/practice/:sessionId` | `PracticeSessionPage` | Active practice session |
| `/spaces/:spaceId/exams` | `ExamsPage` | Exam list + create |
| `/spaces/:spaceId/exams/:examId/attempts/:attemptId` | `ExamAttemptPage` | Active exam attempt + result |
| `/spaces/:spaceId/search` | `SearchPage` | Global/space search |

## Catch-all

| Path | Component | Description |
|------|-----------|-------------|
| `*` | `NotFoundPage` | 404 fallback |

## Deep-link contract

- Source reader accepts `?page=<sourcePageId>&block=<contentBlockId>` to jump to a specific page and highlight a content block.
- Search results navigate to entity routes using real backend IDs.
- AI citations navigate to Source / KnowledgePoint / Question routes.

## Out of scope (B-class)

- Notes routes (`/notes`, `/notes/:noteId`) — blocked; no backend contract.
- macOS/Linux specific routes or platform flows.
