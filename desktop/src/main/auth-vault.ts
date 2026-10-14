/**
 * Encrypted refresh-token vault — MAIN process (FE-AUTH-001, ADR-026).
 *
 * Security contract:
 *  - The refresh token NEVER touches localStorage/sessionStorage/cookies
 *    and is never logged. At rest it is encrypted by Electron
 *    safeStorage (OS keychain-backed: DPAPI on Windows, Keychain on
 *    macOS, libsecret on Linux) and written as a versioned envelope to
 *    userData.
 *  - The renderer can only reach three fixed channels (save/load/clear)
 *    via the preload contextBridge; every call is sender-origin checked
 *    and payload-validated; failures reject with a tagged error.
 *  - If OS encryption is unavailable the vault refuses to persist (save
 *    rejects with 'encryption-unavailable') rather than writing plaintext.
 *
 * Electron side-effects (ipcMain, safeStorage, fs) are injected so the
 * pure validation/envelope logic is unit-testable without Electron.
 */

import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import {
  AUTH_VAULT_CHANNELS,
  normalizeRefreshToken,
  type AuthVaultChannel,
} from '../shared/auth-vault-contract';

export const VAULT_FILE_NAME = 'auth-refresh.bin';
const VAULT_FORMAT_VERSION = 1;

interface VaultEnvelope {
  version: number;
  /** safeStorage ciphertext, base64-encoded so the on-disk file is JSON. */
  ciphertext: string;
}

/** Encode ciphertext into the versioned JSON envelope (main-only: Buffer). */
export function encodeVaultEnvelope(ciphertext: Buffer): string {
  const envelope: VaultEnvelope = {
    version: VAULT_FORMAT_VERSION,
    ciphertext: ciphertext.toString('base64'),
  };
  return JSON.stringify(envelope);
}

/**
 * Parse + validate an envelope read from disk. Returns the ciphertext
 * Buffer, or null for any structurally invalid / wrong-version payload
 * (treated as "no usable token", never as plaintext).
 */
export function decodeVaultEnvelope(text: string): Buffer | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (
    typeof parsed !== 'object' ||
    parsed === null ||
    !('version' in parsed) ||
    !('ciphertext' in parsed)
  ) {
    return null;
  }
  const envelope = parsed as Partial<VaultEnvelope>;
  if (envelope.version !== VAULT_FORMAT_VERSION) {
    return null;
  }
  if (typeof envelope.ciphertext !== 'string' || envelope.ciphertext === '') {
    return null;
  }
  const ciphertext = Buffer.from(envelope.ciphertext, 'base64');
  // Round-trip guards against truncated/non-base64 garbage.
  if (ciphertext.toString('base64') !== envelope.ciphertext) {
    return null;
  }
  return ciphertext;
}

/**
 * Only our own renderer origin(s) may call the vault. In production the
 * sole origin is app://aistudy; in dev the electron-vite dev server is
 * additionally allowed. Anything else (including a missing sender URL)
 * is rejected.
 */
/**
 * Extract a scheme://host origin. Electron registers app:// as a
 * standard+secure scheme (Chromium then yields app://aistudy), but Node's
 * WHATWG URL reports origin "null" for a non-special scheme — reconstruct
 * scheme://host explicitly so the predicate is identical under the
 * Node-based unit tests and the Chromium runtime.
 */
function senderOriginOf(senderUrl: string): string | null {
  let url: URL;
  try {
    url = new URL(senderUrl);
  } catch {
    return null;
  }
  if (url.host) {
    return `${url.protocol}//${url.host}`;
  }
  return url.origin === 'null' ? null : url.origin;
}

export function isTrustedVaultSender(
  senderUrl: string | undefined | null,
  allowedOrigins: readonly string[]
): boolean {
  if (!senderUrl) {
    return false;
  }
  const origin = senderOriginOf(senderUrl);
  if (origin === null) {
    return false;
  }
  return allowedOrigins.includes(origin);
}

/** Minimal structural type for the IpcMainEvent the handler receives. */
export interface VaultIpcEvent {
  senderFrame?: { url?: string } | null;
}

export interface AuthVaultDeps {
  /** app.getPath('userData') */
  userDataPath: () => string;
  /** safeStorage.isEncryptionAvailable */
  isEncryptionAvailable: () => boolean;
  /** safeStorage.encryptString */
  encryptString: (plaintext: string) => Buffer;
  /** safeStorage.decryptString */
  decryptString: (ciphertext: Buffer) => string;
  /** ipcMain.handle(channel, listener) */
  handle: (
    channel: AuthVaultChannel,
    listener: (
      event: VaultIpcEvent,
      ...args: unknown[]
    ) => Promise<unknown>
  ) => void;
  /** Renderer origins allowed to invoke the vault. */
  allowedOrigins: readonly string[];
}

export class VaultSecurityError extends Error {
  constructor(public code: 'untrusted-sender' | 'bad-payload' | 'encryption-unavailable') {
    super(code);
    this.name = 'VaultSecurityError';
  }
}

/**
 * Register the three vault IPC handlers. Returns an array of
 * [channel, listener] pairs so the caller can remove them if ever needed.
 */
export function registerAuthVaultIpc(deps: AuthVaultDeps) {
  const { allowedOrigins } = deps;
  const vaultFilePath = () =>
    `${deps.userDataPath().replace(/[\\/]$/, '')}/${VAULT_FILE_NAME}`;

  function checkSender(event: VaultIpcEvent): void {
    if (!isTrustedVaultSender(event.senderFrame?.url, allowedOrigins)) {
      throw new VaultSecurityError('untrusted-sender');
    }
  }

  const registrations: Array<
    [AuthVaultChannel, (event: VaultIpcEvent, ...args: unknown[]) => Promise<unknown>]
  > = [];

  function register(
    channel: AuthVaultChannel,
    listener: (event: VaultIpcEvent, ...args: unknown[]) => Promise<unknown>
  ) {
    deps.handle(channel, listener);
    registrations.push([channel, listener]);
  }

  register(AUTH_VAULT_CHANNELS.save, async (event, value) => {
    checkSender(event);
    const token = normalizeRefreshToken(value);
    if (token === null) {
      throw new VaultSecurityError('bad-payload');
    }
    if (!deps.isEncryptionAvailable()) {
      throw new VaultSecurityError('encryption-unavailable');
    }
    const ciphertext = deps.encryptString(token);
    const path = vaultFilePath();
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, encodeVaultEnvelope(ciphertext), {
      encoding: 'utf8',
      mode: 0o600,
    });
    return { ok: true as const };
  });

  register(AUTH_VAULT_CHANNELS.load, async (event) => {
    checkSender(event);
    let raw: string;
    try {
      raw = await readFile(vaultFilePath(), 'utf8');
    } catch {
      return null; // no persisted session
    }
    const ciphertext = decodeVaultEnvelope(raw);
    if (ciphertext === null) {
      return null;
    }
    let plaintext: string;
    try {
      plaintext = deps.decryptString(ciphertext);
    } catch {
      return null; // unreadable (e.g. OS keychain reset) -> re-login
    }
    return normalizeRefreshToken(plaintext);
  });

  register(AUTH_VAULT_CHANNELS.clear, async (event) => {
    checkSender(event);
    await rm(vaultFilePath(), { force: true });
    return { ok: true as const };
  });

  return registrations;
}
