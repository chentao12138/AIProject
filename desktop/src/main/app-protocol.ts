/**
 * Custom `app://` protocol helpers (ELECTRON-CORS-001-A).
 *
 * The production renderer runs on a NON-OPAQUE, project-fixed custom
 * origin `app://aistudy` instead of `file://` (Origin:null). This file
 * holds the pure, Electron-free pieces so they are unit-testable:
 *
 *  - origin/navigation allowlist (isAllowedAppNavigation)
 *  - app:// URL -> renderer file path mapping (resolveAppUrlPath),
 *    hardened against traversal / encoded traversal / root escape
 */

import { join, resolve, sep } from 'node:path';

export const APP_SCHEME = 'app';
export const APP_HOST = 'aistudy';
export const APP_ORIGIN = 'app://aistudy';
export const APP_INDEX_DOCUMENT = 'index.html';

/** Single source of truth for the packaged renderer output directory. */
export function rendererRootPath(): string {
  // main bundle lives in out/main; renderer output is out/renderer
  return join(__dirname, '../renderer');
}

/**
 * true only for the application's own custom origin.
 * Hash fragments (in-app HashRouter) are ignored:
 *   app://aistudy/#/spaces  -> allowed
 *   app://evil/             -> denied
 *   file:///...             -> denied
 *   http(s)://...           -> denied
 */
export function isAllowedAppNavigation(targetUrl: string): boolean {
  try {
    const target = new URL(targetUrl);
    return target.protocol === `${APP_SCHEME}:` && target.host === APP_HOST;
  } catch {
    return false;
  }
}

/**
 * Map an app:// URL to a file path inside the renderer root.
 *
 * Returns null for: non-app schemes, foreign hosts, malformed
 * percent-encoding, and any path that resolves OUTSIDE rendererRoot
 * (raw `../`, encoded `%2e%2e`, absolute escapes).
 *
 * `app://aistudy/` and `app://aistudy` both map to <root>/index.html.
 */
export function resolveAppUrlPath(
  appUrl: string,
  rendererRoot: string
): string | null {
  let url: URL;
  try {
    url = new URL(appUrl);
  } catch {
    return null;
  }
  if (url.protocol !== `${APP_SCHEME}:` || url.host !== APP_HOST) {
    return null;
  }

  let decoded: string;
  try {
    decoded = decodeURIComponent(url.pathname);
  } catch {
    return null; // malformed percent-encoding
  }
  if (decoded === '' || decoded === '/') {
    decoded = `/${APP_INDEX_DOCUMENT}`;
  }
  if (!decoded.startsWith('/')) {
    return null;
  }

  const candidate = resolve(rendererRoot, `.${decoded}`);
  const rootPrefix = rendererRoot.endsWith(sep)
    ? rendererRoot
    : rendererRoot + sep;
  if (candidate !== rendererRoot && !candidate.startsWith(rootPrefix)) {
    return null; // escaped the renderer root
  }
  return candidate;
}
