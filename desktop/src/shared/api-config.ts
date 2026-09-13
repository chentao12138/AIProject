/**
 * API base URL configuration contract (FE-001.5 PRE-COMMIT REVIEW FIX-01).
 *
 * SINGLE source of truth for the backend base URL, shared by:
 *   - renderer: lib/api-client.ts (createApiClient base URL + header label)
 *   - main:     src/main/csp.ts (connect-src API origin)
 *
 * Both sides resolve the SAME VITE_API_BASE_URL value through
 * resolveApiBaseUrl(), so the CSP always allows exactly the origin the
 * renderer actually talks to (electron-vite exposes VITE_* env vars to
 * the main build too, see electron.vite.config.ts / tsconfig.node.json).
 *
 * Contract:
 *   - only http:/https: backend URLs are accepted
 *   - invalid / missing / non-http(s) values fall back to the default
 *     (never a wildcard, never an unvalidated origin)
 *   - CSP uses ONLY the origin (scheme + host + port) — a base URL path
 *     like /api must never be pasted into connect-src
 */

export const DEFAULT_API_BASE_URL = 'http://localhost:8080';

const HTTP_PROTOCOLS = new Set(['http:', 'https:']);

/**
 * Normalize a raw VITE_API_BASE_URL value into the renderer base URL.
 *
 * - undefined / empty / whitespace-only  -> DEFAULT_API_BASE_URL
 * - non-http(s) or unparsable value      -> DEFAULT_API_BASE_URL (safe
 *   fallback; explicit failure is not needed because the default is
 *   always a valid, CSP-covered origin)
 * - valid http(s) URL                    -> trimmed, trailing slashes
 *   stripped (the path is preserved — it is the API client's job to
 *   append endpoint paths to it; only the CSP drops the path)
 */
export function resolveApiBaseUrl(raw: string | undefined | null): string {
  const trimmed = (raw ?? '').trim();
  if (trimmed === '') {
    return DEFAULT_API_BASE_URL;
  }
  let parsed: URL;
  try {
    parsed = new URL(trimmed);
  } catch {
    return DEFAULT_API_BASE_URL;
  }
  if (!HTTP_PROTOCOLS.has(parsed.protocol)) {
    return DEFAULT_API_BASE_URL;
  }
  // The WHATWG parser is lenient about the authority: 'http:///x'
  // silently becomes host 'x'. Require the canonical scheme://host form
  // (case-insensitive prefix, no extra slash before the host).
  if (!/^https?:\/\/[^/]/i.test(trimmed)) {
    return DEFAULT_API_BASE_URL;
  }
  // URL.parse also accepts hosts like '*' or '' that would be nonsense
  // (or worse, wildcard-ish) in a CSP source — reject them.
  if (parsed.hostname === '' || parsed.hostname.includes('*')) {
    return DEFAULT_API_BASE_URL;
  }
  return trimmed.replace(/\/+$/, '');
}

/**
 * The CSP-safe origin (scheme + host + port) for a normalized base URL.
 *
 * A base URL path (e.g. http://127.0.0.1:9090/api) contributes NOTHING
 * to the CSP origin. Falls back to the default origin when the input
 * cannot be parsed (resolveApiBaseUrl already guarantees validity, so
 * this is defensive only).
 */
export function apiOriginFromBaseUrl(baseUrl: string): string {
  try {
    return new URL(baseUrl).origin;
  } catch {
    return new URL(DEFAULT_API_BASE_URL).origin;
  }
}
