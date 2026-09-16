/**
 * auth-vault tests (FE-AUTH-001).
 *
 * Covers the pure contract + envelope codec + the full IPC handler
 * behaviour with injected fake deps (no Electron runtime): save/load
 * round trip, clear, sender-origin rejection, payload validation,
 * missing encryption and corrupt-envelope handling.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  mkdtempSync,
  rmSync,
  existsSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  AUTH_VAULT_CHANNELS,
  isAuthVaultChannel,
  normalizeRefreshToken,
} from '../shared/auth-vault-contract';
import {
  VAULT_FILE_NAME,
  VaultSecurityError,
  decodeVaultEnvelope,
  encodeVaultEnvelope,
  isTrustedVaultSender,
  registerAuthVaultIpc,
  type AuthVaultDeps,
  type VaultIpcEvent,
} from './auth-vault';

describe('auth-vault-contract', () => {
  it('isAuthVaultChannel accepts only the fixed allowlist', () => {
    expect(isAuthVaultChannel(AUTH_VAULT_CHANNELS.save)).toBe(true);
    expect(isAuthVaultChannel(AUTH_VAULT_CHANNELS.load)).toBe(true);
    expect(isAuthVaultChannel(AUTH_VAULT_CHANNELS.clear)).toBe(true);
    expect(isAuthVaultChannel('auth-vault:evil')).toBe(false);
    expect(isAuthVaultChannel(undefined)).toBe(false);
    expect(isAuthVaultChannel(123)).toBe(false);
  });

  it('normalizeRefreshToken trims and rejects empty/oversized/non-string', () => {
    expect(normalizeRefreshToken('  abc  ')).toBe('abc');
    expect(normalizeRefreshToken('')).toBe(null);
    expect(normalizeRefreshToken('   ')).toBe(null);
    expect(normalizeRefreshToken(42)).toBe(null);
    expect(normalizeRefreshToken(null)).toBe(null);
    expect(normalizeRefreshToken('x'.repeat(4096))).toHaveLength(4096);
    expect(normalizeRefreshToken('x'.repeat(4097))).toBe(null);
  });
});

describe('vault envelope codec', () => {
  it('round-trips ciphertext buffers', () => {
    const cipher = Buffer.from([0, 1, 2, 250, 255]);
    const text = encodeVaultEnvelope(cipher);
    expect(decodeVaultEnvelope(text)?.equals(cipher)).toBe(true);
  });

  it.each([
    ['not json'],
    ['{}'],
    [JSON.stringify({ version: 2, ciphertext: 'aa' })],
    [JSON.stringify({ version: 1, ciphertext: '' })],
    [JSON.stringify({ version: 1, ciphertext: 42 })],
  ])('rejects invalid envelope: %s', (text) => {
    expect(decodeVaultEnvelope(text)).toBe(null);
  });
});

describe('isTrustedVaultSender', () => {
  const origins = ['app://aistudy', 'http://localhost:5173'];
  it('accepts exactly the allowed origins (ignores path/hash)', () => {
    expect(
      isTrustedVaultSender('app://aistudy/#/spaces', origins)
    ).toBe(true);
    expect(
      isTrustedVaultSender('http://localhost:5173/src/main.tsx', origins)
    ).toBe(true);
  });
  it('rejects foreign/missing/malformed senders', () => {
    expect(isTrustedVaultSender('https://evil.example', origins)).toBe(false);
    expect(isTrustedVaultSender(undefined, origins)).toBe(false);
    expect(isTrustedVaultSender(null, origins)).toBe(false);
    expect(isTrustedVaultSender('not a url', origins)).toBe(false);
  });
});

type Handler = (event: VaultIpcEvent, ...args: unknown[]) => Promise<unknown>;

function makeDeps(overrides: Partial<AuthVaultDeps> = {}): {
  deps: AuthVaultDeps;
  handlers: Map<string, Handler>;
  dir: string;
} {
  const dir = mkdtempSync(join(tmpdir(), 'vault-'));
  const handlers = new Map<string, Handler>();
  // Trivial reversible "encryption": reversed base64 so tests can prove
  // the on-disk blob is NOT the plaintext.
  const encryptString = (plain: string) =>
    Buffer.from(`enc:${plain}`, 'utf8');
  const decryptString = (cipher: Buffer) => {
    const text = cipher.toString('utf8');
    if (!text.startsWith('enc:')) throw new Error('cannot decrypt');
    return text.slice(4);
  };
  const deps: AuthVaultDeps = {
    userDataPath: () => dir,
    isEncryptionAvailable: () => true,
    encryptString: vi.fn(encryptString),
    decryptString: vi.fn(decryptString),
    handle: (channel, listener) => handlers.set(channel, listener),
    allowedOrigins: ['app://aistudy'],
    ...overrides,
  };
  registerAuthVaultIpc(deps);
  return { deps, handlers, dir };
}

const trustedEvent: VaultIpcEvent = {
  senderFrame: { url: 'app://aistudy/#/spaces' },
};
const foreignEvent: VaultIpcEvent = {
  senderFrame: { url: 'https://evil.example/' },
};

describe('registerAuthVaultIpc — save/load/clear', () => {
  let dir: string;
  beforeEach(() => {
    dir = makeDeps().dir;
  });
  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it('saves an encrypted envelope to disk and loads it back', async () => {
    const { handlers, dir: d } = makeDeps();
    const save = handlers.get(AUTH_VAULT_CHANNELS.save)!;
    const load = handlers.get(AUTH_VAULT_CHANNELS.load)!;

    expect(await save(trustedEvent, 'refresh-abc')).toEqual({ ok: true });
    const blobPath = join(d, VAULT_FILE_NAME);
    expect(existsSync(blobPath)).toBe(true);
    const onDisk = readFileSync(blobPath, 'utf8');
    expect(onDisk).not.toContain('refresh-abc'); // ciphertext at rest
    expect(JSON.parse(onDisk).version).toBe(1);

    expect(await load(trustedEvent)).toBe('refresh-abc');
  });

  it('save accepts the token as direct argument and trims it', async () => {
    const { handlers } = makeDeps();
    await handlers.get(AUTH_VAULT_CHANNELS.save)!(trustedEvent, '  tok-x  ');
    expect(await handlers.get(AUTH_VAULT_CHANNELS.load)!(trustedEvent)).toBe(
      'tok-x'
    );
  });

  it('load returns null when no file exists', async () => {
    const { handlers } = makeDeps();
    expect(await handlers.get(AUTH_VAULT_CHANNELS.load)!(trustedEvent)).toBe(
      null
    );
  });

  it('load returns null for a corrupt envelope', async () => {
    const { handlers, dir: d } = makeDeps();
    writeFileSync(join(d, VAULT_FILE_NAME), 'garbage');
    expect(await handlers.get(AUTH_VAULT_CHANNELS.load)!(trustedEvent)).toBe(
      null
    );
  });

  it('clear removes the persisted blob', async () => {
    const { handlers, dir: d } = makeDeps();
    await handlers.get(AUTH_VAULT_CHANNELS.save)!(trustedEvent, 'tok');
    await handlers.get(AUTH_VAULT_CHANNELS.clear)!(trustedEvent);
    expect(existsSync(join(d, VAULT_FILE_NAME))).toBe(false);
    expect(await handlers.get(AUTH_VAULT_CHANNELS.load)!(trustedEvent)).toBe(
      null
    );
  });
});

describe('registerAuthVaultIpc — security rejections', () => {
  it('rejects every channel for an untrusted sender', async () => {
    const { handlers } = makeDeps();
    for (const channel of Object.values(AUTH_VAULT_CHANNELS)) {
      await expect(
        handlers.get(channel)!(foreignEvent)
      ).rejects.toBeInstanceOf(VaultSecurityError);
    }
  });

  it('rejects save for bad payload shapes', async () => {
    const { handlers } = makeDeps();
    const save = handlers.get(AUTH_VAULT_CHANNELS.save)!;
    for (const bad of ['', '   ', 123, null, 'x'.repeat(4097)]) {
      await expect(save(trustedEvent, bad)).rejects.toBeInstanceOf(
        VaultSecurityError
      );
    }
  });

  it('refuses to persist when OS encryption is unavailable (never plaintext)', async () => {
    const { handlers, dir: d } = makeDeps({
      isEncryptionAvailable: () => false,
    });
    await expect(
      handlers.get(AUTH_VAULT_CHANNELS.save)!(trustedEvent, 'tok')
    ).rejects.toMatchObject({ code: 'encryption-unavailable' });
    expect(existsSync(join(d, VAULT_FILE_NAME))).toBe(false);
  });
});
