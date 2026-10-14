/**
 * Route contract test (A-series hardening).
 *
 * The router used to be the only place that knew the path shapes while every
 * page built its own target string by hand — so `/exams/:examId` navigations
 * silently landed on NotFound and an `edit` route stayed unreachable. This
 * test pins the two together: every literal navigation target in the renderer
 * must resolve to a registered route, and routing must go through the router
 * (no `window.location.hash` writes).
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, posix } from 'node:path';
import { describe, expect, it } from 'vitest';
import { router } from './router';

interface RouteLike {
  path?: string;
  index?: boolean;
  children?: RouteLike[];
}

/**
 * Resolve the router config into full path patterns (posix join, never
 * `node:path`, which would emit backslashes on Windows). The catch-all is
 * dropped: a target that only matches it is a broken link.
 */
function collectRoutePatterns(routes: RouteLike[], parent = ''): string[] {
  const out = new Set<string>();
  for (const route of routes) {
    const raw = (route.path ?? '').replace(/\\/g, '/');
    if (raw.startsWith('*')) {
      continue;
    }
    const full = posix.join('/', parent, raw);
    out.add(full);
    for (const child of collectRoutePatterns(route.children ?? [], full)) {
      out.add(child);
    }
  }
  return [...out];
}

/**
 * Segment match where either side may be a placeholder: navigation targets
 * interpolate real ids this test can never know, so those segments are
 * wildcards just like the router's `:params`.
 */
function matchesPattern(pattern: string, target: string): boolean {
  const p = pattern.split('/').filter(Boolean);
  const t = target.split('/').filter(Boolean);
  if (p.length !== t.length) {
    return false;
  }
  return p.every(
    (segment, i) =>
      segment.startsWith(':') || t[i].startsWith(':') || segment === t[i]
  );
}

function sourceFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      found.push(...sourceFiles(full));
    } else if (/\.(ts|tsx)$/.test(entry) && !/\.test\.(ts|tsx)$/.test(entry)) {
      found.push(full);
    }
  }
  return found;
}

/**
 * Turn a navigation template into a matchable path:
 *  - `#` (hash router) and everything from a query string on are dropped
 *  - a path segment that interpolates anything becomes one `:param`
 *    (a segment may concatenate several expressions, e.g. `?page=` builders)
 *  - relative targets are resolved by the router itself, so they are skipped
 */
function normalizeTarget(raw: string): string | null {
  const withoutQuery = raw.replace(/^#/, '').split('?')[0];
  const segments = withoutQuery.split('/').map((segment) =>
    segment.includes('${') ? ':param' : segment
  );
  const path = segments.join('/');
  return path.startsWith('/') ? path : null;
}

function navigationTargets(file: string): string[] {
  const source = readFileSync(file, 'utf8');
  const patterns = [
    /\bnavigate\(\s*(['"`])([^'"`]+)\1/g,
    /\bto=\{?\s*(['"`])([^'"`]+)\1/g,
  ];
  const targets: string[] = [];
  for (const pattern of patterns) {
    for (const match of source.matchAll(pattern)) {
      targets.push(match[2]);
    }
  }
  return targets;
}

const ROUTE_PATTERNS = collectRoutePatterns(
  router.routes as unknown as RouteLike[]
);

describe('route contract', () => {
  it('reads the real router config', () => {
    for (const expected of [
      '/login',
      '/spaces',
      '/spaces/:spaceId/sources',
      '/spaces/:spaceId/exams',
      '/spaces/:spaceId/notes',
      '/spaces/:spaceId/notes/:noteId',
      '/spaces/:spaceId/questions/:questionId/edit',
    ]) {
      expect(ROUTE_PATTERNS).toContain(expected);
    }
  });

  it('resolves every navigation target to a registered route', () => {
    const offenders: string[] = [];
    for (const file of sourceFiles('src/renderer/src')) {
      for (const target of navigationTargets(file)) {
        const normalized = normalizeTarget(target);
        if (normalized === null) {
          continue;
        }
        if (
          !ROUTE_PATTERNS.some((pattern) => matchesPattern(pattern, normalized))
        ) {
          offenders.push(`${file}: ${normalized}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });

  it('never bypasses the router with window.location.hash writes', () => {
    const offenders = sourceFiles('src/renderer/src').filter((file) =>
      /location\.hash\s*=/.test(readFileSync(file, 'utf8'))
    );
    expect(offenders).toEqual([]);
  });
});
