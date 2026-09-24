/**
 * Refreshing fetch wrapper (FE-AUTH-001, ADR-026).
 *
 * Wraps the shared api-client's fetch so an expired in-memory ACCESS
 * token is transparent for reads:
 *
 *   request -> 401 -> single-flight /auth/refresh rotation ->
 *   retry the SAME request once with the new bearer.
 *
 * Deliberate boundaries:
 *  - The auth endpoints themselves (/auth/login, /auth/refresh,
 *    /auth/logout) are never refreshed — their 401 means bad
 *    credentials / dead refresh family and must propagate.
 *  - Only SAFE methods (GET/HEAD/OPTIONS) are retried. A mutation
 *    (POST/PUT/PATCH/DELETE) that 401s still TRIGGERS the rotation so
 *    subsequent calls carry a fresh token, but it is never auto-replayed
 *    (no duplicate writes; matches the "mutations never auto-replay"
 *    policy — the user/UI retries explicitly).
 *  - At most one retry: a second 401 is returned to the caller.
 */

import { NetworkUnavailableError } from './api-error';

const AUTH_PATH_SUFFIXES = ['/auth/login', '/auth/refresh', '/auth/logout'];
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export function isAuthEndpointUrl(url: string): boolean {
  try {
    const pathname = new URL(url, 'http://placeholder.local').pathname;
    return AUTH_PATH_SUFFIXES.some((suffix) => pathname.endsWith(suffix));
  } catch {
    return false;
  }
}

interface CreateRefreshingFetchOptions {
  /** Underlying fetch (global fetch by default). */
  fetchImpl?: typeof fetch;
  /** Rotate the refresh token; resolve with the new access token or null. */
  refreshAccessToken: () => Promise<string | null>;
}

export function createRefreshingFetch({
  fetchImpl,
  refreshAccessToken,
}: CreateRefreshingFetchOptions): typeof fetch {
  const baseFetch = fetchImpl ?? fetch;

  /**
   * The single place a genuine transport failure can surface. Tagging it here
   * is what lets `normalizeApiError` tell "backend unreachable" apart from a
   * TypeError raised by the call site itself.
   */
  const transport = ((input, init) =>
    baseFetch(input, init).catch((error: unknown) => {
      throw new NetworkUnavailableError(error);
    })) as typeof fetch;

  return async (input, init) => {
    const first = await transport(input, init);
    if (first.status !== 401) {
      return first;
    }

    const requestUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    if (isAuthEndpointUrl(requestUrl)) {
      return first;
    }

    // Rotate regardless of method: unsafe methods still benefit from the
    // refreshed token for the caller's next attempt.
    const newToken = await refreshAccessToken();
    if (!newToken) {
      return first;
    }

    const method = (
      init?.method ??
      (input instanceof Request ? input.method : 'GET')
    ).toUpperCase();
    if (!SAFE_METHODS.has(method)) {
      return first;
    }

    const headers = new Headers(init?.headers);
    if (input instanceof Request) {
      input.headers.forEach((value, key) => headers.set(key, value));
    }
    headers.set('Authorization', `Bearer ${newToken}`);
    return transport(input, { ...init, headers });
  };
}
