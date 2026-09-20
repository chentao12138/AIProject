/**
 * ADR-037 security-baseline regression gate.
 *
 * These four flags were previously written inline in `index.ts` and asserted
 * by nothing, which is exactly how a silent downgrade could have shipped.
 */

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { buildWebPreferences } from './window-options';

function preferences(allowDevTools: boolean) {
  return buildWebPreferences({
    preloadPath: '/app/out/preload/index.js',
    allowDevTools,
  });
}

describe('buildWebPreferences', () => {
  it('keeps the isolation baseline on for every window', () => {
    for (const allowDevTools of [true, false]) {
      const prefs = preferences(allowDevTools);
      expect(prefs.contextIsolation).toBe(true);
      expect(prefs.nodeIntegration).toBe(false);
      expect(prefs.sandbox).toBe(true);
      expect(prefs.webSecurity).toBe(true);
      expect(prefs.preload).toBe('/app/out/preload/index.js');
    }
  });

  it('only ever relaxes devtools, and only outside a packaged build', () => {
    expect(preferences(true).devTools).toBe(true);
    expect(preferences(false).devTools).toBe(false);
  });
});

describe('main process wiring', () => {
  it('builds every window from the security baseline, never inline', () => {
    const source = readFileSync('src/main/index.ts', 'utf8');
    expect(source).toContain('buildWebPreferences(');
    // An inline `webPreferences: { … }` literal would not be covered by the
    // assertions above, so no window may be configured that way.
    expect(source).not.toMatch(/webPreferences: *\{/);
  });
});
