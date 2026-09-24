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
import type { ApiClient } from '@aistudy/api-client';
import {
  normalizeApiBaseUrl,
  resolveApiBaseUrl,
} from '../../../shared/api-config';
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

/**
 * The backend target. Seeded from the build-time `VITE_API_BASE_URL` and
 * replaced by `configureApiBaseUrl()` with the value main resolved at runtime
 * (ADR-051) — an object so the shell header and the client always read the
 * same live value instead of two snapshots.
 */
export const apiConfig: { baseUrl: string } = {
  baseUrl: resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL),
};

let client: ApiClient | null = null;

/** Lazily built so it always carries the currently configured base URL. */
export function getApiClient(): ApiClient {
  client ??= createApiClient(apiConfig.baseUrl, authSession, {
    fetch: createRefreshingFetch({
      refreshAccessToken: async () => {
        const renewed = await authSession.rotateRefresh(getApiClient());
        return renewed ? authSession.getAccessToken() : null;
      },
    }),
  });
  return client;
}

/**
 * Read the runtime-resolved base URL from the main process before the first
 * request. Without the bridge (unit tests, plain browser) or when main
 * refuses, the build-time value stands — it is what the CSP was built for in
 * that case anyway.
 */
export async function configureApiBaseUrl(): Promise<string> {
  let raw: string | undefined;
  try {
    raw = await window.aistudyConfig?.getApiBaseUrl();
  } catch {
    raw = undefined;
  }
  const resolved = normalizeApiBaseUrl(raw);
  if (resolved && resolved !== apiConfig.baseUrl) {
    apiConfig.baseUrl = resolved;
    client = null;
  }
  return apiConfig.baseUrl;
}
