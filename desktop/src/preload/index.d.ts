/**
 * Renderer type for the preload-exposed bridge (FE-AUTH-001).
 *
 * Kept structurally identical to src/preload/index.ts without importing
 * the preload module itself (renderer and preload compile under
 * different tsconfig projects / environments).
 */

export interface AuthVaultBridge {
  save: (refreshToken: string) => Promise<{ ok: true }>;
  load: () => Promise<string | null>;
  clear: () => Promise<{ ok: true }>;
}

declare global {
  interface Window {
    aistudyAuthVault?: AuthVaultBridge;
  }
}

export {};
