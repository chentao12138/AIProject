/**
 * Preload bridge surface (ADR-037).
 *
 * The promise is that the renderer gets exactly the encrypted refresh-token
 * vault and nothing else: no `ipcRenderer`, no inventable channel, no
 * arbitrary payload. FE-001.5 claimed a "static audit" for this that did not
 * exist, and the bridge was not even in the Vitest include list.
 */

import { describe, expect, it, vi } from 'vitest';
import { AUTH_VAULT_CHANNELS } from '../shared/auth-vault-contract';

const { invoke, exposeInMainWorld } = vi.hoisted(() => ({
  invoke: vi.fn(async (..._args: unknown[]) => ({ ok: true as const })),
  exposeInMainWorld: vi.fn(),
}));

vi.mock('electron', () => ({
  contextBridge: { exposeInMainWorld },
  ipcRenderer: { invoke },
}));

type Bridge = Record<string, (arg?: string) => Promise<unknown>>;

async function loadBridge(): Promise<{ name: string; bridge: Bridge }> {
  exposeInMainWorld.mockClear();
  invoke.mockClear();
  vi.resetModules();
  await import('./index');
  const call = exposeInMainWorld.mock.calls[0];
  if (!call) {
    throw new Error('preload exposed nothing to the main world');
  }
  return { name: call[0] as string, bridge: call[1] as Bridge };
}

describe('preload contextBridge', () => {
  it('exposes exactly the auth vault under one global name', async () => {
    const { name, bridge } = await loadBridge();
    expect(name).toBe('aistudyAuthVault');
    expect(Object.keys(bridge).sort()).toEqual(['clear', 'load', 'save']);
    expect(exposeInMainWorld).toHaveBeenCalledTimes(1);
  });

  it('never hands a raw ipcRenderer or electron object to the renderer', async () => {
    const { bridge } = await loadBridge();
    for (const value of Object.values(bridge)) {
      expect(typeof value).toBe('function');
    }
    // No channel-naming or event-subscription capability leaks through.
    expect(bridge).not.toHaveProperty('invoke');
    expect(bridge).not.toHaveProperty('send');
    expect(bridge).not.toHaveProperty('on');
  });

  it('routes each method to its own fixed channel', async () => {
    const { bridge } = await loadBridge();

    await bridge.save('a'.repeat(32));
    await bridge.load();
    await bridge.clear();

    expect(invoke.mock.calls.map((call) => call[0])).toEqual([
      AUTH_VAULT_CHANNELS.save,
      AUTH_VAULT_CHANNELS.load,
      AUTH_VAULT_CHANNELS.clear,
    ]);
  });

  it('rejects a malformed token before any IPC happens', async () => {
    const { bridge } = await loadBridge();

    await expect(bridge.save('')).rejects.toThrow('bad-payload');
    // The bound itself is inclusive; one over it is rejected un-encrypted.
    await expect(bridge.save('x'.repeat(4097))).rejects.toThrow('bad-payload');
    expect(invoke).not.toHaveBeenCalled();
  });
});
