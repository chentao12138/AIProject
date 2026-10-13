/**
 * Attempt history rows (W07 / A6.3 / C11) — the pure half.
 *
 * `status` is the SERVER's vocabulary (`ExamAttemptService.STATUS_*`:
 * NOT_STARTED / IN_PROGRESS / SUBMITTED, pinned by
 * lib/backend-vocabulary-contract.test.ts) and the contract's summary row
 * carries no grading status, so the graded/ungraded split is decided by whether
 * the server actually sent a `score`. The bug these tests gate is the one
 * already fixed once on the result view: a null score read as 0 分 / 答错.
 */

import { describe, expect, it } from 'vitest';
import {
  ATTEMPT_PROGRESS_ACTIONS,
  ATTEMPT_PROGRESS_NOTES,
  attemptProgressOf,
  attemptScoreText,
  canResumeAttempt,
} from './exam-attempt-history';

describe('attemptProgressOf (the server\'s own status vocabulary)', () => {
  it('maps the three states the learner can be in', () => {
    expect(attemptProgressOf('IN_PROGRESS', null)).toBe('in-progress');
    // SUBMITTED with no score yet: graded is the server's job, not ours.
    expect(attemptProgressOf('SUBMITTED', null)).toBe('awaiting-grade');
    expect(attemptProgressOf('SUBMITTED', undefined)).toBe('awaiting-grade');
    expect(attemptProgressOf('SUBMITTED', 12)).toBe('graded');
    expect(attemptProgressOf('NOT_STARTED', null)).toBe('not-started');
  });

  it('normalizes case and stray whitespace instead of inventing a status', () => {
    expect(attemptProgressOf('  submitted ', 3)).toBe('graded');
    expect(attemptProgressOf('Submitted', null)).toBe('awaiting-grade');
  });

  it('names anything the backend never emits as unknown', () => {
    // CREATED / PENDING / GRADED are exactly the guesses that shipped defects.
    for (const status of ['CREATED', 'PENDING', 'GRADED', 'EXPIRED', '', null, undefined]) {
      expect(attemptProgressOf(status, 8)).toBe('unknown');
    }
  });

  it('treats a score of 0 as a real score, not as "no score"', () => {
    expect(attemptProgressOf('SUBMITTED', 0)).toBe('graded');
  });
});

describe('score rendering: null is never 0 and never a verdict', () => {
  it('shows the numbers only for a graded attempt', () => {
    expect(attemptScoreText({ status: 'SUBMITTED', score: 12, maxScore: 20 })).toBe('12 / 20');
    // 0 分 is a score the server gave; it must be printed, not hidden.
    expect(attemptScoreText({ status: 'SUBMITTED', score: 0, maxScore: 20 })).toBe('0 / 20');
  });

  it('shows — for an attempt that has no score yet', () => {
    expect(attemptScoreText({ status: 'SUBMITTED', score: null, maxScore: null })).toBe('—');
    expect(attemptScoreText({ status: 'SUBMITTED' })).toBe('—');
    expect(attemptScoreText({ status: 'IN_PROGRESS', score: null, maxScore: 20 })).toBe('—');
    expect(attemptScoreText({ status: 'NOT_STARTED' })).toBe('—');
    // A row whose status the client cannot map is not graded either.
    expect(attemptScoreText({ status: 'GRADED', score: null })).toBe('—');
  });

  it('does not claim a max score without a score', () => {
    // `— / 20` would read as "0 of 20"; the whole cell stays empty instead.
    expect(attemptScoreText({ status: 'SUBMITTED', maxScore: 20 })).toBe('—');
  });

  it('keeps the three cases distinguishable by copy, not by colour alone', () => {
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
    expect(ATTEMPT_PROGRESS_ACTIONS['awaiting-grade']).toBe('查看成绩');
    // An unrecognised status promises no action beyond opening the row.
    expect(ATTEMPT_PROGRESS_ACTIONS.unknown).toBe('查看作答');
  });
});
