/**
 * HashRouter — the only safe choice for Electron file:// (FE-001 §C/8).
 * Never depends on server-side history fallback.
 */

import { createHashRouter, Navigate } from 'react-router-dom';
import { AppShell } from './AppShell';
import { SpaceScopeGuard } from './SpaceScopeGuard';
import { NotFoundPage } from './NotFoundPage';
import { SpacesPage } from '../features/spaces/SpacesPage';
import { SourcesPage } from '../features/sources/SourcesPage';
import { KnowledgePage } from '../features/knowledge/KnowledgePage';
import { KnowledgePointDetailPage } from '../features/knowledge/KnowledgePointDetailPage';

export const router = createHashRouter([
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/spaces" replace /> },
      { path: 'spaces', element: <SpacesPage /> },
      {
        // Space scope guard: child resource queries (sources,
        // knowledge, knowledge points) only run after the space itself
        // is confirmed accessible.
        path: 'spaces/:spaceId',
        element: <SpaceScopeGuard />,
        children: [
          { index: true, element: <Navigate to="sources" replace /> },
          { path: 'sources', element: <SourcesPage /> },
          { path: 'knowledge', element: <KnowledgePage /> },
          {
            path: 'knowledge/:knowledgePointId',
            element: <KnowledgePointDetailPage />,
          },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
