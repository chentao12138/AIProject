/**
 * Exam action error mapping (W05 / C12).
 *
 * Two properties matter: every failure gets readable Chinese, and the recovery
 * offered must be one that can actually work — a 409 state conflict told you to
 * "稍后重试" is a dead end, so it maps to `reload` instead.
 *
 * The 409 branch reads ONLY `ProblemDetail.code`. It used to match English
 * `detail` sentences, and these tests used to feed those sentences; the point of
 * rewriting them is that a reworded server message must not change the mapping,
 * so every case below carries a `detail` the mapping could not have produced.
 */

import { describe, expect, it } from 'vitest';
import {
  ApiRequestError,
  NetworkUnavailableError,
  NOT_FOUND_MESSAGE,
} from '../../lib/api-error';
import { EXAM_ERROR_CODES, examActionError } from './exam-error';

/** A 409 carrying one of the C12 codes and an unrelated English detail. */
const conflict = (code: string) =>
  new ApiRequestError(409, {
    code,
    detail: 'exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): SUBMITTED',
  });

describe('examActionError — 409 conflicts branch on the code', () => {
  it('maps the deadline code to a reload, not a retry', () => {
    const result = examActionError(conflict('EXAM_DEADLINE_EXCEEDED'));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('答题时间已结束');
  });

  it('maps EXAM_ATTEMPT_NOT_IN_PROGRESS to a readable status conflict', () => {
    const result = examActionError(conflict('EXAM_ATTEMPT_NOT_IN_PROGRESS'));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('已不在作答中状态');
  });

  it('maps EXAM_NOT_AVAILABLE (unpublished / no paper) to no retry at all', () => {
    const result = examActionError(conflict('EXAM_NOT_AVAILABLE'));
    expect(result.recovery).toBe('none');
    expect(result.message).toContain('该考试当前不可作答');
  });

  it('offers 刷新 for the two codes that mean "another attempt moved first"', () => {
    expect(examActionError(conflict('EXAM_ACTIVE_ATTEMPT_EXISTS')).recovery).toBe('reload');
    expect(examActionError(conflict('EXAM_ATTEMPT_ALREADY_SUBMITTED')).recovery).toBe('reload');
  });

  it.each([
    'EXAM_ATTEMPT_STALE',
    'EXAM_ANSWER_STALE',
    'EXAM_RESULT_NOT_AVAILABLE',
    'EXAM_RESULT_MISSING',
  ])('%s tells the user the page is behind the server', (code) => {
    const result = examActionError(conflict(code));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('服务端');
  });

  it('does not promise a retry for an answer the server will not grade', () => {
    const result = examActionError(conflict('EXAM_ANSWER_NOT_GRADABLE'));
    expect(result.recovery).toBe('none');
  });

  // The whole reason the codes exist: the sentence changed, the mapping did not.
  it('ignores the detail text entirely', () => {
    const byCode = examActionError(
      new ApiRequestError(409, { code: 'EXAM_DEADLINE_EXCEEDED', detail: 'totally new English' })
    );
    const noDetail = examActionError(
      new ApiRequestError(409, { code: 'EXAM_DEADLINE_EXCEEDED' })
    );
    expect(byCode).toEqual(noDetail);
    expect(byCode.message).not.toContain('totally new English');
  });

  it('falls back to a reload for a code it does not know', () => {
    const result = examActionError(conflict('EXAM_SOMETHING_NEW'));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('与服务器不一致');
  });

  it('still handles the legacy generic CONFLICT without inventing a reason', () => {
    const result = examActionError(
      new ApiRequestError(409, { code: 'CONFLICT', detail: 'exam attempt already submitted' })
    );
    expect(result).toEqual({
      message: '本次考试状态与服务器不一致，操作未完成。请刷新后重试。',
      recovery: 'reload',
    });
  });

  it('treats a 409 with no code as an unknown conflict', () => {
    expect(examActionError(new ApiRequestError(409, {})).message).toContain('与服务器不一致');
  });

  it('never echoes server English for any code', () => {
    for (const code of EXAM_ERROR_CODES) {
      const result = examActionError(conflict(code));
      expect(result.message).not.toMatch(/[A-Za-z]{4,}/);
      expect(result.message).not.toContain('IN_PROGRESS');
      expect(result.message).not.toContain(code);
    }
  });

  it('gives every code its own copy', () => {
    // Two codes sharing a message would throw away the distinction the codes
    // were added for, which is the C12 defect in miniature.
    const messages = EXAM_ERROR_CODES.map((code) => examActionError(conflict(code)).message);
    expect(new Set(messages).size).toBe(EXAM_ERROR_CODES.length);
  });
});

describe('examActionError — keeps the shared vocabulary for everything else', () => {
  it('401 asks for re-login and offers no retry', () => {
    const result = examActionError(new ApiRequestError(401, {}));
    expect(result.message).toBe('登录状态已失效，请重新登录。');
    expect(result.recovery).toBe('none');
  });

  it('403 stays the forbidden copy', () => {
    expect(examActionError(new ApiRequestError(403, {})).message).toBe(
      '当前会话无权执行此操作。'
    );
  });

  it('404 keeps the anti-IDOR wording', () => {
    const result = examActionError(new ApiRequestError(404, {}));
    expect(result.message).toBe(NOT_FOUND_MESSAGE);
    expect(result.recovery).toBe('none');
  });

  it('5xx and 502 are retryable', () => {
    expect(examActionError(new ApiRequestError(500, {})).recovery).toBe('retry');
    expect(examActionError(new ApiRequestError(502, {})).message).toContain('上游服务');
  });

  it('a dead backend says so instead of blaming the exam', () => {
    const result = examActionError(new NetworkUnavailableError());
    expect(result.message).toContain('无法连接到后端服务');
    expect(result.recovery).toBe('retry');
  });

  it('400 validation detail still reaches the user', () => {
    const result = examActionError(
      new ApiRequestError(400, {
        code: 'VALIDATION_ERROR',
        detail: 'TRUE_FALSE requires correctBoolean',
      })
    );
    expect(result.message).toContain('校验未通过');
  });
});
