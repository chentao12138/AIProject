/**
 * AuthSession tests (FE-AUTH-001, ADR-026).
 *
 * Access token stays in memory; the refresh token only moves through
 * the injected window.aistudyAuthVault bridge (safeStorage in Electron).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '@aistudy/api-client';
import {
  AuthLoginError,
  AuthSession,
  isCompleteTokenPair,
} from './auth-session';

const PAIR = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresIn: 300,
  refreshToken: 'refresh-1',
  refreshExpiresIn: 86400,
};
const USER = {
  subject: 'u-1',
  username: 'alice',
  roles: ['ROLE_USER'],
};

interface VaultMock {
  save: ReturnType<typeof vi.fn>;
  load: ReturnType<typeof vi.fn>;
  clear: ReturnType<typeof vi.fn>;
}

function installVault(initial: string | null = null): VaultMock {
  const vault = {
    save: vi.fn(async () => ({ ok: true as const })),
    load: vi.fn(async () => initial),
    clear: vi.fn(async () => ({ ok: true as const })),
  };
  window.aistudyAuthVault = vault;
  return vault;
}

function fakeClient(overrides: Partial<Record<string, unknown>> = {}): ApiClient {
  return {
    login: vi.fn(async () => ({
      data: PAIR,
      response: { status: 200 },
    })),
    refresh: vi.fn(async () => ({
      data: PAIR,
      response: { status: 200 },
    })),
    logout: vi.fn(async () => ({ response: { status: 204 } })),
    getCurrentUser: vi.fn(async () => ({
      data: USER,
      response: { status: 200 },
    })),
    ...overrides,
  } as unknown as ApiClient;
}

afterEach(() => {
  delete window.aistudyAuthVault;
  vi.restoreAllMocks();
});

describe('isCompleteTokenPair', () => {
  it('requires both non-empty tokens', () => {
    expect(isCompleteTokenPair(PAIR)).toBe(true);
    expect(isCompleteTokenPair({ accessToken: 'a' })).toBe(false);
    expect(isCompleteTokenPair({ accessToken: 'a', refreshToken: '' })).toBe(
      false
    );
    expect(isCompleteTokenPair(null)).toBe(false);
    expect(isCompleteTokenPair('x')).toBe(false);
  });
});

describe('AuthSession.login', () => {
  let session: AuthSession;
  beforeEach(() => {
    session = new AuthSession();
  });

  it('stores access in memory, persists refresh via vault, loads user', async () => {
    const vault = installVault();
    const client = fakeClient();
    const user = await session.login(client, 'alice', 'pw');

    expect(user.username).toBe('alice');
    expect(session.isAuthenticated()).toBe(true);
    expect(session.getAccessToken()).toBe('access-1');
    expect(vault.save).toHaveBeenCalledWith('refresh-1');
  });

  it('throws a credential error on HTTP 401', async () => {
    installVault();
    const client = fakeClient({
      login: vi.fn(async () => ({
        data: { accessToken: null, refreshToken: null },
        error: {},
        response: { status: 401 },
      })),
    });
    await expect(session.login(client, 'a', 'b')).rejects.toBeInstanceOf(
      AuthLoginError
    );
    await expect(session.login(client, 'a', 'b')).rejects.toThrow(
      '用户名或密码错误。'
    );
    expect(session.isAuthenticated()).toBe(false);
  });

  it('rejects a structurally incomplete token pair', async () => {
    installVault();
    const client = fakeClient({
      login: vi.fn(async () => ({
        data: { accessToken: 'only-access' },
        response: { status: 200 },
      })),
    });
    await expect(session.login(client, 'a', 'b')).rejects.toBeInstanceOf(
      AuthLoginError
    );
  });

  it('resets locally when /auth/me fails after a successful login', async () => {
    const vault = installVault();
    const client = fakeClient({
      getCurrentUser: vi.fn(async () => ({
        data: undefined,
        error: {},
        response: { status: 500 },
      })),
    });
    await expect(session.login(client, 'a', 'b')).rejects.toThrow();
    expect(session.getAccessToken()).toBeNull();
    expect(vault.clear).toHaveBeenCalled();
  });
});

describe('AuthSession.bootstrap', () => {
  it('is anonymous when no refresh token is persisted', async () => {
    installVault(null);
    const session = new AuthSession();
    const client = fakeClient();
    const snapshot = await session.bootstrap(client);
    expect(snapshot.authenticated).toBe(false);
    expect(client.refresh).not.toHaveBeenCalled();
  });

  it('exchanges a persisted refresh token and becomes authenticated', async () => {
    installVault('stored-refresh');
    const session = new AuthSession();
    const client = fakeClient();
    const snapshot = await session.bootstrap(client);
    expect(snapshot.authenticated).toBe(true);
    expect(session.getAccessToken()).toBe('access-1');
    expect(client.refresh).toHaveBeenCalledWith({
      refreshToken: 'stored-refresh',
    });
  });

  it('clears local state when the persisted token is rejected', async () => {
    installVault('dead-refresh');
    const vault = window.aistudyAuthVault!;
    const session = new AuthSession();
    const client = fakeClient({
      refresh: vi.fn(async () => ({
        data: { accessToken: null, refreshToken: null },
        error: {},
        response: { status: 401 },
      })),
    });
    const snapshot = await session.bootstrap(client);
    expect(snapshot.authenticated).toBe(false);
    expect(vault.clear).toHaveBeenCalled();
  });
});

describe('AuthSession.rotateRefresh (single-flight)', () => {
  it('concurrent callers share one refresh request', async () => {
    installVault();
    const session = new AuthSession();
    const refresh = vi.fn(async () => ({
      data: { ...PAIR, accessToken: 'access-2', refreshToken: 'refresh-2' },
      response: { status: 200 },
    }));
    const client = fakeClient({ refresh });
    // Establish a session first so a refresh token is held.
    await session.login(client, 'alice', 'pw');
    refresh.mockClear();

    const [a, b] = await Promise.all([
      session.rotateRefresh(client),
      session.rotateRefresh(client),
    ]);
    expect(a).toBe(true);
    expect(b).toBe(true);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(session.getAccessToken()).toBe('access-2');
  });

  it('returns false without a refresh token (no request)', async () => {
    installVault();
    const session = new AuthSession();
    const client = fakeClient();
    expect(await session.rotateRefresh(client)).toBe(false);
    expect(client.refresh).not.toHaveBeenCalled();
  });

  it('resets the session when rotation is rejected', async () => {
    installVault('refresh-0');
    const session = new AuthSession();
    const client = fakeClient({
      refresh: vi
        .fn()
        .mockResolvedValueOnce({
          data: PAIR,
          response: { status: 200 },
        })
        .mockResolvedValueOnce({
          data: { accessToken: null, refreshToken: null },
          error: {},
          response: { status: 401 },
        }),
    });
    // establish a session
    await session.bootstrap(client);
    expect(session.isAuthenticated()).toBe(true);
    // family reuse / dead token -> reset
    expect(await session.rotateRefresh(client)).toBe(false);
    expect(session.isAuthenticated()).toBe(false);
    expect(session.getAccessToken()).toBeNull();
  });
});

describe('AuthSession.logout', () => {
  it('revokes server-side best-effort and clears local state', async () => {
    const vault = installVault();
    const session = new AuthSession();
    const client = fakeClient();
    await session.login(client, 'alice', 'pw');
    expect(session.isAuthenticated()).toBe(true);

    await session.logout(client);
    expect(client.logout).toHaveBeenCalledWith({ refreshToken: 'refresh-1' });
    expect(session.isAuthenticated()).toBe(false);
    expect(session.getAccessToken()).toBeNull();
    expect(vault.clear).toHaveBeenCalled();
  });

  it('still clears locally when the server revoke call fails', async () => {
    installVault();
    const session = new AuthSession();
    const client = fakeClient({
      logout: vi.fn(async () => {
        throw new TypeError('network down');
      }),
    });
    await session.login(client, 'alice', 'pw');
    await session.logout(client);
    expect(session.isAuthenticated()).toBe(false);
  });
});
