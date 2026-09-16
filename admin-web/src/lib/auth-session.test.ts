import { describe, expect, it, vi } from 'vitest';
import type { ApiClient } from '@aistudy/api-client';
import {
  ADMIN_ROLE,
  AuthLoginError,
  AuthSession,
  isCompleteTokenPair,
} from './auth-session';

const PAIR = {
  accessToken: 'a1',
  refreshToken: 'r1',
  tokenType: 'Bearer',
  expiresIn: 300,
  refreshExpiresIn: 86400,
};

function fakeClient(overrides: Partial<Record<string, unknown>> = {}): ApiClient {
  return {
    login: vi.fn(async () => ({ data: PAIR, response: { status: 200 } })),
    refresh: vi.fn(async () => ({ data: PAIR, response: { status: 200 } })),
    logout: vi.fn(async () => ({ response: { status: 204 } })),
    getCurrentUser: vi.fn(async () => ({
      data: { subject: 's1', username: 'admin', roles: [ADMIN_ROLE] },
      response: { status: 200 },
    })),
    ...overrides,
  } as unknown as ApiClient;
}

describe('isCompleteTokenPair', () => {
  it('needs both tokens', () => {
    expect(isCompleteTokenPair(PAIR)).toBe(true);
    expect(isCompleteTokenPair({ accessToken: 'x' })).toBe(false);
    expect(isCompleteTokenPair(null)).toBe(false);
  });
});

describe('AuthSession', () => {
  it('logs in and reports admin role', async () => {
    const session = new AuthSession();
    const user = await session.login(fakeClient(), 'admin', 'pw');
    expect(user.username).toBe('admin');
    expect(session.isAuthenticated()).toBe(true);
    expect(session.isAdmin()).toBe(true);
    expect(session.getAccessToken()).toBe('a1');
  });

  it('throws credential error on 401', async () => {
    const session = new AuthSession();
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
    expect(session.isAuthenticated()).toBe(false);
  });

  it('single-flight: concurrent rotations share one refresh call', async () => {
    const session = new AuthSession();
    const refresh = vi.fn(async () => ({
      data: { ...PAIR, accessToken: 'a2', refreshToken: 'r2' },
      response: { status: 200 },
    }));
    const client = fakeClient({ refresh });
    await session.login(client, 'admin', 'pw');
    refresh.mockClear();

    const [x, y] = await Promise.all([
      session.rotateRefresh(client),
      session.rotateRefresh(client),
    ]);
    expect(x && y).toBe(true);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(session.getAccessToken()).toBe('a2');
  });

  it('clears the session when rotation is rejected', async () => {
    const session = new AuthSession();
    const client = fakeClient({
      refresh: vi.fn(async () => ({
        data: { accessToken: null, refreshToken: null },
        error: {},
        response: { status: 401 },
      })),
    });
    await session.login(client, 'admin', 'pw');
    expect(await session.rotateRefresh(client)).toBe(false);
    expect(session.isAuthenticated()).toBe(false);
  });

  it('logout revokes best-effort and resets locally', async () => {
    const session = new AuthSession();
    const client = fakeClient();
    await session.login(client, 'admin', 'pw');
    await session.logout(client);
    expect(client.logout).toHaveBeenCalledWith({ refreshToken: 'r1' });
    expect(session.isAuthenticated()).toBe(false);
    expect(session.getAccessToken()).toBeNull();
  });
});
