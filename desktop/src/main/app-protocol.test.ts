/**
 * app:// protocol pure-function tests (ELECTRON-CORS-001-A).
 *
 * - app://aistudy/ -> index.html
 * - app://aistudy/#/spaces navigation allowed
 * - app://aistudy/assets/x.js legal
 * - app://evil/ rejected
 * - file:///... rejected
 * - https://example.com rejected
 * - raw/encoded traversal rejected
 * - renderer root escape rejected
 * - malformed input rejected
 */

import { describe, expect, it } from 'vitest';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  APP_ORIGIN,
  isAllowedAppNavigation,
  resolveAppUrlPath,
} from './app-protocol';

const root = join(tmpdir(), 'aistudy-renderer-root-test');

describe('isAllowedAppNavigation', () => {
  it('allows the app origin itself', () => {
    expect(isAllowedAppNavigation(`${APP_ORIGIN}/`)).toBe(true);
    expect(isAllowedAppNavigation(APP_ORIGIN)).toBe(true);
  });

  it('allows hash routes on the app origin', () => {
    expect(isAllowedAppNavigation(`${APP_ORIGIN}/#/spaces`)).toBe(true);
    expect(
      isAllowedAppNavigation(`${APP_ORIGIN}/#/spaces/1/knowledge/5`)
    ).toBe(true);
  });

  it('rejects foreign app hosts', () => {
    expect(isAllowedAppNavigation('app://evil/')).toBe(false);
    expect(isAllowedAppNavigation('app://aistudy.evil/')).toBe(false);
    expect(isAllowedAppNavigation('app://evil.aistudy/')).toBe(false);
  });

  it('rejects file:// and remote http(s)', () => {
    expect(isAllowedAppNavigation('file:///C:/other-file.html')).toBe(false);
    expect(isAllowedAppNavigation('https://example.com/')).toBe(false);
    expect(isAllowedAppNavigation('http://localhost:9999/')).toBe(false);
  });

  it('rejects malformed input', () => {
    expect(isAllowedAppNavigation('not-a-url')).toBe(false);
    expect(isAllowedAppNavigation('')).toBe(false);
  });

  it('rejects javascript:, data:, and empty hosts (PHASE 20)', () => {
    expect(isAllowedAppNavigation('javascript:alert(1)')).toBe(false);
    expect(isAllowedAppNavigation('data:text/html,<script>1</script>')).toBe(
      false
    );
    expect(isAllowedAppNavigation('app://')).toBe(false);
    expect(isAllowedAppNavigation('app:///path')).toBe(false);
  });

  it('rejects the app host with a port (PHASE 20)', () => {
    expect(isAllowedAppNavigation('app://aistudy:8080/')).toBe(false);
  });
});

