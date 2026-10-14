/**
 * createRefreshingFetch tests (FE-AUTH-001).
 */

import { describe, expect, it, vi } from 'vitest';
import {
  createRefreshingFetch,
  isAuthEndpointUrl,
} from './authed-fetch';

function jsonResponse(status: number, body: unknown = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('isAuthEndpointUrl', () => {
  it('flags the three auth endpoints regardless of origin/path prefix', () => {
    expect(isAuthEndpointUrl('http://localhost:8080/api/v1/auth/login')).toBe(
      true
    );
    expect(isAuthEndpointUrl('http://x/api/v1/auth/refresh?x=1')).toBe(true);
    expect(isAuthEndpointUrl('http://x/api/v1/auth/logout')).toBe(true);
  });
  it('does not flag business endpoints', () => {
    expect(isAuthEndpointUrl('http://x/api/v1/spaces')).toBe(false);
    expect(isAuthEndpointUrl('http://x/api/v1/auth/me')).toBe(false);
  });
});

describe('createRefreshingFetch', () => {
  it('returns non-401 responses untouched and never refreshes', async () => {
    const base = vi.fn(async () => jsonResponse(200, { ok: true }));
    const refresh = vi.fn(async () => 'new-access');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/spaces');
    expect(res.status).toBe(200);
    expect(base).toHaveBeenCalledTimes(1);
    expect(refresh).not.toHaveBeenCalled();
  });

  it('refreshes once and retries a GET 401 with the new bearer', async () => {
    const base = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    const refresh = vi.fn(async () => 'new-access');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/spaces', {
      method: 'GET',
      headers: { Authorization: 'Bearer old-access' },
    });

    expect(res.status).toBe(200);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(base).toHaveBeenCalledTimes(2);
    const retryInit = base.mock.calls[1][1];
    expect((retryInit?.headers as Headers).get('Authorization')).toBe(
      'Bearer new-access'
    );
  });

  it('returns the original 401 when refresh yields no token', async () => {
    const base = vi.fn(async () => jsonResponse(401));
    const refresh = vi.fn(async () => null);
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/spaces');
    expect(res.status).toBe(401);
    expect(base).toHaveBeenCalledTimes(1); // never retried
  });

  it('never refreshes the auth endpoints themselves', async () => {
    const base = vi.fn(async () => jsonResponse(401));
    const refresh = vi.fn(async () => 'new-access');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/auth/refresh', {
      method: 'POST',
    });
    expect(res.status).toBe(401);
    expect(refresh).not.toHaveBeenCalled();
    expect(base).toHaveBeenCalledTimes(1);
  });

  it('triggers refresh on an unsafe method but never replays the write', async () => {
    const base = vi.fn(async () => jsonResponse(401));
    const refresh = vi.fn(async () => 'new-access');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/spaces', { method: 'POST' });
    expect(res.status).toBe(401);
    expect(refresh).toHaveBeenCalledTimes(1); // token rotated for next call
    expect(base).toHaveBeenCalledTimes(1); // POST not replayed
  });

  it('does not loop when the retried request is still 401', async () => {
    const base = vi.fn(async () => jsonResponse(401));
    const refresh = vi.fn(async () => 'new-access');
    const wrapped = createRefreshingFetch({
      fetchImpl: base as unknown as typeof fetch,
      refreshAccessToken: refresh,
    });

    const res = await wrapped('http://x/api/v1/spaces');
    expect(res.status).toBe(401);
    expect(base).toHaveBeenCalledTimes(2); // exactly one retry, then stop
  });
});
