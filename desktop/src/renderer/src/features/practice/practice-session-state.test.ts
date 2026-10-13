/**
 * Practice state helpers — payload shapes the backend accepts, error copy,
 * and the response mirror that survives unmount (audit W01 / W02 / W03).
 */

import { describe, expect, it } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { ApiRequestError, NetworkUnavailableError } from '../../lib/api-error';
import { createTestQueryClient } from '../../test/test-utils';
import {
  buildAnswerPayload,
  describePracticeError,
  describeServerVerdict,
  describeSubmittedAnswer,
  firstOpenSlotIndex,
  practiceLocalStateKey,
  toPracticeSlots,
  usePracticeLocalState,
  type PracticeSubmittedAnswer,
} from './practice-session-state';

function wrapper({ children }: { children: ReactNode }) {
  const client = createTestQueryClient();
  return createElement(QueryClientProvider, { client }, children);
}

describe('buildAnswerPayload', () => {
  it('sends exactly the one field each type requires', () => {
    expect(
      buildAnswerPayload('SINGLE_CHOICE', { selectedOptionKeys: ['A'] })
    ).toEqual({ ok: true, answer: { selectedOptionKeys: ['A'] } });
    expect(
      buildAnswerPayload('MULTIPLE_CHOICE', { selectedOptionKeys: ['A', 'B'] })
    ).toEqual({ ok: true, answer: { selectedOptionKeys: ['A', 'B'] } });
    expect(buildAnswerPayload('TRUE_FALSE', { booleanAnswer: false })).toEqual({
      ok: true,
      answer: { booleanAnswer: false },
    });
    expect(
      buildAnswerPayload('SHORT_ANSWER', { textAnswer: 'because…' })
    ).toEqual({ ok: true, answer: { textAnswer: 'because…' } });
    expect(
      buildAnswerPayload('FILL_BLANK', { textAnswer: 'alpha,beta' })
    ).toEqual({ ok: true, answer: { textAnswer: 'alpha,beta' } });
  });

  // Backend: `TRUE_FALSE requires booleanAnswer`. Defaulting an untouched
  // judgement to false graded users on a click they never made.
  it('refuses to invent an answer the user never chose', () => {
    expect(buildAnswerPayload('TRUE_FALSE', {}).ok).toBe(false);
    expect(buildAnswerPayload('TRUE_FALSE', undefined).ok).toBe(false);
    expect(buildAnswerPayload('SINGLE_CHOICE', { selectedOptionKeys: [] }).ok).toBe(
      false
    );
    expect(
      buildAnswerPayload('SINGLE_CHOICE', { selectedOptionKeys: ['A', 'B'] }).ok
    ).toBe(false);
    expect(buildAnswerPayload('FILL_BLANK', { textAnswer: '   ' }).ok).toBe(false);
    expect(buildAnswerPayload('SHORT_ANSWER', { textAnswer: '' }).ok).toBe(false);
  });

  // AnswerPayloadView carries neither orderingAnswer nor matchingAnswer.
  it('names the missing contract fields instead of sending a 400-bound body', () => {
    const ordering = buildAnswerPayload('ORDERING', { textAnswer: 'x' });
    const matching = buildAnswerPayload('MATCHING', { textAnswer: 'x' });
    expect(ordering.ok).toBe(false);
    expect(matching.ok).toBe(false);
    if (!ordering.ok) expect(ordering.message).toContain('orderingAnswer');
    if (!matching.ok) expect(matching.message).toContain('matchingAnswer');
  });

  it('rejects a question type the backend does not grade', () => {
    const result = buildAnswerPayload('ESSAY', { textAnswer: 'x' });
    expect(result.ok).toBe(false);
  });
});

describe('describeSubmittedAnswer', () => {
  it('reads as the sent payload, per type', () => {
    expect(
      describeSubmittedAnswer('SINGLE_CHOICE', { selectedOptionKeys: ['B'] })
    ).toBe('已选 B');
    expect(describeSubmittedAnswer('TRUE_FALSE', { booleanAnswer: true })).toBe(
      '选择：正确'
    );
    expect(describeSubmittedAnswer('FILL_BLANK', { textAnswer: 'a,b' })).toBe(
      '提交内容：a,b'
    );
    expect(describeSubmittedAnswer('SHORT_ANSWER', {})).toBe('未填写内容');
  });
});

