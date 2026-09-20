/**
 * Runtime base URL adoption in the renderer (ADR-047).
 *
 * The renderer must end up talking to exactly what main resolved, because
 * that is the origin the CSP allows. These cover the three ways the bridge
 * can answer (valid, absent, useless) and that a rebuild actually happens.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { DEFAULT_API_BASE_URL } from '../../../shared/api-config';

type ApiClientModule = typeof import('./api-client');

async function load(): Promise<ApiClientModule> {
  vi.resetModules();
  return import('./api-client');
}

afterEach(() => {
  delete window.aistudyConfig;
});

describe('configureApiBaseUrl', () => {
  it('adopts the URL main resolved and rebuilds the client', async () => {
    window.aistudyConfig = {
      getApiBaseUrl: async () => 'https://api.example.com/base/',
    };
    const mod = await load();
    const before = mod.getApiClient();

    const resolved = await mod.configureApiBaseUrl();

    // Trailing slash normalized by the shared resolver; path preserved.
    expect(resolved).toBe('https://api.example.com/base');
    expect(mod.apiConfig.baseUrl).toBe(resolved);
    expect(mod.getApiClient()).not.toBe(before);
  });

  it('keeps the build-time default when there is no bridge (tests, browser)', async () => {
    const mod = await load();
    const initial = mod.apiConfig.baseUrl;

    await expect(mod.configureApiBaseUrl()).resolves.toBe(initial);
    expect(mod.apiConfig.baseUrl).toBe(initial);
  });

  it('ignores a rejected bridge call instead of blocking startup', async () => {
    window.aistudyConfig = {
      getApiBaseUrl: async () => {
        throw new Error('untrusted-sender');
      },
    };
    const mod = await load();
    const initial = mod.apiConfig.baseUrl;

    await expect(mod.configureApiBaseUrl()).resolves.toBe(initial);
  });

  it('refuses a non-http(s) answer and falls back to the safe default', async () => {
    window.aistudyConfig = { getApiBaseUrl: async () => 'file:///etc/passwd' };
    const mod = await load();
    // The build-time value in this environment is already the default.
    expect(mod.apiConfig.baseUrl).toBe(DEFAULT_API_BASE_URL);

    await expect(mod.configureApiBaseUrl()).resolves.toBe(
      DEFAULT_API_BASE_URL
    );
  });
});
