/**
 * Renderer types for the preload-exposed bridges (FE-AUTH-001, ADR-047).
 *
 * Both interfaces come from src/shared so the preload implementation and
 * this declaration cannot drift apart — previously this file restated the
 * shape by hand and only a comment claimed they matched.
 */

import type { AuthVaultBridge } from '../shared/auth-vault-contract';
import type { AppConfigBridge } from '../shared/runtime-config';

export type { AuthVaultBridge, AppConfigBridge };

declare global {
  interface Window {
    aistudyAuthVault?: AuthVaultBridge;
    aistudyConfig?: AppConfigBridge;
  }
}

export {};
