/**
 * Exam conflict-code contract guard (C12 / A9.3).
 *
 * `features/exams/exam-error.ts` maps each exam conflict to Chinese copy and a
 * recovery, and it now branches on `ProblemDetail.code` — the `detail` sentences
 * it used to match were never a contract. That makes the code list itself a
 * claim about the backend, so it is checked against the backend's own
 * `ApiErrorCodes` declarations, the same way the ingestion and vocabulary
 * guards work. Skips when `server/` is not next to this checkout.
 *
 * TWO ASSERTIONS, BOTH ALLOWED TO FAIL
 *  1. every `EXAM_*` code the Java declares must be handled by the UI — a code
 *     the backend adds and we do not map silently degrades to the generic
 *     conflict copy, which is the defect C12 was raised to fix;
 *  2. the codes this copy does NOT declare yet must be exactly the list below.
 *
 * (2) exists because this workspace's `server/` reference copy is behind the
 * running instance (its migrations stop at V057 while the server has been
 * migrated to V066), so seven of the ten shipped codes cannot be read from it.
 * When the copy is synced with `main`, assertion (2) fails on purpose and the
 * answer is to empty the lag list — not to delete the guard.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { EXAM_ERROR_CODES } from '../features/exams/exam-error';

const CODES_PATH = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'java',
  'com',
  'aistudy',
  'server',
  'common',
  'problem',
  'ApiErrorCodes.java'
);

const source = existsSync(CODES_PATH) ? readFileSync(CODES_PATH, 'utf8') : '';

/** `public static final String NAME = "VALUE";` */
function declared(source: string): { name: string; value: string }[] {
  const out: { name: string; value: string }[] = [];
  for (const match of source.matchAll(
    /public static final String ([A-Z_0-9]+)\s*=\s*"([A-Z_0-9]+)"/g
  )) {
    out.push({ name: match[1], value: match[2] });
  }
  return out;
}

const entries = declared(source);
const declaredExamCodes = entries
  .filter((e) => e.name.startsWith('EXAM_'))
  .map((e) => e.value);

const CODES_AWAITING_WORKSPACE_SYNC = [
  'EXAM_ACTIVE_ATTEMPT_EXISTS',
  'EXAM_ATTEMPT_ALREADY_SUBMITTED',
  'EXAM_RESULT_NOT_AVAILABLE',
  'EXAM_RESULT_MISSING',
  'EXAM_ANSWER_NOT_GRADABLE',
  'EXAM_ATTEMPT_STALE',
  'EXAM_ANSWER_STALE',
] as const;

describe.skipIf(!source)('exam conflict codes (C12)', () => {
  it('actually parsed declarations out of ApiErrorCodes.java', () => {
    // Without this, a bad regex would make every assertion below vacuously true.
    expect(entries.length).toBeGreaterThan(5);
  });

  it('handles every exam code the backend declares here', () => {
    const unhandled = declaredExamCodes.filter(
      (code) => !(EXAM_ERROR_CODES as readonly string[]).includes(code)
    );
    expect(unhandled).toEqual([]);
  });

  it('lists exactly the shipped-but-unreadable codes as pending sync', () => {
    const missing = EXAM_ERROR_CODES.filter(
      (code) => !declaredExamCodes.includes(code)
    ).sort();
    expect(missing).toEqual([...CODES_AWAITING_WORKSPACE_SYNC].sort());
  });

  it('keeps the server declaration style: the value equals the constant name', () => {
    const renamed = entries.filter((e) => e.name !== e.value);
    expect(renamed).toEqual([]);
  });
});
