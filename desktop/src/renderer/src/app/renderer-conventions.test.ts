/**
 * Renderer architecture conventions (FE-ARCH stage C/D).
 *
 * The README and the ADRs assert several renderer-wide rules in prose. Prose
 * is how they got lost once already (`window.location.hash` writes, mirrored
 * query state, "tokens never in localStorage"), so each rule below is a scan
 * over the real source instead of a comment.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, sep } from 'node:path';
import { describe, expect, it } from 'vitest';

/** Test helpers may fake anything; they are not application code. */
const SKIP = [`${sep}test${sep}`, '.test.'];

function sourceFiles(dir: string): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      found.push(...sourceFiles(full));
    } else if (/\.(ts|tsx)$/.test(entry) && !SKIP.some((skip) => full.includes(skip))) {
      found.push(full);
    }
  }
  return found;
}

/** `file:line: text` for every code line in `dirs` matching `pattern`. */
function offenders(pattern: RegExp, dirs: string[]): string[] {
  const hits: string[] = [];
  for (const dir of dirs) {
    for (const file of sourceFiles(dir)) {
      readFileSync(file, 'utf8')
        .split('\n')
        .forEach((line, index) => {
          const code = line.trim();
          // Documentation may name the forbidden thing (these rules started
          // as prose); only executable lines are scanned.
          if (code.startsWith('*') || code.startsWith('//') || code.startsWith('/*')) {
            return;
          }
          if (pattern.test(line)) {
            hits.push(`${file}:${index + 1}: ${code}`);
          }
        });
    }
  }
  return hits;
}

const UI = ['src/renderer/src/app', 'src/renderer/src/features'];
const ALL = ['src/renderer/src'];

describe('renderer conventions', () => {
  it('never re-implements an id guard inline', () => {
    // lib/queries.ts and requireId own this. A page-local copy is how a
    // guard and its cache key ended up disagreeing about "no id".
    expect(offenders(/Promise\.reject\(new Error\(/, UI)).toEqual([]);
  });

  it('never bakes a placeholder id into a cache key', () => {
    expect(offenders(/queryKeys\.[a-zA-Z]+\([^)]*\?\? *0/, UI)).toEqual([]);
  });

  it('never re-types the authorization-safe not-found copy', () => {
    expect(offenders(/资源不存在或当前不可访问/, UI)).toEqual([]);
  });

  it('never reaches the network outside the shared api client', () => {
    // The one sanctioned caller is the 401-refresh fetch wrapper.
    expect(offenders(/\bfetch\(|XMLHttpRequest|from ['"]axios/, UI)).toEqual([]);
    expect(
      offenders(/await fetch\(|globalThis\.fetch|XMLHttpRequest|from ['"]axios/, [
        'src/renderer/src/lib',
      ])
    ).toEqual([]);
  });

  it('never persists session material from the renderer', () => {
    // ADR-026 / ADR-037: access token in memory, refresh token in safeStorage.
    expect(offenders(/localStorage|sessionStorage|document\.cookie/, ALL)).toEqual(
      []
    );
  });

  it('never touches Node or Electron from the renderer', () => {
    expect(
      offenders(/from ['"]node:|require\(['"]|window\.require|process\./, ALL)
    ).toEqual([]);
  });
});
