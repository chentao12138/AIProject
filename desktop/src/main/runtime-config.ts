/**
 * Runtime API base URL resolution (ADR-051).
 *
 * Precedence: `AISTUDY_API_BASE_URL` env → `<userData>/config.json` →
 * build-time `VITE_API_BASE_URL` → hard default. Main resolves this ONCE and
 * is the only authority: the CSP `connect-src` is derived from the same value
 * and the renderer reads it over a read-only IPC channel, so the policy and
 * the traffic can never disagree (that disagreement was silent before).
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import {
  DEFAULT_API_BASE_URL,
  normalizeApiBaseUrl,
} from '../shared/api-config';
import {
  API_BASE_URL_ENV_VAR,
  RUNTIME_CONFIG_FILE_NAME,
  parseRuntimeConfig,
} from '../shared/runtime-config';

export type ApiBaseUrlSource =
  | 'env'
  | 'config-file'
  | 'build-default'
  | 'hard-default';

export interface ResolvedApiBaseUrl {
  url: string;
  source: ApiBaseUrlSource;
}

export interface RuntimeApiBaseUrlDeps {
  /** app.getPath('userData') */
  userDataPath: () => string;
  /** build-time VITE_API_BASE_URL (may be undefined in a clean checkout) */
  buildDefault?: string | null;
  /** injected for tests */
  readEnv?: (name: string) => string | undefined;
  readFile?: (path: string) => string;
}

/**
 * The directory name user data lives under, and the app name Electron derives
 * that path from.
 *
 * `app.getPath('userData')` is built from `app.getName()`, which without an
 * explicit `setName` comes from package.json `name` — here `aistudy-desktop`.
 * Everything a user actually sees says `AIStudy` instead (electron-builder's
 * `productName`: the installer, the Start-Menu entry, the uninstall registry
 * key), and ADR-051 documented the runtime override as
 * `%APPDATA%\AIStudy\config.json`. In a packaged build that file was therefore
 * never read: measured on the installed app, with the file present and valid,
 * `aistudyConfig.getApiBaseUrl()` still answered `http://localhost:8080` and the
 * CSP — correctly derived from that value — blocked the origin the operator had
 * just configured, so login failed with no visible reason.
 *
 * `runtime-config.test.ts` pins this string to `build.productName`, because the
 * two drifting apart re-creates exactly that bug.
 */
export const APP_USER_DATA_NAME = 'AIStudy';

/**
 * A missing or unreadable config file is the normal case (users never
 * create one); a malformed one is ignored rather than fatal, because
 * falling back to a valid default is strictly safer than refusing to start.
 */
function readConfigFile(deps: RuntimeApiBaseUrlDeps): string | undefined {
  const read = deps.readFile ?? ((path: string) => readFileSync(path, 'utf8'));
  try {
    return read(join(deps.userDataPath(), RUNTIME_CONFIG_FILE_NAME));
  } catch {
    return undefined;
  }
}

export function resolveRuntimeApiBaseUrl(
  deps: RuntimeApiBaseUrlDeps
): ResolvedApiBaseUrl {
  const readEnv = deps.readEnv ?? ((name: string) => process.env[name]);
  // Lazy so a winning env override never touches the disk.
  const candidates: Array<[ApiBaseUrlSource, () => string | undefined | null]> =
    [
      ['env', () => readEnv(API_BASE_URL_ENV_VAR)],
      ['config-file', () => {
        const raw = readConfigFile(deps);
        return raw === undefined ? undefined : parseRuntimeConfig(raw).apiBaseUrl;
      }],
      ['build-default', () => deps.buildDefault],
    ];

  for (const [source, read] of candidates) {
    const usable = normalizeApiBaseUrl(read());
    if (usable !== undefined) {
      return { url: usable, source };
    }
  }
  return { url: DEFAULT_API_BASE_URL, source: 'hard-default' };
}
