/**
 * App Shell (FE-001 PHASE F, FE-001.5 PRE-COMMIT REVIEW FIX-01).
 *
 * Desktop workbench layout: header (product + backend state), left nav
 * (Spaces, then current-space Sources/Knowledge), main content outlet,
 * optional compact status footer.
 *
 * The current LearningSpace is derived from the URL (FE-001 §8): the
 * sidebar shows the space name resolved via getLearningSpace(spaceId).
 * No store, no localStorage for the current space.
 *
 * Navigation contract (FIX-01):
 *  - the Spaces link uses `end`, so nested routes like
 *    /spaces/7/sources never keep Spaces active — at most one primary
 *    nav link is active at a time.
 *  - Sources/Knowledge child links render ONLY when the current space
 *    query succeeded with a valid space. While pending or errored they
 *    are hidden (never present links that look navigable but are not).
 *  - space error label: 404 -> "not found or inaccessible"
 *    (authorization-safe), everything else (401/403/network/5xx) ->
 *    neutral "Space unavailable".
 */

import { NavLink, Outlet, useLocation, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../lib/api-context';
import { normalizeApiError, unwrap } from '../lib/api-error';
import { queryKeys } from '../lib/query-keys';
import { parsePositiveIdParam } from '../lib/ids';
import { apiBaseUrl } from '../lib/api-client';
import { DevelopmentSession } from '../features/auth/DevelopmentSession';

function useCurrentSpaceId(): number | null {
  const params = useParams();
  const location = useLocation();
  // Route param wins; fall back to parsing /spaces/:spaceId/... from the URL
  // so the shell can resolve the space name even on nested routes.
  const fromParams = parsePositiveIdParam(params.spaceId);
  if (fromParams !== null) {
    return fromParams;
  }
  const match = location.pathname.match(/^\/spaces\/(\d+)/);
  if (!match) {
    return null;
  }
  return parsePositiveIdParam(match[1]);
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
  // Child navigation requires a SUCCESSFUL query with a valid space —
  // pending/error never renders seemingly-valid child links.
  const spaceAvailable =
    spaceId !== null && spaceQuery.isSuccess && space !== undefined;
  const spaceError = spaceQuery.isError
    ? normalizeApiError(spaceQuery.error)
    : null;
  const spaceLabel = spaceError
    ? spaceError.status === 404
      ? 'Space not found or inaccessible'
      : 'Space unavailable'
    : spaceName ?? 'Space';

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__brand">AIStudy</div>
        <div className="app-shell__api-target" title={apiBaseUrl}>
          API target: {apiBaseUrl}
        </div>
        <div className="app-shell__auth">
          <DevelopmentSession />
        </div>
      </header>

      <div className="app-shell__body">
        <nav className="app-shell__nav" aria-label="Main navigation">
          {/* `end`: Spaces stays active ONLY on the exact /spaces route —
              never on /spaces/:spaceId/... nested routes. */}
          <NavLink
            to="/spaces"
            end
            className={({ isActive }) =>
              isActive ? 'nav-link nav-link--active' : 'nav-link'
            }
          >
            Spaces
          </NavLink>

          {spaceId !== null && (
            <div className="nav-section">
              <span
                className="nav-section__space-name"
                title={spaceName ?? 'Space'}
              >
                {spaceLabel}
              </span>
              {spaceAvailable && (
                <>
                  <NavLink
                    to={`/spaces/${spaceId}/sources`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    Sources
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/knowledge`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    Knowledge
                  </NavLink>
                </>
              )}
            </div>
          )}
        </nav>

        <main className="app-shell__main">
          <Outlet />
        </main>
      </div>

      <footer className="app-shell__footer">
        <span>AIStudy Desktop</span>
      </footer>
    </div>
  );
}
