/**
 * App-config IPC: one channel, one value, trusted senders only.
 */

import { describe, expect, it, vi } from 'vitest';
import { APP_CONFIG_CHANNELS } from '../shared/runtime-config';
import { registerAppConfigIpc } from './config-ipc';

const ALLOWED = ['app://aistudy'];

function register(apiBaseUrl: () => string) {
  const handle = vi.fn();
  registerAppConfigIpc({ apiBaseUrl, allowedOrigins: ALLOWED, handle });
  expect(handle).toHaveBeenCalledTimes(1);
  const [channel, listener] = handle.mock.calls[0] as unknown as [
    string,
    (event: { senderFrame?: { url?: string } | null }) => Promise<unknown>,
  ];
  return { channel, listener };
}

describe('registerAppConfigIpc', () => {
  it('registers only the get-api-base-url channel', () => {
    const { channel } = register(() => 'http://127.0.0.1:8080');
    expect(channel).toBe(APP_CONFIG_CHANNELS.getApiBaseUrl);
  });

  it('answers a trusted renderer origin with the resolved URL', async () => {
    const { listener } = register(() => 'https://api.example.com');
    await expect(
      listener({ senderFrame: { url: 'app://aistudy/index.html' } })
    ).resolves.toBe('https://api.example.com');
  });

  it('rejects a foreign sender without leaking the value', async () => {
    const getValue = vi.fn(() => 'https://internal.example.com');
    const { listener } = register(getValue);

    await expect(
      listener({ senderFrame: { url: 'https://evil.example/' } })
    ).rejects.toThrow('untrusted-sender');
    await expect(listener({ senderFrame: null })).rejects.toThrow(
      'untrusted-sender'
    );
    expect(getValue).not.toHaveBeenCalled();
  });

  it('reads the value lazily so a later resolution is never stale', async () => {
    let current = 'http://localhost:8080';
    const { listener } = register(() => current);

    await expect(
      listener({ senderFrame: { url: 'app://aistudy/' } })
    ).resolves.toBe('http://localhost:8080');

    current = 'https://api.example.com';
    await expect(
      listener({ senderFrame: { url: 'app://aistudy/' } })
    ).resolves.toBe('https://api.example.com');
  });
});
