/**
 * API client singleton for Admin Web.
 *
 * Base URL is RELATIVE (''): /api/v1/... resolves against the page
 * origin — the Vite dev proxy in development, a reverse proxy in
 * production — so the browser never needs CORS and no host is baked
 * into the bundle. A 401 rotates the in-memory refresh token once.
 */

import { createApiClient } from '@aistudy/api-client';
import { authSession } from './auth-session';
import { createRefreshingFetch } from './authed-fetch';

export { authSession } from './auth-session';
export {
  AuthLoginError,
  ADMIN_ROLE,
  isCompleteTokenPair,
  type AuthSnapshot,
  type AuthUser,
} from './auth-session';

/**
 * VITE_API_BASE_URL may override the relative default (e.g. an absolute
 * API origin). Empty/relative values are passed through unchanged.
 */
const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL ?? '';
const apiBaseUrl =
  typeof configuredBaseUrl === 'string' ? configuredBaseUrl.trim() : '';

export const apiClient = createApiClient(apiBaseUrl, authSession, {
  fetch: createRefreshingFetch({
    refreshAccessToken: async () => {
      const renewed = await authSession.rotateRefresh(apiClient);
      return renewed ? authSession.getAccessToken() : null;
    },
  }),
});
