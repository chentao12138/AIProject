/**
 * App Shell (FE-001 PHASE F).
 *
 * Desktop workbench layout: header (product + backend state), left nav
 * (Spaces, then current-space Sources/Knowledge), main content outlet,
 * optional compact status footer.
 *
 * The current LearningSpace is derived from the URL (FE-001 §8): the
 * sidebar shows the space name resolved via getLearningSpace(spaceId).
 * No store, no localStorage for the current space.
 */

import { NavLink, Outlet, useLocation, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../lib/api-context';
import { unwrap } from '../lib/api-error';
import { queryKeys } from '../lib/query-keys';
import { apiBaseUrl } from '../lib/api-client';
import { DevelopmentSession } from '../features/auth/DevelopmentSession';

function useCurrentSpaceId(): number | null {
  const params = useParams();
  const location = useLocation();
  // Route param wins; fall back to parsing /spaces/:spaceId/... from the URL
  // so the shell can resolve the space name even on nested routes.
  const fromParams = Number(params.spaceId);
  if (Number.isFinite(fromParams) && fromParams > 0) {
    return fromParams;
  }
  const match = location.pathname.match(/^\/spaces\/(\d+)/);
  if (!match) {
    return null;
  }
  const parsed = Number(match[1]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

export function AppShell() {
  const api = useApiClient();
  const spaceId = useCurrentSpaceId();

  const spaceQuery = useQuery({
    queryKey: spaceId === null ? ['space', 'none'] : queryKeys.space(spaceId),
    queryFn: () => {
      if (spaceId === null) {
        return Promise.resolve(undefined);
      }
      return api.getLearningSpace(spaceId).then(unwrap);
    },
    enabled: spaceId !== null,
  });

  const space = spaceQuery.data;
  const spaceName = space?.name;
  const spaceUnavailable = spaceId !== null && spaceQuery.isError;

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__brand">AIStudy</div>
        <div className="app-shell__backend" title={apiBaseUrl}>
          <span className="dot" aria-hidden="true" />
          backend {apiBaseUrl}
        </div>
        <div className="app-shell__auth">
          <DevelopmentSession />
        </div>
      </header>

      <div className="app-shell__body">
        <nav className="app-shell__nav" aria-label="Main navigation">
          <NavLink to="/spaces" className={({ isActive }) => (isActive ? 'nav-link nav-link--active' : 'nav-link')}>
            Spaces
          </NavLink>

          {spaceId !== null && (
            <>
              <div className="nav-section">
                <span className="nav-section__space-name" title={spaceName ?? 'Space'}>
                  {spaceUnavailable
                    ? 'Space not found or inaccessible'
                    : spaceName ?? 'Space'}
                </span>
                <NavLink
                  to={`/spaces/${spaceId}/sources`}
                  className={({ isActive }) => (isActive ? 'nav-link nav-link--active' : 'nav-link')}
                >
                  Sources
                </NavLink>
                <NavLink
                  to={`/spaces/${spaceId}/knowledge`}
                  className={({ isActive }) => (isActive ? 'nav-link nav-link--active' : 'nav-link')}
                >
                  Knowledge
                </NavLink>
              </div>
            </>
          )}
        </nav>

        <main className="app-shell__main">
          <Outlet />
        </main>
      </div>

      <footer className="app-shell__footer">
        <span>FE-001 desktop foundation</span>
      </footer>
    </div>
  );
}
