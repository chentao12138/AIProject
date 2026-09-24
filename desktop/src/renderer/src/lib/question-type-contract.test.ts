/**
 * Question type drift guard.
 *
 * `questionType` is a plain `type: string` in the OpenAPI contract, so nothing
 * but the backend's own constants says which values are legal. The renderer
 * offered BOOLEAN and TEXT_ANSWER, neither of which exists — creating a true
 * false question failed with 400 "unsupported questionType: BOOLEAN" and the
 * filter dropdown silently matched nothing.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { QUESTION_TYPES } from './format';

const QUESTION_SERVICE = join(
  process.cwd(),
  '..',
  'server',
  'src',
  'main',
  'java',
  'com',
  'aistudy',
  'server',
  'question',
  'service',
  'QuestionService.java'
);

/** `String TYPE_SINGLE_CHOICE = "SINGLE_CHOICE"` */
function backendTypes(): string[] {
  const src = readFileSync(QUESTION_SERVICE, 'utf8');
  return [...src.matchAll(/String\s+TYPE_[A-Z_]+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]);
}

const available = existsSync(QUESTION_SERVICE);

describe.skipIf(!available)('question type vocabulary matches the backend', () => {
  it('finds the TYPE_* constants in QuestionService', () => {
    expect(backendTypes().length).toBeGreaterThan(0);
  });

  it('offers exactly the types the backend accepts', () => {
    const backend = new Set<string>(backendTypes());
    const frontend = new Set<string>(QUESTION_TYPES);
    expect(
      {
        onlyBackend: [...backend].filter((t) => !frontend.has(t)),
        onlyFrontend: [...frontend].filter((t) => !backend.has(t)),
      },
      'questionType drift between QuestionService.java and lib/format.ts'
    ).toEqual({ onlyBackend: [], onlyFrontend: [] });
  });
});
