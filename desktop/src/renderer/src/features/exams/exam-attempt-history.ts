/**
 * Attempt history for one paper (W07 / A6.3 / C11).
 *
 * `GET /api/v1/spaces/{spaceId}/exam-attempts?examId=&page=&size=` returns the
 * learner's own attempts as a server-paged envelope, so this module only reads
 * the rows it is given: no local slicing, no local counting, and no status the
 * backend does not emit.
 *
 * `status` is the SERVER's vocabulary (`ExamAttemptService.STATUS_NOT_STARTED /
 * STATUS_IN_PROGRESS / STATUS_SUBMITTED`, pinned by
 * `lib/backend-vocabulary-contract.test.ts`) — not a frontend invention. The
 * contract's `AttemptSummaryView{id, examId, status, startedAt, submittedAt,
 * score, maxScore}` carries no grading status, so "submitted" splits into the
 * two cases the score itself distinguishes:
 *  - `score` is a number → the result row exists → 已评分;
 *  - `score` is null / absent → nothing has been graded yet → 已交卷，等待评分.
 *
 * That distinction is the whole point: a null score is NOT 0 分 and is NOT a
 * verdict. The same tri-state bug (a value the server left null being read as
 * "wrong") was already fixed once in `ExamAttemptPage`'s result view
 * (`isCorrect === true / false / null`) and in `exam-attempt-state.ts`.
 */

import { EXAM_ATTEMPT_STATUSES } from '../../lib/format';

/**
 * What one history row can honestly say, derived from the server's status and
 * whether the server sent a score. `'unknown'` is the case that exists because
 * the contract types `status` as a bare string: anything outside the pinned
 * three values is displayed as what it is instead of being guessed at.
 */
export type AttemptProgress =
  | 'not-started'
  | 'in-progress'
  | 'awaiting-grade'
  | 'graded'
  | 'unknown';

/** The three cases the audit asks for, plus the two it must not be told apart from. */
export const ATTEMPT_PROGRESS_NOTES: Record<AttemptProgress, string> = {
  'not-started': '尚未开始作答',
  'in-progress': '作答进行中',
  'awaiting-grade': '已交卷，等待评分',
  graded: '已评分',
  unknown: '服务器未报告可识别的状态',
};

/**
 * The row's link text. Resuming is offered ONLY where the server says the
 * attempt is still the learner's to continue (`NOT_STARTED` / `IN_PROGRESS`);
 * a `SUBMITTED` attempt opens its read-only result view, and an unrecognised
 * status is not promised anything at all.
 */
export const ATTEMPT_PROGRESS_ACTIONS: Record<AttemptProgress, string> = {
  'not-started': '开始作答',
  'in-progress': '继续作答',
  'awaiting-grade': '查看成绩',
  graded: '查看成绩',
  unknown: '查看作答',
};

/** The server's status verbatim, normalised for case and stray whitespace. */
function normalizeStatus(status?: string | null): string {
  return (status ?? '').trim().toUpperCase();
}

/**
 * Classify one attempt row. `score` is typed `number` by the generated client,
 * but the contract marks it optional and the server sends `null` for an
 * attempt with no graded result, so the check is `typeof === 'number'` — `0`
 * is a real score and must not fall through to "not graded".
 */
export function attemptProgressOf(
  status?: string | null,
  score?: number | null
): AttemptProgress {
  const normalized = normalizeStatus(status);
  if (normalized === EXAM_ATTEMPT_STATUSES.NOT_STARTED) return 'not-started';
  if (normalized === EXAM_ATTEMPT_STATUSES.IN_PROGRESS) return 'in-progress';
  if (normalized === EXAM_ATTEMPT_STATUSES.SUBMITTED) {
    return typeof score === 'number' ? 'graded' : 'awaiting-grade';
  }
  return 'unknown';
}

/**
 * Whether this attempt may be offered as resumable. Only the server's own
 * IN_PROGRESS / NOT_STARTED qualify; a submitted (or finalised-by-the-server)
 * attempt is closed, and an expired one is no longer the client's to judge —
 * the attempt page reads the server's `deadlineAt` and ends it there.
 */
export function canResumeAttempt(status?: string | null): boolean {
  const progress = attemptProgressOf(status, null);
  return progress === 'not-started' || progress === 'in-progress';
}

/**
 * The score cell. A score is printed ONLY for a graded attempt; everything
 * else — an attempt still being answered, one submitted but not graded, one
 * whose status the client cannot map — shows `—`, never `0 / 0` and never a
 * 答对/答错 verdict the backend never issued.
 */
export function attemptScoreText(row: {
  status?: string | null;
  score?: number | null;
  maxScore?: number | null;
}): string {
  if (attemptProgressOf(row.status, row.score) !== 'graded') return '—';
  const score = typeof row.score === 'number' ? row.score : '—';
  const maxScore = typeof row.maxScore === 'number' ? row.maxScore : '—';
  return `${score} / ${maxScore}`;
}
