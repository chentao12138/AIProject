/**
 * API client singleton for the Desktop renderer (FE-AUTH-001).
 *
 * Renderer code must ONLY reach the backend through the shared
 * @aistudy/api-client. No business component ever calls
 * fetch/axios/XHR directly.
 *
 * Token strategy (ADR-026):
 *  - The ACCESS token lives in renderer memory inside `authSession`.
 *  - A 401 on a read transparently rotates the (safeStorage-persisted)
 *    refresh token once via the refreshing-fetch wrapper.
 *  - Nothing is written to localStorage/sessionStorage/cookies.
 */

import { createApiClient } from '@aistudy/api-client';
import { resolveApiBaseUrl } from '../../../shared/api-config';
import { authSession } from './auth-session';
import { createRefreshingFetch } from './authed-fetch';

export { authSession } from './auth-session';
export {
  AuthLoginError,
  SessionExpiredError,
  isCompleteTokenPair,
  type AuthSnapshot,
  type AuthUser,
} from './auth-session';

export const apiBaseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

export const apiClient = createApiClient(apiBaseUrl, authSession, {
  fetch: createRefreshingFetch({
    refreshAccessToken: async () => {
      const renewed = await authSession.rotateRefresh(apiClientLazy());
      return renewed ? authSession.getAccessToken() : null;
    },
  }),
});

/**
 * Breaks the constructor cycle: the refresh callback is only invoked
 * after a network response, by which point the singleton is assigned.
 */
function apiClientLazy(): typeof apiClient {
  return apiClient;
}
