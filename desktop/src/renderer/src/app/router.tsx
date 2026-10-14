/**
 * HashRouter — the only safe choice for Electron app:// (FE-001 §C/8).
 * Never depends on server-side history fallback.
 *
 * FE-AUTH-001: /login is a PUBLIC route outside the shell; every other
 * route sits behind RequireAuth (bootstrapping -> anonymous redirect ->
 * authenticated outlet).
 * FE-002A: adds /spaces/:spaceId/sources/:sourceId workbench route.
 */

import { createHashRouter, Navigate } from 'react-router-dom';
import { AppShell } from './AppShell';
import { RequireAuth } from './RequireAuth';
import { SpaceScopeGuard } from './SpaceScopeGuard';
import { NotFoundPage } from './NotFoundPage';
import { LoginPage } from '../features/auth/LoginPage';
import { SpacesPage } from '../features/spaces/SpacesPage';
import { SourcesPage } from '../features/sources/SourcesPage';
import { SourceWorkbenchPage } from '../features/sources/SourceWorkbenchPage';
import { KnowledgePage } from '../features/knowledge/KnowledgePage';
import { KnowledgePointDetailPage } from '../features/knowledge/KnowledgePointDetailPage';
import { NotesPage } from '../features/notes/NotesPage';
import { NoteDetailPage } from '../features/notes/NoteDetailPage';
import { AiSettingsPage } from '../features/ai/AiSettingsPage';
import { AiChatPage } from '../features/ai/AiChatPage';
import { QuestionsPage } from '../features/questions/QuestionsPage';
import { QuestionDetailPage } from '../features/questions/QuestionDetailPage';
import { QuestionEditPage } from '../features/questions/QuestionEditPage';
import { QuestionForm } from '../features/questions/QuestionForm';
import { PracticePage } from '../features/practice/PracticePage';
import { PracticeSessionPage } from '../features/practice/PracticeSessionPage';
import { ExamsPage } from '../features/exams/ExamsPage';
import { ExamDetailPage } from '../features/exams/ExamDetailPage';
import { ExamAttemptPage } from '../features/exams/ExamAttemptPage';
import { SearchPage } from '../features/search/SearchPage';

export const router = createHashRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <AppShell />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/spaces" replace /> },
      { path: 'spaces', element: <SpacesPage /> },
      { path: 'settings/ai', element: <AiSettingsPage /> },
      {
        path: 'spaces/:spaceId',
        element: <SpaceScopeGuard />,
        children: [
          { index: true, element: <Navigate to="sources" replace /> },
          { path: 'sources', element: <SourcesPage /> },
          { path: 'sources/:sourceId', element: <SourceWorkbenchPage /> },
          { path: 'knowledge', element: <KnowledgePage /> },
          {
            path: 'knowledge/:knowledgePointId',
            element: <KnowledgePointDetailPage />,
          },
          { path: 'notes', element: <NotesPage /> },
          { path: 'notes/:noteId', element: <NoteDetailPage /> },
          { path: 'ai', element: <AiChatPage /> },
          { path: 'ai/:conversationId', element: <AiChatPage /> },
          { path: 'questions', element: <QuestionsPage /> },
          { path: 'questions/new', element: <QuestionForm /> },
          { path: 'questions/:questionId', element: <QuestionDetailPage /> },
          { path: 'questions/:questionId/edit', element: <QuestionEditPage /> },
          { path: 'practice', element: <PracticePage /> },
          { path: 'practice/:sessionId', element: <PracticeSessionPage /> },
          { path: 'exams', element: <ExamsPage /> },
          { path: 'exams/:examId', element: <ExamDetailPage /> },
          { path: 'exams/:examId/attempts/:attemptId', element: <ExamAttemptPage /> },
          { path: 'search', element: <SearchPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