describe('describeServerVerdict', () => {
  it('reports the server answer verbatim', () => {
    expect(describeServerVerdict({ isCorrect: true }).label).toBe('答对');
    expect(describeServerVerdict({ isCorrect: false }).label).toBe('答错');
    expect(describeServerVerdict({ isCorrect: false }).tone).toBe('wrong');
  });

  // SHORT_ANSWER is stored ungraded: no verdict must be invented for it.
  it('reports an ungraded answer instead of guessing (ADR-031)', () => {
    expect(describeServerVerdict({}).label).toBe('后端未返回逐题评分');
    expect(describeServerVerdict({}).graded).toBe(false);
    expect(describeServerVerdict({ score: 2 }).graded).toBe(false);
  });
});

describe('toPracticeSlots', () => {
  it('keeps server order, honours complete sortOrder, drops malformed slots', () => {
    const slots = toPracticeSlots({
      questions: [
        {
          practiceSessionQuestionId: 11,
          questionId: 101,
          sortOrder: 2,
          questionType: 'single_choice',
          stem: 'second',
          options: [{ optionKey: 'A', content: 'Alpha' }, { content: 'no key' }],
        },
        {
          practiceSessionQuestionId: 12,
          questionId: 102,
          sortOrder: 1,
          stem: 'first',
        },
        { questionId: 103, stem: 'no slot id' },
      ],
    });
    expect(slots.map((s) => s.stem)).toEqual(['first', 'second']);
    expect(slots[0].options).toEqual([]);
    expect(slots[1].options).toEqual([{ optionKey: 'A', content: 'Alpha' }]);
    expect(slots[1].questionType).toBe('single_choice');
  });

  it('returns nothing for a detail without questions', () => {
    expect(toPracticeSlots(undefined)).toEqual([]);
  });
});

describe('describePracticeError', () => {
  it('keeps the shared normalization for auth / not-found / network copy', () => {
    expect(describePracticeError(new ApiRequestError(401), 'start').message).toBe(
      '登录状态已失效，请重新登录。'
    );
    expect(describePracticeError(new ApiRequestError(403), 'finish').message).toBe(
      '当前会话无权执行此操作。'
    );
    expect(describePracticeError(new ApiRequestError(404), 'answer').message).toBe(
      '资源不存在或当前不可访问。'
    );
    expect(
      describePracticeError(new NetworkUnavailableError(), 'finish').message
    ).toBe('无法连接到后端服务。请确认服务已启动后重试。');
    expect(describePracticeError(new ApiRequestError(500), 'start').message).toBe(
      '服务器暂时无法完成请求，请稍后重试。'
    );
  });

  // A 409 means the session moved (CREATED -> IN_PROGRESS -> SUBMITTED), so
  // repeating the same write can only fail again.
  it('turns a 409 into a readable conflict plus a session re-read', () => {
    const start = describePracticeError(
      new ApiRequestError(409, { code: 'CONFLICT' }),
      'start'
    );
    expect(start.message).toBe('会话已不在“待开始”状态，无法再次开始。');
    expect(start.retryAction).toBe('reload-session');
    expect(start.retryLabel).toBe('重新读取会话');

    const finish = describePracticeError(new ApiRequestError(409), 'finish');
    expect(finish.message).toBe('会话不在作答中状态，无法交卷。');
    expect(finish.retryAction).toBe('reload-session');

    const answer = describePracticeError(new ApiRequestError(409), 'answer');
    expect(answer.message).toBe('会话状态已变化，本题答案未提交。');
    expect(answer.retryAction).toBe('reload-session');
  });

  it('shows the backend validation detail for a 400 payload rejection', () => {
    const copy = describePracticeError(
      new ApiRequestError(400, {
        code: 'VALIDATION_ERROR',
        detail: 'TRUE_FALSE requires booleanAnswer',
      }),
      'answer'
    );
    expect(copy.message).toBe('校验未通过：TRUE_FALSE requires booleanAnswer');
    expect(copy.detail).toContain('本题仍按未提交处理');
    expect(copy.retryAction).toBe('repeat');
  });

  it('offers no retry for errors a retry cannot fix', () => {
    for (const status of [401, 403, 404]) {
      expect(describePracticeError(new ApiRequestError(status), 'start').retryLabel).toBeNull();
    }
  });
});

