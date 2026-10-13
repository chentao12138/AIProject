/**
 * Exam action error copy (W05).
 *
 * `startAttempt` / `submitAnswer` / `submitAttempt` failures used to be
 * invisible: the buttons just "did nothing". This module turns a thrown API
 * error into readable Chinese PLUS the recovery the copy promises, on top of
 * the shared primitive `normalizeApiError` (lib/api-error.ts) — 401/403/404/
 * 502/5xx/network keep their existing repo-wide wording, so no copy is
 * invented twice and `ErrorState` stays the only renderer.
 *
 * WHY 409 NEEDS ITS OWN BRANCH
 * `normalizeApiError` maps 409 to `{kind:'unknown'}` -> "请求失败，请稍后重试。",
 * which is wrong for exams: a 409 here means a STATE conflict (already
 * submitted / deadline passed / exam not published), and "稍后重试" sends the
 * user to retry something that can never succeed.
 *
 * The backend's 409 `code` is always `CONFLICT` (`ApiErrorCodes.forStatus(409)`),
 * and the specific reason lives ONLY in the RFC 7807 `detail`, where
 * `ExamAttemptService` embeds a stable marker, e.g.
 *   "exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): SUBMITTED"
 *   "exam deadline exceeded (EXAM_DEADLINE_EXCEEDED)"
 *   "exam is not available (EXAM_NOT_AVAILABLE): DRAFT"
 * So we branch on those markers and NEVER render `detail` itself — same
 * discipline as normalizeApiError, which only ever surfaces validation
 * details. Raw server text must not reach the UI.
 */

import { ApiRequestError, normalizeApiError } from '../../lib/api-error';

/** What the UI can honestly offer after this failure. */
export type ExamErrorRecovery =
  /** Re-run the same action (transient: network / 5xx / concurrent update). */
  | 'retry'
  /** The server is ahead of us — re-read the attempt, its status is truth. */
  | 'reload'
  /** Retrying cannot help (401/403/404) — only the fallback route remains. */
  | 'none';

export interface ExamActionError {
  message: string;
  recovery: ExamErrorRecovery;
}

/** Stable markers embedded in the backend's 409 `detail` (never rendered). */
const MARKERS = {
  deadline: 'EXAM_DEADLINE_EXCEEDED',
  notInProgress: 'EXAM_ATTEMPT_NOT_IN_PROGRESS',
  notAvailable: 'EXAM_NOT_AVAILABLE',
} as const;

/** The 409 phrasings that all mean "this attempt is already past its turn". */
const ALREADY_SETTLED = [
  'exam attempt already submitted',
  'only IN_PROGRESS attempts can be submitted',
  'exam attempt is not NOT_STARTED',
  'result is only available after submit',
] as const;

const CONCURRENT = 'exam attempt state changed concurrently';

/** `detail` of a `ResponseStatusException`, if the error carries one. */
function conflictDetail(error: unknown): string | null {
  if (!(error instanceof ApiRequestError) || error.status !== 409) return null;
  const body = error.body as { detail?: unknown } | undefined;
  return typeof body?.detail === 'string' ? body.detail : null;
}

/**
 * The backend embeds markers in free-text English reasons whose casing varies
 * with the status phrase, so matching is case-insensitive. The `detail` itself
 * is NEVER rendered — only branched on.
 */
function mentions(detail: string, phrase: string): boolean {
  return detail.toLowerCase().includes(phrase.toLowerCase());
}

export function examActionError(error: unknown): ExamActionError {
  const detail = conflictDetail(error);

  if (detail !== null) {
    if (mentions(detail, MARKERS.deadline)) {
      return {
        message: '答题时间已结束，本次考试无法继续作答。请刷新以查看最新状态。',
        recovery: 'reload',
      };
    }
    if (mentions(detail, MARKERS.notInProgress)) {
      return {
        message: '本次考试已不在作答中状态（可能已交卷或已到时）。请刷新以查看最新状态。',
        recovery: 'reload',
      };
    }
    if (mentions(detail, MARKERS.notAvailable)) {
      return {
        message: '该考试当前不可作答：试卷未发布或没有题目。',
        recovery: 'none',
      };
    }
    if (mentions(detail, CONCURRENT)) {
      return {
        message: '本次考试状态刚刚发生变动，请重试。',
        recovery: 'retry',
      };
    }
    if (ALREADY_SETTLED.some((phrase) => mentions(detail, phrase))) {
      return {
        message: '本次考试状态已变更（可能已交卷）。请刷新以查看最新状态。',
        recovery: 'reload',
      };
    }
    return {
      message: '本次考试状态与服务器不一致，操作未完成。请刷新后重试。',
      recovery: 'reload',
    };
  }

  // Everything else keeps the shared vocabulary; only the recovery is added.
  const normalized = normalizeApiError(error);
  return {
    message: normalized.message,
    recovery:
      normalized.kind === 'network' || normalized.kind === 'server' || normalized.kind === 'upstream'
        ? 'retry'
        : 'none',
  };
}
