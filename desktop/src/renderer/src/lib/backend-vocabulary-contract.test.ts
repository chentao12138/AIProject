/**
 * Backend vocabulary drift guards.
 *
 * The OpenAPI contract types every status/kind field as a bare `string`, so
 * TypeScript cannot see a mismatch and the renderer has been guessing. Each
 * guess below shipped as a real defect:
 *
 *  - ingestion: modelled PENDING/RUNNING/SUCCEEDED (never emitted), so an
 *    uploaded source sat on "waiting" after its job hit PARTIAL_FAILED.
 *  - questionType: offered BOOLEAN / TEXT_ANSWER, which the backend rejects
 *    with 400 "unsupported questionType".
 *  - exam attempt: matched CREATED/PENDING/GRADED, so a fresh attempt skipped
 *    its start screen and every answer came back 409 NOT_STARTED.
 *  - practice session: matched FINISHED/COMPLETED, so a submitted session
 *    reopened as a blank answer form.
 *
 * These tests read the authoritative Java declarations out of the same
 * monorepo and fail with the offending names. They skip when `server/` is not
 * present (a frontend-only checkout).
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  EXAM_ATTEMPT_STATUSES,
  PRACTICE_SESSION_STATUSES,
  QUESTION_TYPES,
} from './format';
import { INGESTION_STATUSES } from './ingestion-status';

const SERVER = join(process.cwd(), '..', 'server', 'src', 'main', 'java', 'com', 'aistudy', 'server');

const VOCABULARIES = {
  'ingestion job status': {
    file: join(SERVER, 'ingestion', 'job', 'service', 'IngestionJobService.java'),
    read: (src: string) =>
      [...src.matchAll(/enum\s+IngestionStatus\s*\{([^}]*)\}/g)].flatMap((m) =>
        m[1].split(',').map((s) => s.trim()).filter((s) => /^[A-Z][A-Z0-9_]*$/.test(s))
      ),
    frontend: Object.values(INGESTION_STATUSES) as string[],
  },
  'question type': {
    file: join(SERVER, 'question', 'service', 'QuestionService.java'),
    read: (src: string) =>
      [...src.matchAll(/String\s+TYPE_[A-Z_]+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]),
    frontend: [...QUESTION_TYPES],
  },
  'exam attempt status': {
    file: join(SERVER, 'exam', 'service', 'ExamAttemptService.java'),
    read: (src: string) =>
      [...src.matchAll(/String\s+STATUS_[A-Z_]+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]),
    frontend: Object.values(EXAM_ATTEMPT_STATUSES) as string[],
  },
  'practice session status': {
    file: join(SERVER, 'practice', 'service', 'PracticeSessionService.java'),
    read: (src: string) =>
      [...src.matchAll(/String\s+STATUS_[A-Z_]+\s*=\s*"([A-Z_]+)"/g)].map((m) => m[1]),
    frontend: Object.values(PRACTICE_SESSION_STATUSES) as string[],
  },
} as const;

for (const [name, spec] of Object.entries(VOCABULARIES)) {
  describe.skipIf(!existsSync(spec.file))(`${name} vocabulary matches the backend`, () => {
    const backend = () => {
      const members = spec.read(readFileSync(spec.file, 'utf8'));
      expect(members.length, `no ${name} constants found in ${spec.file}`).toBeGreaterThan(0);
      return new Set(members);
    };

    it('finds the declarations in the backend source', () => {
      expect(backend().size).toBeGreaterThan(0);
    });

    it('models exactly the values the backend can emit', () => {
      const b = backend();
      const f = new Set(spec.frontend);
      expect(
        {
          onlyBackend: [...b].filter((v) => !f.has(v)),
          onlyFrontend: [...f].filter((v) => !b.has(v)),
        },
        `${name} drift between ${spec.file} and the renderer`
      ).toEqual({ onlyBackend: [], onlyFrontend: [] });
    });
  });
}
