/**
 * Runtime configuration contract (ADR-047).
 *
 * Before this, the backend base URL was baked into BOTH bundles from
 * `VITE_API_BASE_URL` at build time: a packaged app could never be pointed at
 * another server, and because the CSP `connect-src` was derived from the same
 * build-time value the failure mode was silent — the renderer would talk to
 * the remote host while the policy only allowed the origin compiled in.
 *
 * Precedence (highest first), resolved once by the main process and published
 * to the renderer over a read-only IPC channel:
 *
 *   1. `AISTUDY_API_BASE_URL`   operator/CI override, no file writes
 *   2. `<userData>/config.json` `{ "apiBaseUrl": "https://api.example.com" }`
 *   3. build-time `VITE_API_BASE_URL`
 *   4. `DEFAULT_API_BASE_URL`
 *
 * Every candidate still goes through `resolveApiBaseUrl()`, so an invalid
 * value at any layer degrades to the default instead of producing a wildcard
 * CSP source. Only `apiBaseUrl` is honored from the config file: it is not a
 * general-purpose settings bag, and unknown keys are ignored rather than
 * trusted.
 */

export const API_BASE_URL_ENV_VAR = 'AISTUDY_API_BASE_URL';
export const RUNTIME_CONFIG_FILE_NAME = 'config.json';

/** Read-only channel exposing the resolved base URL to the renderer. */
export const APP_CONFIG_CHANNELS = {
  getApiBaseUrl: 'app-config:get-api-base-url',
} as const;

export type AppConfigChannel =
  (typeof APP_CONFIG_CHANNELS)[keyof typeof APP_CONFIG_CHANNELS];

/** True only for a channel this bridge may serve. */
export function isAppConfigChannel(value: unknown): value is AppConfigChannel {
  return value === APP_CONFIG_CHANNELS.getApiBaseUrl;
}

export interface RuntimeConfig {
  apiBaseUrl?: string;
}

/**
 * Parse `<userData>/config.json`. Unparseable JSON, non-object payloads and
 * a missing/blank `apiBaseUrl` all yield `{}` — a broken config file must not
 * brick the app, and it must never smuggle a non-http(s) origin into the CSP.
 */
export function parseRuntimeConfig(raw: string): RuntimeConfig {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return {};
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return {};
  }
  const candidate = (parsed as Record<string, unknown>)['apiBaseUrl'];
  return typeof candidate === 'string' && candidate.trim() !== ''
    ? { apiBaseUrl: candidate }
    : {};
}
/** Read-only config surface preload exposes to the renderer (ADR-047). */
export interface AppConfigBridge {
  getApiBaseUrl: () => Promise<string>;
}
