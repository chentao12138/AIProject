/**
 * Read-only app-config IPC (ADR-051).
 *
 * The renderer must talk to exactly the origin the main process allowed in
 * the CSP, so the resolved base URL is published from the SAME place that
 * derives `connect-src` — over one fixed channel, to one trusted origin.
 */

import {
  APP_CONFIG_CHANNELS,
  type AppConfigChannel,
} from '../shared/runtime-config';
import {
  isTrustedVaultSender,
  VaultSecurityError,
  type VaultIpcEvent,
} from './auth-vault';

export interface AppConfigIpcDeps {
  /** The resolved base URL; read lazily so the answer is always current. */
  apiBaseUrl: () => string;
  /** Renderer origins allowed to ask (app://aistudy, plus the dev server). */
  allowedOrigins: readonly string[];
  handle: (
    channel: AppConfigChannel,
    listener: (event: VaultIpcEvent) => Promise<unknown>
  ) => void;
}

export function registerAppConfigIpc(deps: AppConfigIpcDeps): void {
  // VaultSecurityError is the app's single IPC rejection type; an untrusted
  // sender gets no value and no detail.
  deps.handle(APP_CONFIG_CHANNELS.getApiBaseUrl, async (event) => {
    if (!isTrustedVaultSender(event.senderFrame?.url, deps.allowedOrigins)) {
      throw new VaultSecurityError('untrusted-sender');
    }
    return deps.apiBaseUrl();
  });
}
