/**
 * Exam action error copy (W05 / C12).
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
 * WHY IT NOW READS THE `code` AND NOT THE `detail`
 * The backend used to answer every exam conflict with `code:"CONFLICT"` and put
 * the reason only in the RFC 7807 `detail`, so this file matched English
 * sentences ("only IN_PROGRESS attempts can be submitted", "exam attempt state
 * changed concurrently") and embedded markers inside them. That was never a
 * contract: a reworded sentence silently turns a real conflict into the generic
 * fallback, and English text is exactly what must not reach the UI. C12 shipped
 * stable per-conflict codes, so all of that matching is gone — the only server
 * string this file consults is `ProblemDetail.code`.
 *
 * An unknown 409 code (the server added one we have not mapped) keeps the
 * reload copy: honest, and it cannot be mistaken for one of the ten below.
 */

import { ApiRequestError, apiProblemCode, normalizeApiError } from '../../lib/api-error';

/** What the UI can honestly offer after this failure. */
export type ExamErrorRecovery =
  /** Re-run the same action (transient: network / 5xx / concurrent update). */
  | 'retry'
  /** The server is ahead of us — re-read the attempt, its status is truth. */
  | 'reload'
  /** Retrying cannot help (401/403/404, or an exam that cannot be taken). */
  | 'none';

export interface ExamActionError {
  message: string;
  recovery: ExamErrorRecovery;
}

/**
 * The exam conflict codes (backend `ApiErrorCodes`, C12). Pinned to the Java
 * declarations by `lib/exam-error-codes-contract.test.ts`, which also holds the
 * documented list of codes this workspace's `server/` copy does not declare yet.
 */
export const EXAM_ERROR_CODES = [
  'EXAM_NOT_AVAILABLE',
  'EXAM_ACTIVE_ATTEMPT_EXISTS',
  'EXAM_ATTEMPT_NOT_IN_PROGRESS',
  'EXAM_ATTEMPT_ALREADY_SUBMITTED',
  'EXAM_RESULT_NOT_AVAILABLE',
  'EXAM_RESULT_MISSING',
  'EXAM_ANSWER_NOT_GRADABLE',
  'EXAM_ATTEMPT_STALE',
  'EXAM_ANSWER_STALE',
  'EXAM_DEADLINE_EXCEEDED',
] as const;

export type ExamErrorCode = (typeof EXAM_ERROR_CODES)[number];

function isExamErrorCode(code: string | null): code is ExamErrorCode {
  return code !== null && (EXAM_ERROR_CODES as readonly string[]).includes(code);
}

/**
 * One entry per code, and only per code: the copy says what the server stated
 * and what the page can then do. `reload` means "the server's read is the truth
 * we lack", `none` means "repeating this request can only fail again".
 */
const EXAM_ERROR_COPY: Record<ExamErrorCode, ExamActionError> = {
  EXAM_NOT_AVAILABLE: {
    message: '该考试当前不可作答：未发布，或试卷里没有题目。',
    recovery: 'none',
  },
  EXAM_ACTIVE_ATTEMPT_EXISTS: {
    message: '已经有一次进行中的作答，请先完成那一次，不要新建。请刷新以进入未完成的作答。',
    recovery: 'reload',
  },
  EXAM_ATTEMPT_NOT_IN_PROGRESS: {
    message: '本次考试已不在作答中状态（可能已交卷或已到时）。请刷新以查看最新状态。',
    recovery: 'reload',
  },
  EXAM_ATTEMPT_ALREADY_SUBMITTED: {
    message: '本次作答已经交卷，不能再提交或改动答案。',
    recovery: 'reload',
  },
  EXAM_RESULT_NOT_AVAILABLE: {
    message: '现在还不能查看成绩：这次作答尚未交卷，或服务端尚未判完。',
    recovery: 'reload',
  },
  EXAM_RESULT_MISSING: {
    message: '服务端没有这次作答的成绩记录，因此没有分数可显示。',
    recovery: 'reload',
  },
  EXAM_ANSWER_NOT_GRADABLE: {
    message: '这道题不适用系统判分，对错与解析以服务端给出的为准。',
    recovery: 'none',
  },
  EXAM_ATTEMPT_STALE: {
    message: '本次作答的状态在服务端已经更新，你看到的页面是旧的。请刷新后继续。',
    recovery: 'reload',
  },
  EXAM_ANSWER_STALE: {
    message: '这一题在服务端已有更新的作答记录，本次写入被拒绝。请刷新后再作答。',
    recovery: 'reload',
  },
  EXAM_DEADLINE_EXCEEDED: {
    message: '答题时间已结束，本次考试无法继续作答。请刷新以查看最新状态。',
    recovery: 'reload',
  },
};

/** The 409 the UI cannot attribute to any known code. */
const UNMAPPED_CONFLICT: ExamActionError = {
  message: '本次考试状态与服务器不一致，操作未完成。请刷新后重试。',
  recovery: 'reload',
};

/**
 * The mapped copy for one failed exam action. Exported separately from
 * `examActionError` so the guard test can assert every code is handled and no
 * two codes share a message (a shared message would mean the distinction the
 * codes exist for is thrown away).
 */
export function examConflictError(error: unknown): ExamActionError | null {
  if (!(error instanceof ApiRequestError) || error.status !== 409) {
    return null;
  }
  const code = apiProblemCode(error);
  return isExamErrorCode(code) ? EXAM_ERROR_COPY[code] : UNMAPPED_CONFLICT;
}

export function examActionError(error: unknown): ExamActionError {
  const conflict = examConflictError(error);
  if (conflict) {
    return conflict;
  }

  // Everything else keeps the shared vocabulary; only the recovery is added.
  const normalized = normalizeApiError(error);
  return {
    message: normalized.message,
    recovery:
      normalized.kind === 'network' ||
      normalized.kind === 'server' ||
      normalized.kind === 'upstream'
        ? 'retry'
        : 'none',
  };
}
