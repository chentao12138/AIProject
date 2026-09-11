/**
 * API base URL contract tests (FE-001.5 PRE-COMMIT REVIEW FIX-01).
 *
 * The renderer client and the main-process CSP must derive from ONE
 * normalization: same VITE_API_BASE_URL value, same fallback, same
 * origin extraction. These tests pin that contract.
 */

import { describe, expect, it } from 'vitest';
import {
  apiOriginFromBaseUrl,
  DEFAULT_API_BASE_URL,
  resolveApiBaseUrl,
} from './api-config';

describe('resolveApiBaseUrl', () => {
  it('defaults when the env value is missing or empty', () => {
    expect(resolveApiBaseUrl(undefined)).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl(null)).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('')).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('   ')).toBe(DEFAULT_API_BASE_URL);
  });

  it('accepts plain http(s) origins', () => {
    expect(resolveApiBaseUrl('http://localhost:8080')).toBe(
      'http://localhost:8080'
    );
    expect(resolveApiBaseUrl('https://api.example.test')).toBe(
      'https://api.example.test'
    );
    expect(resolveApiBaseUrl('https://api.example.test:9443')).toBe(
      'https://api.example.test:9443'
    );
  });

  it('keeps a base URL path for the API client', () => {
    expect(resolveApiBaseUrl('http://127.0.0.1:9090/api')).toBe(
      'http://127.0.0.1:9090/api'
    );
    expect(resolveApiBaseUrl('https://api.example.test/v1')).toBe(
      'https://api.example.test/v1'
    );
  });

  it('trims whitespace and trailing slashes', () => {
    expect(resolveApiBaseUrl('  http://localhost:8080/  ')).toBe(
      'http://localhost:8080'
    );
    expect(resolveApiBaseUrl('http://localhost:8080///')).toBe(
      'http://localhost:8080'
    );
  });

  it('falls back safely for non-http(s) schemes', () => {
    expect(resolveApiBaseUrl('ftp://files.example.test')).toBe(
      DEFAULT_API_BASE_URL
    );
    expect(resolveApiBaseUrl('file:///tmp/x')).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('ws://localhost:8080')).toBe(
      DEFAULT_API_BASE_URL
    );
    expect(resolveApiBaseUrl('javascript:alert(1)')).toBe(
      DEFAULT_API_BASE_URL
    );
  });

  it('falls back safely for unparsable values', () => {
    expect(resolveApiBaseUrl('not a url')).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('localhost:8080')).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('http://')).toBe(DEFAULT_API_BASE_URL);
    expect(resolveApiBaseUrl('http:///missing-host')).toBe(
      DEFAULT_API_BASE_URL
    );
  });

  it('never returns a wildcard or an unvalidated origin', () => {
    for (const raw of ['*', 'http://*', 'https://*', 'http://**']) {
      const resolved = resolveApiBaseUrl(raw);
      expect(resolved.includes('*')).toBe(false);
      expect(resolved).toBe(DEFAULT_API_BASE_URL);
    }
    // A wildcard inside a PATH is not an origin wildcard: the resolved
    // base URL keeps the path, but the CSP origin must stay plain.
    const withPathWildcard = resolveApiBaseUrl('http://localhost:8080/*');
    expect(withPathWildcard).toBe('http://localhost:8080/*');
    const origin = apiOriginFromBaseUrl(withPathWildcard);
    expect(origin).toBe('http://localhost:8080');
    expect(origin.includes('*')).toBe(false);
  });
});

describe('apiOriginFromBaseUrl (CSP contract)', () => {
  it('extracts scheme + host + port only', () => {
    expect(apiOriginFromBaseUrl('http://127.0.0.1:9090/api')).toBe(
      'http://127.0.0.1:9090'
    );
    expect(apiOriginFromBaseUrl('https://api.example.test/v1')).toBe(
      'https://api.example.test'
    );
    expect(apiOriginFromBaseUrl('https://api.example.test:9443/v1')).toBe(
      'https://api.example.test:9443'
    );
  });

  it('never includes a path in the origin', () => {
    const origin = apiOriginFromBaseUrl('http://127.0.0.1:9090/api/v2');
    expect(origin).not.toContain('/api');
    expect(origin).not.toContain('v2');
  });

  it('defaults the origin when the base URL is unparsable', () => {
    expect(apiOriginFromBaseUrl('garbage')).toBe(
      'http://localhost:8080'
    );
  });

  it('matches the default base URL origin', () => {
    expect(apiOriginFromBaseUrl(DEFAULT_API_BASE_URL)).toBe(
      'http://localhost:8080'
    );
  });
});
