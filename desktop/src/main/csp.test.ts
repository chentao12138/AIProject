/**
 * CSP policy tests (ELECTRON-CORS-001-A + FE-001.5 PRE-COMMIT REVIEW
 * FIX-01 API-origin contract).
 *
 * Production policy must be strict (no unsafe-eval, no script-src *,
 * self-only, minimal scoped style exception); dev policy keeps only
 * the documented HMR allowances. connect-src must always allow the
 * SAME API origin the renderer client resolves — default localhost:8080
 * or a custom http(s) VITE_API_BASE_URL origin (scheme+host+port only,
 * never the path, never '*').
 */

import { describe, expect, it } from 'vitest';
import { cspFor } from './csp';

describe('cspFor (production)', () => {
  const prod = cspFor(false);

  it('defaults to self', () => {
    expect(prod).toContain("default-src 'self'");
  });

  it('allows only self scripts, no unsafe-eval, no wildcards', () => {
    expect(prod).toContain("script-src 'self'");
    expect(prod).not.toContain('unsafe-eval');
    expect(prod).not.toContain('script-src *');
    expect(prod).not.toContain('*');
  });

  it('allows self-only styles with NO inline-style escape (PHASE 21)', () => {
    expect(prod).toContain("style-src 'self'");
    // PHASE 21: the last inline style attribute (category tree indent)
    // was removed — style-src-attr must be gone from production.
    expect(prod).not.toContain('style-src-attr');
    expect(prod).not.toContain('unsafe-inline');
    expect(prod).toMatch(/style-src 'self'(?! 'unsafe-inline')/);
  });

  it('allows the default API origin only for connect', () => {
    expect(prod).toContain("connect-src 'self' http://localhost:8080");
    expect(prod).not.toContain('localhost:5173');
    expect(prod).not.toContain('ws://');
  });

  it('hardens object/base-uri/frame-ancestors', () => {
    expect(prod).toContain("object-src 'none'");
    expect(prod).toContain("base-uri 'none'");
    expect(prod).toContain("frame-ancestors 'none'");
    expect(prod).toContain("form-action 'self'");
  });
});

describe('cspFor (development)', () => {
  const dev = cspFor(true, { devServerUrl: 'http://localhost:5173' });

  it('keeps the documented HMR allowances only', () => {
    expect(dev).toContain("script-src 'self' 'unsafe-inline'");
    expect(dev).toContain(
      "connect-src 'self' http://localhost:5173 ws://localhost:5173 http://localhost:8080"
    );
    expect(dev).not.toContain('unsafe-eval');
    expect(dev).not.toContain('script-src *');
    // no inline style-attribute escape in dev either (PHASE 21)
    expect(dev).not.toContain('style-src-attr');
  });
});

describe('cspFor — custom API origin contract (PRE-COMMIT FIX-01)', () => {
  it('allows a custom http origin, scheme+host+port only, in prod', () => {
    const prod = cspFor(false, { apiBaseUrl: 'http://127.0.0.1:9090/api' });
    expect(prod).toContain("connect-src 'self' http://127.0.0.1:9090");
    // the base URL path must never ride into connect-src
    expect(prod).not.toContain('http://127.0.0.1:9090/api');
    expect(prod).not.toContain('localhost:8080');
  });

  it('allows a custom https origin in prod', () => {
    const prod = cspFor(false, { apiBaseUrl: 'https://api.example.test/v1' });
    expect(prod).toContain("connect-src 'self' https://api.example.test");
    expect(prod).not.toContain('/v1');
    expect(prod).not.toContain('localhost:8080');
  });

  it('allows the custom origin in dev alongside HMR endpoints', () => {
    const dev = cspFor(true, {
      devServerUrl: 'http://localhost:5173',
      apiBaseUrl: 'https://api.example.test/v1',
    });
    expect(dev).toContain(
      "connect-src 'self' http://localhost:5173 ws://localhost:5173 https://api.example.test"
    );
    expect(dev).not.toContain('/v1');
    expect(dev).not.toContain('localhost:8080');
  });

  it('falls back to the default origin for invalid values', () => {
    for (const invalid of ['not a url', 'ftp://files.example.test', '*']) {
      const prod = cspFor(false, { apiBaseUrl: invalid });
      expect(prod).toContain(
        "connect-src 'self' http://localhost:8080"
      );
      // never a wildcard connect-src, never an unvalidated host
      expect(prod).not.toContain('connect-src *');
      expect(prod).not.toContain('connect-src \'self\' *');
    }
  });

  it('never emits a wildcard connect-src for any custom value', () => {
    for (const raw of ['*', 'http://*', 'http://127.0.0.1:9090/*']) {
      const prod = cspFor(false, { apiBaseUrl: raw });
      expect(prod).not.toMatch(/connect-src [^;]*\*/);
    }
  });
});
