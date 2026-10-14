/**
 * Practice state helpers — payload shapes the backend accepts, error copy,
 * the response mirror that survives unmount (audit W01 / W02 / W03), the A5.2
 * resume read (`GET …/answers`) and the A5.3 score read (`GET …/result`).
 */

import { describe, expect, it, vi } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import { createElement, type ReactNode } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import {
  ApiRequestError,
  NetworkUnavailableError,
  normalizeApiError,
} from '../../lib/api-error';
import { ApiClientProvider } from '../../lib/api-context';
import { queryKeys } from '../../lib/query-keys';
import type { PracticeResult } from '../../lib/types';
import {
  createTestQueryClient,
  mockApiClient,
} from '../../test/test-utils';
import {
  buildAnswerPayload,
  describePracticeError,
  describeServerVerdict,
  describeSubmittedAnswer,
  firstOpenSlotIndex,
  practiceLocalStateKey,
  practicePayloadContext,
  practiceResultState,
  practiceResultErrorNotice,
  toPracticeSlots,
  unanswerableTypeNotice,
  usePracticeLocalState,
  usePracticeResult,
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

  // C09 is closed: AnswerPayloadView grew orderingAnswer / matchingAnswer, so
  // both types now travel in the field the backend actually grades.
  it('sends the committed 1-based positions for ORDERING', () => {
    expect(
      buildAnswerPayload('ORDERING', { orderingAnswer: [3, 1, 2] }, { optionCount: 3 })
    ).toEqual({ ok: true, answer: { orderingAnswer: [3, 1, 2] } });
    // …and only that field: a payload with another one is a 400.
    const result = buildAnswerPayload(
      'ORDERING',
      { orderingAnswer: [2, 1], textAnswer: 'stale draft' },
      { optionCount: 2 }
    );
    expect(result.ok && result.answer).toEqual({ orderingAnswer: [2, 1] });
  });

  // gradeOrdering compares the whole list for equality and throws on an empty
  // one, so anything short of a full permutation must never be sent.
  it('refuses an ORDERING answer that is not one complete permutation', () => {
    const cases: Array<[number[] | undefined, number]> = [
      [undefined, 3],
      [[], 3],
      [[1, 2], 3], // not every option placed
      [[0, 1, 2], 3], // the contract puts minimum: 1 on every item
      [[2, 2, 1], 3], // one option in two places
      [[1, 2, 4], 3], // a position the paper does not have
      [[1, 2, 3, 4], 3], // longer than the paper
      [[1.5, 2, 3], 3],
    ];
    for (const [orderingAnswer, optionCount] of cases) {
      const result = buildAnswerPayload(
        'ORDERING',
        orderingAnswer === undefined ? {} : { orderingAnswer },
        { optionCount }
      );
      expect(result.ok).toBe(false);
    }
  });

  it('sends the pair JSON for MATCHING, re-serialized to {left,right}', () => {
    const wire = JSON.stringify([
      { left: 'B', right: '2' },
      { left: 'A', right: '1' },
    ]);
    const result = buildAnswerPayload('MATCHING', { matchingAnswer: wire }, { leftCount: 2 });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(Object.keys(result.answer)).toEqual(['matchingAnswer']);
      // The order the pairs were made in is irrelevant to the grader (it
      // compares sets), so it is passed through untouched, keys and all.
      expect(JSON.parse(result.answer.matchingAnswer as string)).toEqual([
        { left: 'B', right: '2' },
        { left: 'A', right: '1' },
      ]);
    }
  });

  // gradeMatching parses the string into maps: malformed JSON, a stray key, a
  // blank side or a repeated left item can only ever be a 400 or a wrong grade.
  it('refuses a MATCHING answer the grader cannot read', () => {
    const refused = [
      undefined,
      '',
      '   ',
      'not json',
      '{"left":"A","right":"1"}', // an object, not the array the codec stores
      '[]',
      JSON.stringify([{ left: 'A', right: '1', note: 'extra key' }]),
      JSON.stringify([{ left: 'A', right: '' }]),
      JSON.stringify([{ left: 'A', right: '1' }, { left: 'A', right: '2' }]),
      JSON.stringify([{ left: 'A', right: '1' }]), // one of two left items
      JSON.stringify([{ key: 'A', value: '1' }]),
      `"${'x'.repeat(10001)}"`,
    ];
    for (const matchingAnswer of refused) {
      const result = buildAnswerPayload(
        'MATCHING',
        matchingAnswer === undefined ? {} : { matchingAnswer },
        { leftCount: 2 }
      );
      expect(result.ok).toBe(false);
    }
  });

  it('still names the gap for a type the backend does not grade', () => {
    const result = buildAnswerPayload('ESSAY', { textAnswer: 'x' });
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.message).toContain('暂不支持的题型');
    expect(unanswerableTypeNotice('ESSAY')).toContain('ESSAY');
    expect(unanswerableTypeNotice('ORDERING')).toBeNull();
    expect(unanswerableTypeNotice('MATCHING')).toBeNull();
    expect(unanswerableTypeNotice(undefined)).not.toBeNull();
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
    expect(
      describeSubmittedAnswer('ORDERING', { orderingAnswer: [3, 1, 2] })
    ).toBe('提交顺序：第3项 → 第1项 → 第2项');
    expect(
      describeSubmittedAnswer('MATCHING', {
        matchingAnswer: JSON.stringify([
          { left: 'A', right: '1' },
          { left: 'B', right: '2' },
        ]),
      })
    ).toBe('提交配对：A↔1、B↔2');
    expect(describeSubmittedAnswer('MATCHING', { matchingAnswer: 'oops' })).toBe(
      '未配对'
    );
  });

  // An answer read back from GET …/answers has no content to describe: the
  // copy says so instead of dressing up the verdict as the learner's reply.
  it('has no echo to describe for a server-read answer', () => {
    expect(describeSubmittedAnswer('SINGLE_CHOICE', undefined)).toBe(
      '本端未记录作答内容'
    );
  });
});

