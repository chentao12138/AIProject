/**
 * Exam action error mapping (W05).
 *
 * Two properties matter: every failure gets readable Chinese, and the recovery
 * offered must be one that can actually work — a 409 state conflict told you to
 * "稍后重试" is a dead end, so it maps to `reload` instead.
 */

import { describe, expect, it } from 'vitest';
import {
  ApiRequestError,
  NetworkUnavailableError,
  NOT_FOUND_MESSAGE,
} from '../../lib/api-error';
import { examActionError } from './exam-error';

const conflict = (detail: string) =>
  new ApiRequestError(409, { code: 'CONFLICT', detail });

describe('examActionError — 409 state conflicts', () => {
  it('reads the EXAM_DEADLINE_EXCEEDED marker as a reload, not a retry', () => {
    const result = examActionError(
      conflict('exam deadline exceeded (EXAM_DEADLINE_EXCEEDED)')
    );
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('答题时间已结束');
  });

  it('maps EXAM_ATTEMPT_NOT_IN_PROGRESS to a readable status conflict', () => {
    const result = examActionError(
      conflict(
        'exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): SUBMITTED'
      )
    );
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('已不在作答中状态');
  });

  it('maps EXAM_NOT_AVAILABLE (unpublished / no paper) to no retry at all', () => {
    const result = examActionError(
      conflict('exam is not available (EXAM_NOT_AVAILABLE): DRAFT')
    );
    expect(result.recovery).toBe('none');
    expect(result.message).toContain('该考试当前不可作答');
  });

  it('treats a concurrent start as retryable', () => {
    const result = examActionError(
      conflict('exam attempt state changed concurrently')
    );
    expect(result.recovery).toBe('retry');
  });

  it.each([
    'only IN_PROGRESS attempts can be submitted: SUBMITTED',
    'exam attempt already submitted',
    'exam attempt is not NOT_STARTED: IN_PROGRESS',
    'result is only available after submit: IN_PROGRESS',
  ])('recognizes "%s" as an already-settled attempt', (detail) => {
    const result = examActionError(conflict(detail));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('状态已变更');
  });

  it('falls back to a reload for an unknown 409', () => {
    const result = examActionError(conflict('something else entirely'));
    expect(result.recovery).toBe('reload');
    expect(result.message).toContain('与服务器不一致');
  });

  it('never echoes the server detail into the UI', () => {
    const result = examActionError(
      conflict('exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): SUBMITTED')
    );
    expect(result.message).not.toContain('IN_PROGRESS');
    expect(result.message).not.toContain('SUBMITTED');
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
    expect(
      examActionError(new ApiRequestError(502, {})).message
    ).toContain('上游服务');
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
