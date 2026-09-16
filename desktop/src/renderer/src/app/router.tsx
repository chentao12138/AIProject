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
import { AiSettingsPage } from '../features/ai/AiSettingsPage';
import { AiChatPage } from '../features/ai/AiChatPage';

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
          { path: 'ai', element: <AiChatPage /> },
          { path: 'ai/:conversationId', element: <AiChatPage /> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
