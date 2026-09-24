/**
 * Client surface guard.
 *
 * The renderer reaches the backend only through `useApiClient()`, but a method
 * that exists in `packages/api-client/src/client.ts` can still be `undefined`
 * at runtime — a stale pre-bundled copy of the linked package does exactly
 * that. The symptom is not a crash: `api.listExams is not a function` throws a
 * TypeError, which used to read as "backend unreachable" and left pages
 * spinning.
 *
 * So: collect every `api.<method>` the renderer calls and assert the real
 * client object actually has it. Offline, no server needed.
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it, vi } from 'vitest';
import { createApiClient } from '@aistudy/api-client';

// Vitest runs with the project root as cwd; `import.meta.url` is not a file
// URL under the jsdom environment, so it cannot anchor the scan.
const RENDERER_SRC = join(process.cwd(), 'src', 'renderer', 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (entry === 'test' || entry === 'node_modules') return [];
    if (statSync(full).isDirectory()) return sourceFiles(full);
    return /\.(ts|tsx)$/.test(entry) && !entry.endsWith('.test.tsx') && !entry.endsWith('.test.ts')
      ? [full]
      : [];
  });
}

/**
 * `api.<method>(` from every renderer file that obtains the client through
 * `useApiClient()`. Files that name a different local `api` (the auth vault
 * bridge) are skipped, so the scan tracks the real convention instead of the
 * variable spelling.
 */
function clientMethodsUsed(): Set<string> {
  const used = new Set<string>();
  for (const file of sourceFiles(RENDERER_SRC)) {
    const text = readFileSync(file, 'utf8');
    if (!text.includes('useApiClient(')) continue;
    for (const match of text.matchAll(/\bapi\.([a-zA-Z0-9_]+)\s*\(/g)) {
      used.add(match[1]);
    }
  }
  return used;
}

describe('api client surface', () => {
  const client = createApiClient('http://localhost:8080', { getAccessToken: () => null }, {
    fetch: vi.fn() as unknown as typeof fetch,
  });

  it('finds the renderer sources it is supposed to cover', () => {
    expect(clientMethodsUsed().size).toBeGreaterThan(20);
  });

  it.each([...clientMethodsUsed()].sort())(
    'client implements api.%s() that the renderer calls',
    (method) => {
      expect(typeof (client as unknown as Record<string, unknown>)[method]).toBe('function');
    }
  );
});
