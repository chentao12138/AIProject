/**
 * Preload bridge surface (ADR-037, ADR-051).
 *
 * The promise is that the renderer gets the encrypted refresh-token vault
 * plus one read-only config getter, and nothing else: no `ipcRenderer`, no
 * inventable channel, no arbitrary payload. FE-001.5 claimed a "static audit"
 * for this that did not exist, and the preload was not even in the Vitest
 * include list.
 */

import { describe, expect, it, vi } from 'vitest';
import { AUTH_VAULT_CHANNELS } from '../shared/auth-vault-contract';
import { APP_CONFIG_CHANNELS } from '../shared/runtime-config';

const { invoke, exposeInMainWorld } = vi.hoisted(() => ({
  invoke: vi.fn(async (..._args: unknown[]) => ({ ok: true as const })),
  exposeInMainWorld: vi.fn(),
}));

vi.mock('electron', () => ({
  contextBridge: { exposeInMainWorld },
  ipcRenderer: { invoke },
}));

type Bridge = Record<string, (arg?: string) => Promise<unknown>>;

async function loadBridges(): Promise<Record<string, Bridge>> {
  exposeInMainWorld.mockClear();
  invoke.mockClear();
  vi.resetModules();
  await import('./index');
  const exposed: Record<string, Bridge> = {};
  for (const [name, value] of exposeInMainWorld.mock.calls) {
    exposed[name as string] = value as Bridge;
  }
  return exposed;
}

describe('preload contextBridge', () => {
  it('exposes exactly the vault and the read-only config', async () => {
    const exposed = await loadBridges();

    expect(Object.keys(exposed).sort()).toEqual([
      'aistudyAuthVault',
      'aistudyConfig',
    ]);
    expect(Object.keys(exposed.aistudyAuthVault).sort()).toEqual([
      'clear',
      'load',
      'save',
    ]);
    expect(Object.keys(exposed.aistudyConfig).sort()).toEqual([
      'getApiBaseUrl',
    ]);
  });

  it('never hands a raw ipcRenderer or electron object to the renderer', async () => {
    const exposed = await loadBridges();

    for (const bridge of Object.values(exposed)) {
      for (const value of Object.values(bridge)) {
        expect(typeof value).toBe('function');
      }
      // No channel-naming or event-subscription capability leaks through.
      expect(bridge).not.toHaveProperty('invoke');
      expect(bridge).not.toHaveProperty('send');
      expect(bridge).not.toHaveProperty('on');
    }
  });

  it('routes each method to its own fixed channel', async () => {
    const { aistudyAuthVault: vault, aistudyConfig: config } =
      await loadBridges();

    await vault.save('a'.repeat(32));
    await vault.load();
    await vault.clear();
    await config.getApiBaseUrl();

    expect(invoke.mock.calls.map((call) => call[0])).toEqual([
      AUTH_VAULT_CHANNELS.save,
      AUTH_VAULT_CHANNELS.load,
      AUTH_VAULT_CHANNELS.clear,
      APP_CONFIG_CHANNELS.getApiBaseUrl,
    ]);
  });

  it('rejects a malformed token before any IPC happens', async () => {
    const { aistudyAuthVault: vault } = await loadBridges();

    await expect(vault.save('')).rejects.toThrow('bad-payload');
    // The bound itself is inclusive; one over it is rejected un-encrypted.
    await expect(vault.save('x'.repeat(4097))).rejects.toThrow('bad-payload');
    expect(invoke).not.toHaveBeenCalled();
  });
});