describe('firstOpenSlotIndex', () => {
  const slots = toPracticeSlots({
    questions: [
      { practiceSessionQuestionId: 11, questionId: 1 },
      { practiceSessionQuestionId: 12, questionId: 2 },
    ],
  });

  it('points at the first question the server has not answered', () => {
    const submitted: Record<number, PracticeSubmittedAnswer> = {
      11: { answer: {}, view: { isCorrect: true } },
    };
    expect(firstOpenSlotIndex(slots, submitted)).toBe(1);
    expect(firstOpenSlotIndex(slots, {})).toBe(0);
    expect(
      firstOpenSlotIndex(slots, {
        11: { answer: {}, view: {} },
        12: { answer: {}, view: {} },
      })
    ).toBe(-1);
  });
});

describe('usePracticeLocalState', () => {
  it('is scoped to one session', () => {
    expect(practiceLocalStateKey(7, 1)).toEqual(['practice-session-state', 7, 1]);
    expect(practiceLocalStateKey(7, 2)).not.toEqual(practiceLocalStateKey(7, 1));
  });

  // W01: a draft was component state, so navigating away and back lost both
  // the typed text and the server-graded answer.
  it('keeps drafts and server answers after the consumer unmounts', async () => {
    const client = createTestQueryClient();
    const mount = () =>
      renderHook(() => usePracticeLocalState(7, 1), {
        wrapper: ({ children }: { children: ReactNode }) =>
          createElement(QueryClientProvider, { client }, children),
      });

    const first = mount();
    act(() => {
      first.result.current.setDraft(11, { textAnswer: 'typed answer' });
      first.result.current.markSubmitted(11, {
        answer: { textAnswer: 'typed answer' },
        view: { isCorrect: true, correctAnswer: 'typed answer', explanation: 'why' },
      });
      first.result.current.setCurrentIndex(3);
    });
    first.unmount();

    const again = mount();
    await waitFor(() => {
      expect(again.result.current.state.drafts[11]).toEqual({
        textAnswer: 'typed answer',
      });
      expect(again.result.current.state.submitted[11]?.view.explanation).toBe('why');
      expect(again.result.current.state.currentIndex).toBe(3);
    });
    again.unmount();
  });

  it('merges partial drafts without dropping other fields', async () => {
    const { result } = renderHook(() => usePracticeLocalState(7, 1), { wrapper });
    act(() => {
      result.current.setDraft(11, { textAnswer: 'a' });
    });
    act(() => {
      result.current.setDraft(11, { selectedOptionKeys: ['A'] });
    });
    // The mirror is read through React Query, so a write surfaces on the next
    // notification — asserted with waitFor rather than a synchronous read.
    await waitFor(() => {
      expect(result.current.state.drafts[11]).toEqual({
        textAnswer: 'a',
        selectedOptionKeys: ['A'],
      });
    });
  });

  it('leaves a fresh session at an empty state', () => {
    const { result } = renderHook(() => usePracticeLocalState(7, 99), { wrapper });
    expect(result.current.state.submitted).toEqual({});
    expect(result.current.state.currentIndex).toBe(0);
  });

  it('does not leak state into another session', async () => {
    const client = createTestQueryClient();
    const Wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client }, children);
    const a = renderHook(() => usePracticeLocalState(7, 1), { wrapper: Wrapper });
    const b = renderHook(() => usePracticeLocalState(7, 2), { wrapper: Wrapper });
    act(() => {
      a.result.current.markSubmitted(11, { answer: {}, view: { isCorrect: true } });
    });
    await waitFor(() => {
      expect(a.result.current.state.submitted[11]).toBeDefined();
    });
    expect(b.result.current.state.submitted).toEqual({});
  });
});
