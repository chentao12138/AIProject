/**
 * Renderer-side wrapper around the preload auth vault (FE-AUTH-001).
 *
 * The renderer NEVER persists the refresh token itself: it goes through
 * window.aistudyAuthVault, which routes to the main-process safeStorage
 * vault. This wrapper exists so:
 *  - callers don't reach for `window.aistudyAuthVault` directly;
 *  - non-Electron environments (jsdom tests, accidental misuse) degrade
 *    safely instead of throwing at call sites.
 */

function bridge(): Window['aistudyAuthVault'] | undefined {
  return typeof window !== 'undefined'
    ? window.aistudyAuthVault
    : undefined;
}

/** Persist the refresh token (encrypted at rest by the OS). */
export async function saveStoredRefreshToken(
  refreshToken: string
): Promise<void> {
  const api = bridge();
  if (!api) {
    return;
  }
  await api.save(refreshToken);
}

/** Load the persisted refresh token; null when absent / unavailable. */
export async function loadStoredRefreshToken(): Promise<string | null> {
  const api = bridge();
  if (!api) {
    return null;
  }
  return api.load();
}

/** Remove the persisted refresh token. */
export async function clearStoredRefreshToken(): Promise<void> {
  const api = bridge();
  if (!api) {
    return;
  }
  await api.clear();
}
