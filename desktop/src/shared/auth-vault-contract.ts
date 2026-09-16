/**
 * Auth-vault IPC contract (FE-AUTH-001, ADR-026 / ADR-037).
 *
 * The Desktop keeps the short-lived ACCESS token in renderer memory and
 * the long-lived opaque REFRESH token encrypted at rest by the OS via
 * Electron `safeStorage` (main process only). The renderer never sees
 * Node APIs: it talks to the vault through a fixed, minimal IPC surface
 * exposed by the preload contextBridge.
 *
 * This module is the single contract shared by the MAIN process and the
 * PRELOAD script. It must stay free of Node-only imports (Buffer,
 * node:fs, electron, ...) because the sandboxed preload bundles it and
 * can only use `electron` itself.
 */

/** Fixed IPC channel names — preload and main must never drift apart. */
export const AUTH_VAULT_CHANNELS = {
  /** (refreshToken: string) => { ok: true } — encrypt + persist */
  save: 'auth-vault:save',
  /** () => refreshToken: string | null — load + decrypt */
  load: 'auth-vault:load',
  /** () => { ok: true } — remove the persisted blob */
  clear: 'auth-vault:clear',
} as const;

export type AuthVaultChannel =
  (typeof AUTH_VAULT_CHANNELS)[keyof typeof AUTH_VAULT_CHANNELS];

/** Allowlist guard: an inbound channel string must be one of ours. */
export function isAuthVaultChannel(
  value: unknown
): value is AuthVaultChannel {
  return (
    value === AUTH_VAULT_CHANNELS.save ||
    value === AUTH_VAULT_CHANNELS.load ||
    value === AUTH_VAULT_CHANNELS.clear
  );
}

/**
 * Hard upper bound on a refresh token's length. The backend opaque
 * token is far shorter; this only exists so a malformed/crafted IPC
 * payload can never drive an unbounded encryption or disk write.
 */
export const MAX_REFRESH_TOKEN_LENGTH = 4096;

/**
 * Validates the shape of a refresh token crossing the IPC boundary:
 * must be a non-empty (after trim) string within the bounded length.
 * Surrounding whitespace is never significant and is trimmed away.
 */
export function normalizeRefreshToken(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_REFRESH_TOKEN_LENGTH) {
    return null;
  }
  return trimmed;
}
