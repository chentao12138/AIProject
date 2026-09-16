/**
 * Preload bridge (FE-AUTH-001, ADR-026 / ADR-037).
 *
 * The renderer stays Node-free: the ONLY surface exposed is the
 * encrypted refresh-token vault (three fixed IPC channels). Raw
 * ipcRenderer is never leaked — the renderer cannot invent channels,
 * cannot send arbitrary messages, and the main process additionally
 * checks the sender frame origin on every call.
 *
 * Sandbox-compatible: this module imports only `electron` and the
 * Node-free shared contract.
 */

import { contextBridge, ipcRenderer } from 'electron';
import {
  AUTH_VAULT_CHANNELS,
  normalizeRefreshToken,
} from '../shared/auth-vault-contract';

const authVault = {
  /** Encrypt + persist the refresh token. Rejects on invalid input. */
  save: (refreshToken: string): Promise<{ ok: true }> => {
    const normalized = normalizeRefreshToken(refreshToken);
    if (normalized === null) {
      return Promise.reject(new Error('bad-payload'));
    }
    return ipcRenderer.invoke(AUTH_VAULT_CHANNELS.save, normalized);
  },
  /** Load + decrypt the persisted refresh token, or null if none. */
  load: (): Promise<string | null> =>
    ipcRenderer.invoke(AUTH_VAULT_CHANNELS.load),
  /** Remove the persisted refresh token. */
  clear: (): Promise<{ ok: true }> =>
    ipcRenderer.invoke(AUTH_VAULT_CHANNELS.clear),
};

contextBridge.exposeInMainWorld('aistudyAuthVault', authVault);

export type AuthVaultBridge = typeof authVault;
