/**
 * BrowserWindow security baseline (ADR-037).
 *
 * Extracted from `index.ts` so the four flags the README and ADR-037 promise
 * are asserted by a test instead of being re-read by a reviewer every time.
 * A window created from anything else is a security regression, not a style
 * choice.
 */

export interface WebPreferencesLike {
  readonly preload: string;
  readonly contextIsolation: true;
  readonly nodeIntegration: false;
  readonly sandbox: true;
  readonly webSecurity: true;
  readonly devTools: boolean;
}

/**
 * `allowDevTools` is expected to be `!app.isPackaged`: a packaged renderer
 * holds the access token in memory, so DevTools is a read path for it and
 * nothing in the shipped product needs it.
 */
export function buildWebPreferences({
  preloadPath,
  allowDevTools,
}: {
  preloadPath: string;
  allowDevTools: boolean;
}): WebPreferencesLike {
  return {
    preload: preloadPath,
    contextIsolation: true,
    nodeIntegration: false,
    sandbox: true,
    webSecurity: true,
    devTools: allowDevTools,
  };
}
