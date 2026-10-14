/**
 * Attempt history rows (W07 / A6.3 / C11) — the pure half.
 *
 * Two server fields decide a row: `status` (`ExamAttemptService.STATUS_*`:
 * NOT_STARTED / IN_PROGRESS / SUBMITTED, pinned by
 * lib/backend-vocabulary-contract.test.ts) and `gradingStatus`
 * (GRADED / PARTIALLY_GRADED / UNGRADED, null while the attempt is open) — the
 * field this round asked for, so the graded / ungraded split is now the
 * server's statement rather than an inference from "did a number arrive".
 * The bug these tests gate is the one already fixed once on the result view:
 * a null score read as 0 分 / 答错.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { EXAM_GRADING_STATUSES } from '../../lib/format';
import {
  ATTEMPT_PROGRESS_ACTIONS,
  ATTEMPT_PROGRESS_NOTES,
  attemptProgressOf,
  attemptScoreText,
  canResumeAttempt,
} from './exam-attempt-history';

describe('attemptProgressOf (the server\'s own two fields)', () => {
  it('maps the states the learner can be in', () => {
    expect(attemptProgressOf({ status: 'IN_PROGRESS' })).toBe('in-progress');
    expect(attemptProgressOf({ status: 'NOT_STARTED' })).toBe('not-started');
    // Submitted, nothing graded yet — including the `null` the server sends
    // while the attempt has not been graded at all.
    expect(attemptProgressOf({ status: 'SUBMITTED', gradingStatus: null })).toBe(
      'awaiting-grade'
    );
    expect(attemptProgressOf({ status: 'SUBMITTED', gradingStatus: 'UNGRADED' })).toBe(
      'awaiting-grade'
    );
    expect(attemptProgressOf({ status: 'SUBMITTED', gradingStatus: 'GRADED', score: 12 })).toBe(
      'graded'
    );
  });

  // This distinction is the whole reason `gradingStatus` was requested: a
  // partial score and a final one arrive as numbers either way.
  it('separates a partially graded attempt from a final one', () => {
    expect(
      attemptProgressOf({
        status: 'SUBMITTED',
        gradingStatus: 'PARTIALLY_GRADED',
        score: 6,
        maxScore: 20,
      })
    ).toBe('partially-graded');
    expect(ATTEMPT_PROGRESS_NOTES['partially-graded']).toContain('主观题仍待人工');
    expect(ATTEMPT_PROGRESS_NOTES.graded).toBe('已评分');
  });

  it('no longer infers a grade from the presence of a score', () => {
    // A number with no grading statement is not something to label 已评分:
    // the server decides that, and said nothing here.
    expect(attemptProgressOf({ status: 'SUBMITTED', score: 12 })).toBe('awaiting-grade');
    expect(attemptScoreText({ status: 'SUBMITTED', score: 12, maxScore: 20 })).toBe('—');
  });

  it('normalizes case and stray whitespace instead of inventing a status', () => {
    expect(attemptProgressOf({ status: '  submitted ', gradingStatus: ' graded ' })).toBe(
      'graded'
    );
    expect(attemptProgressOf({ status: 'Submitted', gradingStatus: 'UNGRADED' })).toBe(
      'awaiting-grade'
    );
  });

  it('names anything the backend never emits as unknown', () => {
    // CREATED / PENDING / GRADED (as a status) are exactly the guesses that
    // shipped defects.
    for (const status of ['CREATED', 'PENDING', 'EXPIRED', '', null, undefined]) {
      expect(attemptProgressOf({ status, gradingStatus: 'GRADED', score: 8 })).toBe('unknown');
    }
  });
});

describe('score rendering: null is never 0 and never a verdict', () => {
  it('shows the numbers only where the server says it graded', () => {
    expect(
      attemptScoreText({ status: 'SUBMITTED', gradingStatus: 'GRADED', score: 12, maxScore: 20 })
    ).toBe('12 / 20');
    // 0 分 is a score the server gave; it must be printed, not hidden.
    expect(
      attemptScoreText({ status: 'SUBMITTED', gradingStatus: 'GRADED', score: 0, maxScore: 20 })
    ).toBe('0 / 20');
    // A partial score is shown AS partial, by the row's note, not hidden.
    expect(
      attemptScoreText({
        status: 'SUBMITTED',
        gradingStatus: 'PARTIALLY_GRADED',
        score: 6,
        maxScore: 20,
      })
    ).toBe('6 / 20');
  });

  it('shows — whenever the server has not graded', () => {
    expect(attemptScoreText({ status: 'SUBMITTED', gradingStatus: 'UNGRADED' })).toBe('—');
    expect(attemptScoreText({ status: 'SUBMITTED', gradingStatus: null, score: null })).toBe('—');
    expect(attemptScoreText({ status: 'IN_PROGRESS', score: null, maxScore: 20 })).toBe('—');
    expect(attemptScoreText({ status: 'NOT_STARTED' })).toBe('—');
    // A row whose status the client cannot map is not graded either.
    expect(attemptScoreText({ status: 'GRADED', score: null })).toBe('—');
  });

  it('does not claim a max score without a score', () => {
    // `— / 20` would read as "0 of 20"; the whole cell stays empty instead.
    expect(
      attemptScoreText({ status: 'SUBMITTED', gradingStatus: 'GRADED', maxScore: 20 })
    ).toBe('—');
  });

  it('keeps the cases distinguishable by copy, not by colour alone', () => {
    expect(ATTEMPT_PROGRESS_NOTES['in-progress']).toContain('进行中');
    expect(ATTEMPT_PROGRESS_NOTES['awaiting-grade']).toContain('等待评分');
    expect(ATTEMPT_PROGRESS_NOTES.graded).toContain('已评分');
    // An ungraded attempt is not a verdict — that is the bug this copy exists
    // to avoid repeating (see ExamAttemptPage's isCorrect tri-state).
    expect(ATTEMPT_PROGRESS_NOTES['awaiting-grade']).not.toContain('答错');
    expect(ATTEMPT_PROGRESS_NOTES['awaiting-grade']).not.toMatch(/\d/);
  });
});

describe('the resume affordance follows the server, never the other way round', () => {
  it('offers resume only for an attempt still open on the server', () => {
    expect(canResumeAttempt('IN_PROGRESS')).toBe(true);
    expect(canResumeAttempt('NOT_STARTED')).toBe(true);
    // Submitted / finalised attempts are closed: no 继续作答 for them.
    expect(canResumeAttempt('SUBMITTED')).toBe(false);
    expect(canResumeAttempt('EXPIRED')).toBe(false);
    expect(canResumeAttempt(undefined)).toBe(false);
  });

  it('labels a closed attempt as the read-only result it opens', () => {
    expect(ATTEMPT_PROGRESS_ACTIONS['in-progress']).toBe('继续作答');
    expect(ATTEMPT_PROGRESS_ACTIONS['not-started']).toBe('开始作答');
    expect(ATTEMPT_PROGRESS_ACTIONS.graded).toBe('查看成绩');
    expect(ATTEMPT_PROGRESS_ACTIONS['partially-graded']).toBe('查看成绩');
    // Nothing was graded, so there is no 成绩 to view yet — only the answer sheet.
    expect(ATTEMPT_PROGRESS_ACTIONS['awaiting-grade']).toBe('查看作答');
    // An unrecognised status promises no action beyond opening the row.
    expect(ATTEMPT_PROGRESS_ACTIONS.unknown).toBe('查看作答');
  });
});

/**
 * The pin: `EXAM_GRADING_STATUSES` must equal the contract's own enum, or the
 * labels above are guesses again. Reads the committed snapshot (skipped when the
 * generated artifacts are not next to this checkout).
 */
describe('gradingStatus vocabulary (C11)', () => {
  const CONTRACT_PATH = join(
    process.cwd(),
    '..',
    'packages',
    'api-client',
    'src',
    'generated',
    'openapi.json'
  );
  const hasContract = existsSync(CONTRACT_PATH);
  const publishedEnum: string[] | undefined = hasContract
    ? ((
        JSON.parse(readFileSync(CONTRACT_PATH, 'utf8'))?.components?.schemas
          ?.AttemptSummaryView as
        | { properties?: { gradingStatus?: { enum?: string[] } } }
        | undefined
      )?.properties?.gradingStatus?.enum ?? undefined)
    : undefined;

  it.skipIf(!hasContract)('matches the enum the server publishes', () => {
    expect(publishedEnum).toEqual([...Object.values(EXAM_GRADING_STATUSES)]);
  });

  it('declares the three states this module handles', () => {
    expect(Object.values(EXAM_GRADING_STATUSES)).toEqual([
      'GRADED',
      'PARTIALLY_GRADED',
      'UNGRADED',
    ]);
  });
});
