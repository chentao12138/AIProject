/**
 * CSP policies (ELECTRON-CORS-001-A, FE-001.5 PRE-COMMIT REVIEW FIX-01).
 *
 * Pure builder so the exact policy strings are unit-testable. The
 * production policy is set directly on app:// responses by the
 * protocol handler; the dev policy rides on http(s) responses via
 * onHeadersReceived. Dev vs prod are EXPLICITLY distinct — there is
 * no loose meta CSP anywhere.
 *
 * connect-src contract: the API origin comes from the SAME
 * VITE_API_BASE_URL resolution the renderer client uses
 * (src/shared/api-config.ts). Only the ORIGIN (scheme + host + port)
 * is allowed — a base URL path like /api is never pasted into the
 * policy, and an invalid value falls back to the default origin, so
 * connect-src can never widen to '*' or to an unvalidated host.
 */

import {
  apiOriginFromBaseUrl,
  resolveApiBaseUrl,
} from '../shared/api-config';

export interface CspOptions {
  /** electron-vite renderer dev server URL (dev only). */
  devServerUrl?: string;
  /** Raw VITE_API_BASE_URL value; resolved via resolveApiBaseUrl(). */
  apiBaseUrl?: string;
}

export function cspFor(isDev: boolean, options: CspOptions = {}): string {
  const { devServerUrl, apiBaseUrl } = options;
  let devOrigin = 'http://localhost:5173';
  if (isDev && devServerUrl) {
    try {
      devOrigin = new URL(devServerUrl).origin;
    } catch {
      // keep default
    }
  }
  const devWs = devOrigin.replace(/^http/, 'ws');
  // API origin: same normalization contract as the renderer client.
  // resolveApiBaseUrl() already guarantees a valid http(s) base URL
  // (falling back to the default), so the origin is always a plain
  // scheme://host:port — never '*' and never a path.
  const apiOrigin = apiOriginFromBaseUrl(resolveApiBaseUrl(apiBaseUrl));
  const connectSrc = isDev
    ? `'self' ${devOrigin} ${devWs} ${apiOrigin}`
    : `'self' ${apiOrigin}`;
  // Dev: @vitejs/plugin-react injects an inline preamble script, so
  // script-src needs 'unsafe-inline' (documented dev-only relaxation).
  const scriptSrc = isDev ? "'self' 'unsafe-inline'" : "'self'";
  const styleSrc = isDev ? "'self' 'unsafe-inline'" : "'self'";
  return [
    "default-src 'self'",
    `script-src ${scriptSrc}`,
    `style-src ${styleSrc}`,
    // FE-001.5 PHASE 21: no inline style attributes remain (category
    // tree indentation moved to nested-ul CSS padding), so style-src-attr
    // is dropped entirely — prod ships self-only styles.
    "img-src 'self' data:",
    `connect-src ${connectSrc}`,
    "object-src 'none'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
    "form-action 'self'",
  ].join('; ');
}
