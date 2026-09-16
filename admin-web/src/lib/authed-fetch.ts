/**
 * Refreshing fetch wrapper (same security contract as the Desktop):
 *
 *   request -> 401 -> single-flight /auth/refresh rotation ->
 *   retry the SAME safe request once with the new bearer.
 *
 *  - /auth/login|refresh|logout are never auto-refreshed.
 *  - Only GET/HEAD/OPTIONS are retried; unsafe methods trigger the
 *    rotation but are never auto-replayed (no duplicate writes).
 */

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
  fetchImpl?: typeof fetch;
  refreshAccessToken: () => Promise<string | null>;
}

export function createRefreshingFetch({
  fetchImpl,
  refreshAccessToken,
}: CreateRefreshingFetchOptions): typeof fetch {
  const baseFetch = fetchImpl ?? fetch;

  return async (input, init) => {
    const first = await baseFetch(input, init);
    if (first.status !== 401) return first;

    const requestUrl =
      typeof input === 'string'
        ? input
        : input instanceof URL
          ? input.toString()
          : input.url;
    if (isAuthEndpointUrl(requestUrl)) return first;

    const newToken = await refreshAccessToken();
    if (!newToken) return first;

    const method = (
      init?.method ??
      (input instanceof Request ? input.method : 'GET')
    ).toUpperCase();
    if (!SAFE_METHODS.has(method)) return first;

    const headers = new Headers(init?.headers);
    if (input instanceof Request) {
      input.headers.forEach((value, key) => headers.set(key, value));
    }
    headers.set('Authorization', `Bearer ${newToken}`);
    return baseFetch(input, { ...init, headers });
  };
}
