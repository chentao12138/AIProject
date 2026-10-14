import { describe, expect, it, vi } from 'vitest';
import {
  createRefreshingFetch,
  isAuthEndpointUrl,
} from './authed-fetch';

function res(status: number) {
  return new Response('{}', { status });
}

describe('isAuthEndpointUrl', () => {
  it('flags auth endpoints only', () => {
    expect(isAuthEndpointUrl('/api/v1/auth/login')).toBe(true);
    expect(isAuthEndpointUrl('/api/v1/auth/refresh')).toBe(true);
    expect(isAuthEndpointUrl('/api/v1/admin/users')).toBe(false);
    expect(isAuthEndpointUrl('/api/v1/auth/me')).toBe(false);
  });
});

describe('createRefreshingFetch', () => {
  it('retries a GET 401 once with the rotated token', async () => {
    const base = vi
      .fn()
      .mockResolvedValueOnce(res(401))
      .mockResolvedValueOnce(res(200));
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: vi.fn(async () => 'new-token'),
    });
    const response = await wrapped('/api/v1/admin/users');
    expect(response.status).toBe(200);
    expect(base).toHaveBeenCalledTimes(2);
  });

  it('does not retry auth endpoints or unsafe methods', async () => {
    const base = vi.fn(async () => res(401));
    const refresh = vi.fn(async () => 't');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });
    await wrapped('/api/v1/auth/login', { method: 'POST' });
    expect(refresh).not.toHaveBeenCalled();

    await wrapped('/api/v1/admin/users/x/roles', { method: 'PUT' });
    expect(refresh).toHaveBeenCalledTimes(1); // rotated, but PUT not replayed
    expect(base).toHaveBeenCalledTimes(2);
  });
});
