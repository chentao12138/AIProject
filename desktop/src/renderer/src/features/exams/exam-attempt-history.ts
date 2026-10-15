/**
 * Attempt history for one paper (W07 / A6.3 / C11).
 *
 * `GET /api/v1/spaces/{spaceId}/exam-attempts?examId=&page=&size=` returns the
 * learner's own attempts as a server-paged envelope, so this module only reads
 * the rows it is given: no local slicing, no local counting, and no status the
 * backend does not emit.
 *
 * Two server fields decide what a row can say:
 *  - `status` — `ExamAttemptService.STATUS_NOT_STARTED / STATUS_IN_PROGRESS /
 *     STATUS_SUBMITTED`, pinned by `lib/backend-vocabulary-contract.test.ts`;
 *  - `gradingStatus` — the contract enum GRADED / PARTIALLY_GRADED / UNGRADED,
 *     `null` while the attempt is still open. This is the field C11 asked for:
 *     with it, "submitted but not graded yet" is a server statement rather than
 *     an inference from "did a number arrive", and a partial score can be
 *     labelled as partial instead of being shown as final.
 *
 * A score is printed only when `gradingStatus` says the server has graded
 * (fully or partially). Anything else shows `—`: an absent score is not 0 分 and
 * not a verdict (ADR-031).
 */

import {
  EXAM_ATTEMPT_STATUSES,
  EXAM_GRADING_STATUSES,
} from '../../lib/format';
import { deadlineTime } from './useExamCountdown';

/** The fields of `AttemptSummaryView` this module reads. */
export interface AttemptRowState {
  status?: string | null;
  gradingStatus?: string | null;
  score?: number | null;
  maxScore?: number | null;
  /** `startedAt + exam timeLimitMinutes`, null before the attempt has started. */
  deadlineAt?: string | null;
}

/**
 * What one history row can honestly say. `'unknown'` exists because a status
 * outside the server's own vocabulary is not something to guess at: it gets
 * displayed as what it is, with no promised action.
 */
export type AttemptProgress =
  | 'not-started'
  | 'in-progress'
  | 'expired'
  | 'awaiting-grade'
  | 'partially-graded'
  | 'graded'
  | 'unknown';

/** The four cases the audit asks for, plus the two it must not be told apart from. */
export const ATTEMPT_PROGRESS_NOTES: Record<AttemptProgress, string> = {
  'not-started': '尚未开始作答',
  'in-progress': '作答进行中',
  // Only reachable from the server's own `deadlineAt`; the client never decides
  // that an attempt is over by counting minutes itself.
  expired: '已超过截止时间',
  'awaiting-grade': '已交卷，等待评分',
  'partially-graded': '客观题已判分，主观题仍待人工评分',
  graded: '已评分',
  unknown: '服务器未报告可识别的状态',
};

/**
 * The row's link text. Resuming is offered ONLY where the server says the
 * attempt is still the learner's to continue (`NOT_STARTED` / `IN_PROGRESS`);
 * a `SUBMITTED` attempt opens its read-only result view, an expired row opens
 * what it has, and an unrecognised status is not promised anything at all.
 */
export const ATTEMPT_PROGRESS_ACTIONS: Record<AttemptProgress, string> = {
  'not-started': '开始作答',
  'in-progress': '继续作答',
  expired: '查看作答',
  'awaiting-grade': '查看作答',
  'partially-graded': '查看成绩',
  graded: '查看成绩',
  unknown: '查看作答',
};

function normalizeCode(value?: string | null): string {
  return (value ?? '').trim().toUpperCase();
}

/** The grading states in which the server's number is a real statement. */
const SCORED: readonly string[] = [
  EXAM_GRADING_STATUSES.GRADED,
  EXAM_GRADING_STATUSES.PARTIALLY_GRADED,
];

/**
 * Whether a deadline string has passed. Reuses the exam page's ONE interpretation
 * of `deadlineAt` (`useExamCountdown.deadlineTime`), so the row in the list and
 * the countdown on the answering page can never disagree about when an attempt
 * ended. Absent or unparsable is NOT expired: an unreadable timestamp is no
 * statement from the server, and guessing would remove an affordance.
 */
function isPast(deadlineAt?: string | null, nowMs = Date.now()): boolean {
  const at = deadlineTime(deadlineAt);
  return at !== null && at <= nowMs;
}

export function attemptProgressOf(
  row: AttemptRowState,
  nowMs = Date.now()
): AttemptProgress {
  const status = normalizeCode(row.status);
  if (status === EXAM_ATTEMPT_STATUSES.NOT_STARTED) {
    // A not-started row has no deadline by definition (the server sends null),
    // but a row that carries one and is past it is not offered as "开始作答".
    return isPast(row.deadlineAt, nowMs) ? 'expired' : 'not-started';
  }
  if (status === EXAM_ATTEMPT_STATUSES.IN_PROGRESS) {
    return isPast(row.deadlineAt, nowMs) ? 'expired' : 'in-progress';
  }
  if (status !== EXAM_ATTEMPT_STATUSES.SUBMITTED) return 'unknown';

  const grading = normalizeCode(row.gradingStatus);
  if (grading === EXAM_GRADING_STATUSES.GRADED) return 'graded';
  if (grading === EXAM_GRADING_STATUSES.PARTIALLY_GRADED) return 'partially-graded';
  // UNGRADED, or null while the server has not graded anything: nothing to show.
  return 'awaiting-grade';
}

/**
 * Whether a result score is final. `PARTIALLY_GRADED` says so directly, and
 * `pendingGradeCount > 0` says the same thing numerically — the contract's own
 * description ties the two together ("More than zero means score is provisional;
 * gradingStatus is PARTIALLY_GRADED"), so either is enough and neither is guessed.
 */
export function isProvisionalResult(
  gradingStatus?: string | null,
  pendingGradeCount?: number | null
): boolean {
  if (normalizeCode(gradingStatus) === EXAM_GRADING_STATUSES.PARTIALLY_GRADED) {
    return true;
  }
  return typeof pendingGradeCount === 'number' && pendingGradeCount > 0;
}

/**
 * The score cell. A number is printed only when `gradingStatus` says the server
 * graded (fully or partially); everything else — open, submitted and ungraded,
 * or a status the client cannot map — shows `—`, never `0 / 0` and never a
 * verdict the backend never issued.
 */
export function attemptScoreText(row: AttemptRowState): string {
  const grading = normalizeCode(row.gradingStatus);
  if (!SCORED.includes(grading)) {
    return '—';
  }
  if (typeof row.score !== 'number') {
    // Graded, but the row carries no number to print. `— / 20` would read as
    // "0 of 20", so the whole cell stays empty.
    return '—';
  }
  return `${row.score} / ${typeof row.maxScore === 'number' ? row.maxScore : '—'}`;
}
