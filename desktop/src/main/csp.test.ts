/**
 * CSP policy tests (ELECTRON-CORS-001-A).
 *
 * Production policy must be strict (no unsafe-eval, no script-src *,
 * self-only, minimal scoped style exception); dev policy keeps only
 * the documented HMR allowances.
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

  it('keeps only the scoped inline-style exception', () => {
    expect(prod).toContain("style-src 'self'");
    expect(prod).toContain("style-src-attr 'unsafe-inline'");
    // no blanket unsafe-inline for <style> blocks
    expect(prod).toMatch(/style-src 'self'(?! 'unsafe-inline')/);
  });

  it('allows localhost backend only for connect', () => {
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
  const dev = cspFor(true, 'http://localhost:5173');

  it('keeps the documented HMR allowances only', () => {
    expect(dev).toContain("script-src 'self' 'unsafe-inline'");
    expect(dev).toContain("connect-src 'self' http://localhost:5173 ws://localhost:5173 http://localhost:8080");
    expect(dev).not.toContain('unsafe-eval');
    expect(dev).not.toContain('script-src *');
  });
});
