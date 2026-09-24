/**
 * Ingestion status drift guard.
 *
 * `lib/ingestion-status.ts` classifies backend job statuses by exact string.
 * It previously modelled PENDING/RUNNING/SUCCEEDED, which the backend never
 * emits (it emits QUEUED/IMPORTING/.../PARTIAL_FAILED), so a finished job was
 * classified 'unknown': polling stopped and the workbench reported "waiting"
 * forever. The contract types `status` as a bare string, so TypeScript cannot
 * catch this — the enum in Java is the only authority.
 *
 * This test reads that enum out of the same monorepo and fails when the two
 * lists disagree, naming the missing members.
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { INGESTION_STATUSES } from './ingestion-status';

/**
 * Statuses that mean "ingestion job" and nothing else. `PUBLISHED` and
 * `FAILED` are deliberately excluded: knowledge points, questions, exams and
 * assets use those words for their own lifecycle, so flagging them here would
 * only teach the next reader to switch the guard off.
 */
const BACKEND_STATUSES = (Object.values(INGESTION_STATUSES) as string[]).filter(
  (s) => s !== 'PUBLISHED' && s !== 'FAILED'
);

/**
 * Ratchet: no renderer file may compare a job status against a bare string
 * literal. Three separate sites each hardcoding their own guess at this
 * vocabulary is exactly how the workbench came to show "waiting" for a job
 * that had already reached PARTIAL_FAILED. Classify through
 * `classifyIngestionStatus` instead.
 */
describe('ingestion status is read through one classifier', () => {
  const ROOT = join(process.cwd(), 'src', 'renderer', 'src');
  const ALLOWED = new Set([
    join('lib', 'ingestion-status.ts'),
    join('lib', 'ingestion-status-contract.test.ts'),
  ]);

  const offenders: string[] = [];
  (function walk(dir: string) {
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) {
        walk(full);
        continue;
      }
      if (!/\.(ts|tsx)$/.test(entry) || entry.endsWith('.test.tsx') || entry.endsWith('.test.ts')) {
        continue;
      }
      if (ALLOWED.has(full.slice(ROOT.length + 1))) continue;
      const text = readFileSync(full, 'utf8');
      const hits = BACKEND_STATUSES.filter((s) =>
        new RegExp(`['"\`]${s}['"\`]`).test(text)
      );
      if (hits.length) {
        offenders.push(`${full.slice(ROOT.length + 1)}: ${hits.join(', ')}`);
      }
    }
  })(ROOT);

  it('has no bare ingestion status literals outside the classifier', () => {
    expect(offenders).toEqual([]);
  });
});

const JAVA_SERVICE = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'java',
  'com',
  'aistudy',
  'server',
  'ingestion',
  'job',
  'service',
  'IngestionJobService.java'
);

const backendAvailable = existsSync(JAVA_SERVICE);

/** `public enum IngestionStatus { A, B, C }` */
function javaEnumMembers(): string[] | null {
  const src = readFileSync(JAVA_SERVICE, 'utf8');
  const match = src.match(/enum\s+IngestionStatus\s*\{([^}]*)\}/);
  if (!match) return null;
  return match[1]
    .split(',')
    .map((s) => s.replace(/\/\/.*$/m, '').trim())
    .filter((s) => /^[A-Z][A-Z0-9_]*$/.test(s));
}

describe.skipIf(!backendAvailable)(
  'ingestion status vocabulary matches the backend enum',
  () => {
    it('finds IngestionStatus in the backend source', () => {
      expect(javaEnumMembers()).not.toBeNull();
    });

    it('models exactly the statuses the backend can emit', () => {
      const backend = new Set<string>(javaEnumMembers()!);
      const frontend = new Set<string>(Object.values(INGESTION_STATUSES));
      const onlyBackend = [...backend].filter((s) => !frontend.has(s));
      const onlyFrontend = [...frontend].filter((s) => !backend.has(s));
      expect(
        { onlyBackend, onlyFrontend },
        `ingestion status drift — backend has ${[...backend].join(', ')}`
      ).toEqual({ onlyBackend: [], onlyFrontend: [] });
    });
  }
);
