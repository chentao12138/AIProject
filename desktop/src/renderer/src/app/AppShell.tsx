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
 *  - space error label: 404 -> "空间不存在或无权访问"
 *    (authorization-safe), everything else (401/403/network/5xx) ->
 *    neutral "空间暂不可用".
 */

import { NavLink, Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../lib/api-context';
import { normalizeApiError } from '../lib/api-error';
import { spaceQuery } from '../lib/queries';
import { useSpaceId } from '../lib/space-scope';
import { queryKeys } from '../lib/query-keys';
import { apiConfig } from '../lib/api-client';
import { UserMenu } from '../features/auth/UserMenu';

export function AppShell() {
  const api = useApiClient();
  const spaceId = useSpaceId();

  const spaceDetail = useQuery(
    spaceQuery(spaceId, queryKeys.space(spaceId), (id) =>
      api.getLearningSpace(id)
    )
  );

  const space = spaceDetail.data;
  const spaceName = space?.name;
  // Child navigation requires a SUCCESSFUL query with a valid space —
  // pending/error never renders seemingly-valid child links.
  const spaceAvailable =
    spaceId !== null && spaceDetail.isSuccess && space !== undefined;
  const spaceError = spaceDetail.isError
    ? normalizeApiError(spaceDetail.error)
    : null;
  const spaceLabel = spaceError
    ? spaceError.status === 404
      ? '空间不存在或无权访问'
      : '空间暂不可用'
    : spaceName ?? '当前空间';

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__brand">AIStudy</div>
        <div className="app-shell__api-target" title={apiConfig.baseUrl}>
          后端地址：{apiConfig.baseUrl}
        </div>
        <div className="app-shell__auth">
          <UserMenu />
        </div>
      </header>

      <div className="app-shell__body">
        <nav className="app-shell__nav" aria-label="主导航">
          {/* `end`: Spaces stays active ONLY on the exact /spaces route —
              never on /spaces/:spaceId/... nested routes. */}
          <NavLink
            to="/spaces"
            end
            className={({ isActive }) =>
              isActive ? 'nav-link nav-link--active' : 'nav-link'
            }
          >
            学习空间
          </NavLink>

          {spaceId !== null && (
            <div className="nav-section">
              <span
                className="nav-section__space-name"
                title={spaceName ?? '当前空间'}
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
                    学习来源
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/knowledge`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    知识库
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/notes`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    笔记
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/questions`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    题库
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/practice`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    练习
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/exams`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    考试
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/search`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    搜索
                  </NavLink>
                  <NavLink
                    to={`/spaces/${spaceId}/ai`}
                    className={({ isActive }) =>
                      isActive ? 'nav-link nav-link--active' : 'nav-link'
                    }
                  >
                    AI 辅导
                  </NavLink>
                </>
              )}
            </div>
          )}

          <NavLink
            to="/settings/ai"
            className={({ isActive }) =>
              isActive ? 'nav-link nav-link--active' : 'nav-link'
            }
          >
            AI 设置
          </NavLink>
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