describe('practicePayloadContext', () => {
  it('counts the paper the way the editor splits it', () => {
    expect(
      practicePayloadContext({
        options: [
          { optionKey: 'A', content: 'a' },
          { optionKey: 'B', content: 'b' },
          { optionKey: 'C', content: 'c' },
        ],
      })
    ).toEqual({ optionCount: 3, leftCount: 2 });
    expect(practicePayloadContext({})).toEqual({ optionCount: 0, leftCount: 0 });
  });

  it('drives the ORDERING gate from the option count', () => {
    const slot = { options: [{ optionKey: 'A', content: 'a' }, { optionKey: 'B', content: 'b' }] };
    expect(
      buildAnswerPayload('ORDERING', { orderingAnswer: [1] }, practicePayloadContext(slot)).ok
    ).toBe(false);
    expect(
      buildAnswerPayload('ORDERING', { orderingAnswer: [2, 1] }, practicePayloadContext(slot)).ok
    ).toBe(true);
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

describe('practiceResultState', () => {
  // A5.3: the finished view renders exactly one of these states, and only
  // `graded` may show a score. The status words are the backend's own three —
  // PracticeSessionService.java:58-60 (STATUS_CREATED / _IN_PROGRESS /
  // _SUBMITTED), the list `lib/backend-vocabulary-contract.test.ts` pins to
  // PRACTICE_SESSION_STATUSES. That vocabulary has NO "not yet graded" session
  // state, so an absent `score` on a SUBMITTED session is its own state.
  const base: PracticeResult = {
    sessionId: 1,
    status: 'SUBMITTED',
    questionCount: 2,
    answeredCount: 2,
    correctCount: 1,
    score: 1,
    maxScore: 2,
  };
  const payload = (extra: PracticeResult = {}): PracticeResult => ({
    ...base,
    ...extra,
  });

  it('shows a score only when the payload carries one', () => {
    expect(practiceResultState(payload())).toEqual({
      kind: 'graded',
      counts: {
        score: 1,
        maxScore: 2,
        answeredCount: 2,
        correctCount: 1,
        questionCount: 2,
      },
    });
    // An absent number stays absent — it is never turned into 0.
    expect(practiceResultState(payload({ score: undefined }))).toEqual({
      kind: 'unscored',
      counts: {
        score: null,
        maxScore: 2,
        answeredCount: 2,
        correctCount: 1,
        questionCount: 2,
      },
    });
    expect(practiceResultState(payload({ maxScore: undefined }))).toEqual({
      kind: 'graded',
      counts: {
        score: 1,
        maxScore: null,
        answeredCount: 2,
        correctCount: 1,
        questionCount: 2,
      },
    });
  });

  // A real 0 from the server is a 0, not a missing value.
  it('keeps a graded zero as a number', () => {
    const state = practiceResultState({ status: 'SUBMITTED', score: 0 });
    expect(state.kind).toBe('graded');
    if (state.kind === 'graded') {
      expect(state.counts.score).toBe(0);
      expect(state.counts.maxScore).toBeNull();
      expect(state.counts.answeredCount).toBeNull();
    }
  });

  it('keeps the server states apart', () => {
    expect(practiceResultState(payload({ status: 'IN_PROGRESS' })).kind).toBe(
      'not-finished'
    );
    expect(practiceResultState(payload({ status: 'CREATED' })).kind).toBe(
      'not-finished'
    );
    // Practice sessions have no GRADED / FINISHED / COMPLETED status — matching
    // invented ones is how a submitted session once reopened as a blank form,
    // so an unknown word is reported, never interpreted.
    expect(practiceResultState(payload({ status: 'GRADED' })).kind).toBe(
      'unknown-status'
    );
    expect(practiceResultState(payload({ status: undefined })).kind).toBe(
      'unknown-status'
    );
    // Enums arrive as bare strings; case and padding are normalized first.
    expect(practiceResultState(payload({ status: ' submitted ' })).kind).toBe(
      'graded'
    );
  });

  it('reads an empty result body as no payload, not as zero', () => {
    expect(practiceResultState(undefined)).toEqual({ kind: 'no-payload' });
    expect(practiceResultState(null)).toEqual({ kind: 'no-payload' });
  });
});

describe('usePracticeResult', () => {
  // The score read is keyed on its own and goes to the result endpoint — the
  // session detail carries no score, and the `finish` response is not a store.
  function renderResultHook(
    getPracticeResult: (...args: never[]) => unknown
  ) {
    const client = createTestQueryClient();
    const apiClient = mockApiClient({ getPracticeResult });
    const harness = ({ children }: { children: ReactNode }) =>
      createElement(ApiClientProvider, {
        client: apiClient,
        children: createElement(QueryClientProvider, { client }, children),
      });
    return {
      client,
      rendered: renderHook(() => usePracticeResult(7, 1), { wrapper: harness }),
    };
  }

  it('reads GET …/result under the practiceResult key', async () => {
    const getPracticeResult = vi.fn(async () => ({
      data: {
        sessionId: 1,
        status: 'SUBMITTED',
        questionCount: 2,
        answeredCount: 2,
        correctCount: 1,
        // The wire sends null where the generated types promise a number.
        score: null,
        maxScore: null,
      },
      response: { status: 200 },
    }));
    const { client, rendered } = renderResultHook(getPracticeResult);

    await waitFor(() => expect(rendered.result.current.isSuccess).toBe(true));
    expect(getPracticeResult).toHaveBeenCalledWith(7, 1);
    expect(client.getQueryData(queryKeys.practiceResult(7, 1))).toEqual(
      expect.objectContaining({ status: 'SUBMITTED' })
    );
    // `score: null` is a missing grade, not 0 — and it is still `unscored` even
    // though the session's own status says SUBMITTED.
    expect(practiceResultState(rendered.result.current.data).kind).toBe(
      'unscored'
    );
  });

  it('reads an absent result body as null', async () => {
    const getPracticeResult = vi.fn(async () => ({
      data: undefined,
      response: { status: 200 },
    }));
    const { rendered } = renderResultHook(getPracticeResult);

    await waitFor(() => expect(rendered.result.current.isSuccess).toBe(true));
    expect(rendered.result.current.data).toBeNull();
    expect(practiceResultState(rendered.result.current.data)).toEqual({
      kind: 'no-payload',
    });
  });

  it('fails the read like any other server call so the page can report it', async () => {
    const getPracticeResult = vi.fn(async () => {
      throw new ApiRequestError(500, {});
    });
    const { rendered } = renderResultHook(getPracticeResult);

    await waitFor(() => expect(rendered.result.current.isError).toBe(true));
    expect(
      normalizeApiError(rendered.result.current.error).message
    ).toContain('服务器暂时无法完成请求');
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

  // A5.2 resume: the server's answer rows land in the SAME cache entries the
  // POST responses wrote, so "已作答" has one source. The view has no answer
  // field, so these entries carry the verdict only — no invented `answer`.
  it('mirrors the server answer rows into the submitted cache', async () => {
    const { result } = renderHook(() => usePracticeLocalState(7, 1), { wrapper });
    act(() => {
      result.current.mirrorServerAnswers(
        [{ practiceSessionQuestionId: 11 }, { practiceSessionQuestionId: 12 }],
        [
          {
            id: 1,
            practiceSessionQuestionId: 11,
            questionId: 101,
            isCorrect: true,
            correctAnswer: 'A',
            answeredAt: '2026-10-13T08:00:00Z',
          },
          // A row for a question that is not on this paper: nowhere to file it.
          { id: 2, practiceSessionQuestionId: 99, questionId: 999, isCorrect: false },
        ]
      );
    });

    await waitFor(() => {
      expect(result.current.state.submitted[11]).toEqual({
        view: expect.objectContaining({ practiceSessionQuestionId: 11, isCorrect: true }),
        source: 'server',
      });
    });
    expect(result.current.state.submitted[11]?.answer).toBeUndefined();
    expect(result.current.state.submitted[12]).toBeUndefined();
    expect(result.current.state.submitted[99]).toBeUndefined();
    // Unanswered questions stay unanswered, and the resume position moves on.
    expect(
      firstOpenSlotIndex(
        toPracticeSlots({
          questions: [
            { practiceSessionQuestionId: 11, questionId: 101 },
            { practiceSessionQuestionId: 12, questionId: 102 },
          ],
        }),
        result.current.state.submitted
      )
    ).toBe(1);
  });

  it('never overwrites the echo of an answer this client posted', async () => {
    const { result } = renderHook(() => usePracticeLocalState(7, 1), { wrapper });
    act(() => {
      result.current.markSubmitted(11, {
        answer: { orderingAnswer: [3, 1, 2] },
        view: { practiceSessionQuestionId: 11, isCorrect: true },
        source: 'submitted',
      });
      result.current.mirrorServerAnswers(
        [{ practiceSessionQuestionId: 11 }],
        [{ practiceSessionQuestionId: 11, isCorrect: false }]
      );
    });

    await waitFor(() => {
      expect(result.current.state.submitted[11]?.answer).toEqual({
        orderingAnswer: [3, 1, 2],
      });
    });
    expect(result.current.state.submitted[11]?.view.isCorrect).toBe(true);
    expect(result.current.state.submitted[11]?.source).toBe('submitted');
  });
});

describe('practiceResultErrorNotice', () => {
  const coded = (code: string) =>
    new ApiRequestError(409, { code, detail: 'english reason from the server' });

  it('names the two reasons the result endpoint distinguishes', () => {
    const notSubmitted = practiceResultErrorNotice(
      coded('PRACTICE_SESSION_NOT_SUBMITTED')
    );
    const notRecorded = practiceResultErrorNotice(
      coded('PRACTICE_RESULT_NOT_RECORDED')
    );

    expect(notSubmitted).toContain('还没有交卷');
    expect(notRecorded).toContain('无分可读');
    // One branch saying the other would make the distinction pointless.
    expect(notSubmitted).not.toBe(notRecorded);
    // The server's English text stays off the screen (C12).
    expect(notSubmitted).not.toContain('english reason');
    expect(notRecorded).not.toContain('english reason');
  });

  it('stays silent for a code it does not know', () => {
    expect(practiceResultErrorNotice(coded('CONFLICT'))).toBeNull();
    expect(practiceResultErrorNotice(new ApiRequestError(500, {}))).toBeNull();
    expect(practiceResultErrorNotice(new Error('other'))).toBeNull();
  });
});