describe('resolveAppUrlPath', () => {
  it('maps root to index.html', () => {
    expect(resolveAppUrlPath(`${APP_ORIGIN}/`, root)).toBe(
      join(root, 'index.html')
    );
    expect(resolveAppUrlPath(APP_ORIGIN, root)).toBe(join(root, 'index.html'));
  });

  it('maps asset URLs inside the root', () => {
    expect(resolveAppUrlPath(`${APP_ORIGIN}/assets/x.js`, root)).toBe(
      join(root, 'assets', 'x.js')
    );
    expect(resolveAppUrlPath(`${APP_ORIGIN}/assets/index-a1b2.css`, root)).toBe(
      join(root, 'assets', 'index-a1b2.css')
    );
  });

  it('rejects other hosts and schemes', () => {
    expect(resolveAppUrlPath('app://evil/', root)).toBeNull();
    expect(resolveAppUrlPath('file:///C:/secret.txt', root)).toBeNull();
    expect(resolveAppUrlPath('https://example.com/', root)).toBeNull();
  });

  it('rejects raw and encoded traversal', () => {
    // Fully-encoded dot segments (%2e%2e) are collapsed by the WHATWG
    // URL parser itself (standard scheme) and cannot escape the origin.
    expect(resolveAppUrlPath(`${APP_ORIGIN}/../secret.txt`, root)).toBe(
      join(root, 'secret.txt')
    );
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/%2e%2e/%2e%2e/secret.txt`, root)
    ).toBe(join(root, 'secret.txt'));
    expect(resolveAppUrlPath(`${APP_ORIGIN}/a/%2e%2e/secret.txt`, root)).toBe(
      join(root, 'secret.txt')
    );
    // Decoded traversal (encoded separators hide ".." from the parser;
    // decodeURIComponent reveals it) must be rejected by the root check.
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/..%2f..%2fsecret.txt`, root)
    ).toBeNull();
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/%2e%2e%2fsecret.txt`, root)
    ).toBeNull();
  });

  it('rejects malformed percent-encoding', () => {
    expect(resolveAppUrlPath(`${APP_ORIGIN}/%zz`, root)).toBeNull();
  });

  it('rejects URL-construction escapes (standard-scheme normalization)', () => {
    // "app://aistudy" is a hierarchical standard scheme: the URL parser
    // collapses ".." segments itself, so what remains must stay in root.
    expect(resolveAppUrlPath(`${APP_ORIGIN}/assets/../../etc/passwd`, root)).toBe(
      join(root, 'etc', 'passwd')
    );
  });

  it('ignores query strings for filesystem resolution (PHASE 20)', () => {
    // Query strings can never alter the resolved path.
    expect(resolveAppUrlPath(`${APP_ORIGIN}/assets/x.js?v=1&x=2`, root)).toBe(
      join(root, 'assets', 'x.js')
    );
    expect(resolveAppUrlPath(`${APP_ORIGIN}/?next=..%2f..%2fetc`, root)).toBe(
      join(root, 'index.html')
    );
  });

  it('ignores hash fragments for resource resolution (PHASE 20)', () => {
    expect(resolveAppUrlPath(`${APP_ORIGIN}/#/spaces`, root)).toBe(
      join(root, 'index.html')
    );
    expect(resolveAppUrlPath(`${APP_ORIGIN}/#/spaces/1/knowledge/5`, root)).toBe(
      join(root, 'index.html')
    );
    expect(resolveAppUrlPath(`${APP_ORIGIN}/assets/x.js#frag`, root)).toBe(
      join(root, 'assets', 'x.js')
    );
  });

  it('rejects encoded separators that hide traversal (PHASE 20)', () => {
    // %5c decodes to the Windows path separator; a decoded ".." + "\"
    // sequence must not escape the renderer root.
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/..%5c..%5csecret.txt`, root)
    ).toBeNull();
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/%2e%2e%5csecret.txt`, root)
    ).toBeNull();
    expect(
      resolveAppUrlPath(`${APP_ORIGIN}/a%2f..%2f..%2fsecret.txt`, root)
    ).toBeNull();
  });

  it('keeps double-encoded traversal inert (single decode contract, PHASE 20)', () => {
    // After ONE decodeURIComponent the string is still fully encoded:
    // "%2e%2e%2f" is just a literal filename, never a path segment.
    const result = resolveAppUrlPath(
      `${APP_ORIGIN}/%252e%252e%252fsecret.txt`,
      root
    );
    expect(result).not.toBeNull();
    expect(result?.startsWith(root)).toBe(true);
  });

  it('rejects javascript:/data:/file:/http(s) in resource resolution (PHASE 20)', () => {
    expect(resolveAppUrlPath('javascript:alert(1)', root)).toBeNull();
    expect(resolveAppUrlPath('data:text/html,<b>x</b>', root)).toBeNull();
    expect(resolveAppUrlPath('file:///C:/secret.txt', root)).toBeNull();
    expect(resolveAppUrlPath('https://example.com/secret.txt', root)).toBeNull();
    expect(resolveAppUrlPath('http://localhost:9999/secret.txt', root)).toBeNull();
  });

  it('rejects empty-host and ported app URLs in resource resolution (PHASE 20)', () => {
    expect(resolveAppUrlPath('app:///index.html', root)).toBeNull();
    expect(resolveAppUrlPath('app://aistudy:8080/index.html', root)).toBeNull();
  });
});
