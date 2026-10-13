/**
 * PracticeSessionPage — answering, resuming and the completion view.
 *
 * Regression coverage for audit W01 (per-question save/submit state whose
 * truth comes from the server), W02 (start/finish failures must be visible
 * with retry / fallback) and W03 + G05 (per-question review built only from
 * data the contract really returns; no frontend grading).
 */

import { describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { QueryClient } from '@tanstack/react-query';
import type { components } from '@aistudy/api-client';
import { PracticeSessionPage } from './PracticeSessionPage';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../../test/test-utils';
import { ApiRequestError, NetworkUnavailableError } from '../../lib/api-error';

type PracticeSessionDetail = components['schemas']['PracticeSessionDetail'];

const SINGLE = {
  practiceSessionQuestionId: 11,
  questionId: 101,
  questionType: 'SINGLE_CHOICE',
  stem: 'Pick one',
  options: [
    { optionKey: 'A', content: 'Alpha' },
    { optionKey: 'B', content: 'Beta' },
  ],
};

const TRUE_FALSE = {
  practiceSessionQuestionId: 12,
  questionId: 102,
  questionType: 'TRUE_FALSE',
  stem: 'Judge this',
};

const SINGLE_2 = {
  practiceSessionQuestionId: 12,
  questionId: 102,
  questionType: 'SINGLE_CHOICE',
  stem: 'Pick again',
  options: [{ optionKey: 'C', content: 'Gamma' }],
};

const FILL = {
  practiceSessionQuestionId: 13,
  questionId: 103,
  questionType: 'FILL_BLANK',
  stem: 'Fill the blanks',
};

const ORDERING = {
  practiceSessionQuestionId: 14,
  questionId: 104,
  questionType: 'ORDERING',
  stem: 'Put these in order',
  options: [
    { optionKey: 'A', content: 'first' },
    { optionKey: 'B', content: 'second' },
  ],
};

const MATCHING = {
  practiceSessionQuestionId: 15,
  questionId: 105,
  questionType: 'MATCHING',
  stem: 'Match the cities',
  options: [
    { optionKey: 'A', content: '巴黎' },
    { optionKey: 'B', content: '东京' },
    { optionKey: '1', content: '法国' },
    { optionKey: '2', content: '日本' },
  ],
};

/** A row of `GET …/answers`: a verdict, and no answer content (A5.2). */
function serverAnswer(slotId: number, extra: Record<string, unknown> = {}) {
  return {
    id: slotId,
    practiceSessionQuestionId: slotId,
    questionId: 100 + slotId,
    isCorrect: true,
    correctAnswer: 'A',
    explanation: `slot ${slotId} read back from the server`,
    score: 1,
    answeredAt: '2026-10-13T07:30:00Z',
    ...extra,
  };
}

function ok<T>(data: T) {
  return { data, response: { status: 200 } };
}

function session(
  status: string,
  questions: NonNullable<PracticeSessionDetail['questions']>,
  extra: Partial<PracticeSessionDetail> = {}
): PracticeSessionDetail {
  return {
    id: 1,
    spaceId: 7,
    status,
    questionCount: questions.length,
    questions,
    ...extra,
  };
}

/** Answers slot 11 correctly and every other slot wrongly. */
const answerServer = vi.fn(async (_spaceId: number, _sessionId: number, body: unknown) => {
  const slotId = (body as { practiceSessionQuestionId: number }).practiceSessionQuestionId;
  const correct = slotId === 11;
  return ok({
    id: slotId,
    practiceSessionQuestionId: slotId,
    questionId: 100 + slotId,
    isCorrect: correct,
    correctAnswer: correct ? 'A' : 'true',
    explanation: correct ? 'Alpha is right' : `slot ${slotId} explanation`,
    score: correct ? 1 : 0,
    answeredAt: '2026-10-13T08:00:00Z',
  });
});

function renderSession(apiClient: ReturnType<typeof mockApiClient>, queryClient: QueryClient) {
  return renderWithProviders(<PracticeSessionPage />, {
    apiClient,
    queryClient,
    initialEntries: ['/spaces/7/practice/1'],
    routePath: '/spaces/:spaceId/practice/:sessionId',
  });
}

async function submitChoice(
  user: ReturnType<typeof userEvent.setup>,
  optionName = 'Alpha'
) {
  await user.click(await screen.findByRole('button', { name: optionName }));
  await user.click(screen.getByRole('button', { name: '提交' }));
  await screen.findByTestId('answer-feedback');
}

/** Selects an option and submits, expecting the request to FAIL. */
async function submitChoiceExpectingError(
  user: ReturnType<typeof userEvent.setup>,
  optionName = 'Alpha'
) {
  await user.click(await screen.findByRole('button', { name: optionName }));
  await user.click(screen.getByRole('button', { name: '提交' }));
  await screen.findByTestId('answer-error');
}

describe('PracticeSessionPage', () => {
  it('shows a loading state while fetching the session', async () => {
    let resolveLoad: ((value: unknown) => void) | undefined;
    const apiClient = mockApiClient({
      getPracticeSession: vi.fn(
        () =>
          new Promise((resolve) => {
            resolveLoad = resolve;
          })
      ),
    });
    renderSession(apiClient, createTestQueryClient());

    expect(screen.getByText('加载练习…')).toBeInTheDocument();

    await act(async () => {
      resolveLoad?.(ok(session('IN_PROGRESS', [SINGLE])));
    });
    expect(await screen.findByText('Pick one')).toBeInTheDocument();
  });

  // W01 (and the G03 shape of the same bug): ONE shared mutation flag kept the
  // next question's submit button disabled after the first answer succeeded.
  it('lets a second question be submitted after the first succeeded', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(answerServer);

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE, TRUE_FALSE]))
        ),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    await submitChoice(user);
    expect(screen.getByText('答对')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '下一题' }));
    expect(screen.queryByTestId('answer-feedback')).toBeNull();
    expect(screen.queryByTestId('answer-error')).toBeNull();

    // TRUE_FALSE must be an explicit choice, never a defaulted `false`.
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: '正确' }));
    await user.click(screen.getByRole('button', { name: '提交' }));

    await waitFor(() => expect(submitPracticeAnswer).toHaveBeenCalledTimes(2));
    expect(submitPracticeAnswer).toHaveBeenNthCalledWith(2, 7, 1, {
      practiceSessionQuestionId: 12,
      answer: { booleanAnswer: true },
    });
    expect(screen.getByText('答错')).toBeInTheDocument();
    expect(screen.getByText(/已提交 2 题/)).toBeInTheDocument();
  });

  // W01: submitted state lived in component state only, so leaving the route
  // and coming back made an already-graded question look untouched.
  it('keeps a server-graded answer when the session is re-entered', async () => {
    const user = userEvent.setup();
    const queryClient = createTestQueryClient();
    const apiClient = mockApiClient({
      getPracticeSession: vi.fn(async () =>
        ok(
          session('IN_PROGRESS', [SINGLE, TRUE_FALSE], {
            startedAt: '2026-10-13T07:00:00Z',
          })
        )
      ),
      submitPracticeAnswer: vi.fn(answerServer),
    });

    const first = renderSession(apiClient, queryClient);
    await submitChoice(user);
    expect(screen.getByText(/已提交 1 题/)).toBeInTheDocument();
    first.unmount();

    // Same query cache (in-app back/forward or a remount), new component.
    renderSession(apiClient, queryClient);
    expect(await screen.findByText('第 1 题 · 单选')).toBeInTheDocument();
    // Not a blank answer form: the server answer is still there...
    expect(screen.getByText(/Alpha is right/)).toBeInTheDocument();
    expect(screen.getByText(/已提交 1 题/)).toBeInTheDocument();
    // ...and that question cannot be answered twice.
    expect(screen.queryByRole('button', { name: '提交' })).toBeNull();
    expect(
      screen.getByRole('button', { name: '第 1 题（已提交）' })
    ).toBeEnabled();
    expect(
      screen.getByRole('button', { name: '第 2 题（未作答）' })
    ).toBeEnabled();

    // The next question is still open for answering.
    await user.click(screen.getByRole('button', { name: '第 2 题（未作答）' }));
    expect(screen.getByText('第 2 题 · 判断')).toBeInTheDocument();
    // ...and its submit is gated on an explicit judgement, not a default.
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
    expect(screen.getByTestId('submit-hint').textContent).toContain(
      '请明确选择'
    );
    await user.click(screen.getByRole('button', { name: '正确' }));
    expect(screen.getByRole('button', { name: '提交' })).toBeEnabled();
    expect(screen.queryByTestId('resume-notice')).toBeNull();
  });

  // W01: no repeat answering of a graded question.
  it('removes the submit control once that question is answered', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(answerServer);

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', [SINGLE]))),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    await submitChoice(user);
    expect(screen.queryByRole('button', { name: '提交' })).toBeNull();
    expect(screen.queryByRole('button', { name: '已提交' })).toBeNull();
    await waitFor(() => expect(submitPracticeAnswer).toHaveBeenCalledTimes(1));
  });

  // W01: a failed answer belongs to ONE question, not to the session.
  it('shows an answer error only on the question that failed', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(
      async (_spaceId: number, _sessionId: number, body: unknown) => {
        const slotId = (body as { practiceSessionQuestionId: number })
          .practiceSessionQuestionId;
        if (slotId === 11) {
          throw new ApiRequestError(400, {
            code: 'VALIDATION_ERROR',
            detail: 'SINGLE_CHOICE requires exactly one selectedOptionKeys entry',
          });
        }
        return ok({
          id: slotId,
          practiceSessionQuestionId: slotId,
          questionId: 102,
          isCorrect: true,
          score: 1,
        });
      }
    );

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE, SINGLE_2]))
        ),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    await submitChoiceExpectingError(user);
    const error = await screen.findByTestId('answer-error');
    expect(error.textContent).toContain('校验未通过');
    expect(error.textContent).toContain('本题仍按未提交处理');
    await user.click(screen.getByRole('button', { name: '下一题' }));
    expect(screen.queryByTestId('answer-error')).toBeNull();
    // Q1 failed, so it is still open — the failure did not mark it submitted.
    expect(screen.getByText(/已提交 0 题/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Gamma' }));
    await user.click(screen.getByRole('button', { name: '提交' }));
    await screen.findByTestId('answer-feedback');
    expect(submitPracticeAnswer).toHaveBeenCalledTimes(2);
  });

  // W01: a 409 says our snapshot of the session is stale — the server wins.
  it('re-reads the session when an answer is rejected with 409', async () => {
    const user = userEvent.setup();
    const getPracticeSession = vi.fn(async () =>
      ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
    );

    renderSession(
      mockApiClient({
        getPracticeSession,
        submitPracticeAnswer: vi.fn(async () => {
          throw new ApiRequestError(409, { code: 'CONFLICT' });
        }),
      }),
      createTestQueryClient()
    );

    await submitChoiceExpectingError(user);
    const error = await screen.findByTestId('answer-error');
    expect(error.textContent).toContain('会话状态已变化');
    expect(error.textContent).toContain('请以服务端最新状态为准');

    await waitFor(() => expect(getPracticeSession).toHaveBeenCalledTimes(2));
    expect(screen.getByText(/已提交 0 题/)).toBeInTheDocument();
  });

  // W02: startSession failures were invisible — the button just felt dead.
  it('reports a failed start with a readable error and a working retry', async () => {
    const user = userEvent.setup();
    const startPracticeSession = vi.fn(async () => ok(undefined));
    startPracticeSession.mockRejectedValueOnce(new ApiRequestError(500, {}));
    const getPracticeSession = vi.fn(async () =>
      ok(session('IN_PROGRESS', [SINGLE]))
    );
    getPracticeSession.mockResolvedValueOnce(ok(session('CREATED', [SINGLE])));

    renderSession(
      mockApiClient({ getPracticeSession, startPracticeSession }),
      createTestQueryClient()
    );

    await user.click(await screen.findByRole('button', { name: '开始作答' }));

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('服务器暂时无法完成请求');
    expect(banner.textContent).toContain('开始练习失败');
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回练习列表' })).toBeInTheDocument();
    // A failed start must not pretend the paper is open for answering.
    expect(screen.queryByRole('button', { name: 'Alpha' })).toBeNull();

    await user.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('Pick one')).toBeInTheDocument();
    await waitFor(() => expect(startPracticeSession).toHaveBeenCalledTimes(2));
  });

  // W02: a 409 on start means the session already moved, so retry re-reads it.
  it('re-reads the session after a 409 on start', async () => {
    const user = userEvent.setup();
    const getPracticeSession = vi.fn(async () => ok(session('IN_PROGRESS', [SINGLE])));
    getPracticeSession.mockResolvedValueOnce(ok(session('CREATED', [SINGLE])));
    const startPracticeSession = vi.fn(async () => {
      throw new ApiRequestError(409, { code: 'CONFLICT' });
    });

    renderSession(
      mockApiClient({ getPracticeSession, startPracticeSession }),
      createTestQueryClient()
    );

    await user.click(await screen.findByRole('button', { name: '开始作答' }));
    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('会话已不在“待开始”状态');
    expect(banner.textContent).toContain('请以服务端最新状态为准');

    await user.click(screen.getByRole('button', { name: '重新读取会话' }));
    expect(await screen.findByText('Pick one')).toBeInTheDocument();
    await waitFor(() => expect(getPracticeSession).toHaveBeenCalledTimes(2));
    expect(startPracticeSession).toHaveBeenCalledTimes(1);
  });

  // W02: finishSession failures were swallowed as silently as start's.
  it('shows the failure instead of a silent result when 交卷 fails', async () => {
    const user = userEvent.setup();
    const finishPracticeSession = vi.fn(async () => {
      throw new NetworkUnavailableError();
    });

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
        ),
        finishPracticeSession,
      }),
      createTestQueryClient()
    );

    await user.click(await screen.findByRole('button', { name: '交卷' }));

    const banner = await screen.findByRole('alert');
    expect(banner.textContent).toContain('无法连接到后端服务');
    expect(banner.textContent).toContain('交卷请求未能送达后端');
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: '练习 #1 — 成绩' })).toBeNull();
    // The paper stays as it was: no answer is lost or invented by the failure.
    expect(screen.getByText('Pick one')).toBeInTheDocument();
    expect(screen.getByText(/已提交 0 题/)).toBeInTheDocument();

    await waitFor(() => expect(finishPracticeSession).toHaveBeenCalledTimes(1));
    await user.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() => expect(finishPracticeSession).toHaveBeenCalledTimes(2));
  });

  // A5.2: the resume read EXISTS now, so a started session with nothing on
  // record means the server really has no answers — not that this client lost
  // them. The copy has to say which of the two it checked.
  it('says plainly when the server has no answers for a started session', async () => {
    const listPracticeAnswers = vi.fn(async () => ok([]));
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
        ),
        listPracticeAnswers,
      }),
      createTestQueryClient()
    );

    const notice = await screen.findByTestId('resume-notice');
    expect(notice.textContent).toContain('此会话此前已开始');
    expect(notice.textContent).toContain('GET /answers');
    expect(notice.textContent).toContain('没有返回任何已作答题目');
    expect(listPracticeAnswers).toHaveBeenCalledWith(7, 1);
    expect(screen.queryByText(/后端没有逐题答案读取端点/)).toBeNull();
  });

  // The read is what makes a refreshed session show its answered questions —
  // no local POST, no cache, just the endpoint's rows.
  it('resumes a refreshed session from listPracticeAnswers, verdict only', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(answerServer);
    const listPracticeAnswers = vi.fn(async () =>
      ok([serverAnswer(11), serverAnswer(12, { isCorrect: false, correctAnswer: 'B' })])
    );

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(
            session('IN_PROGRESS', [SINGLE, TRUE_FALSE], {
              startedAt: '2026-10-13T07:00:00Z',
            })
          )
        ),
        submitPracticeAnswer,
        listPracticeAnswers,
      }),
      createTestQueryClient()
    );

    expect(await screen.findByTestId('answers-loading')).toBeInTheDocument();
    await waitFor(() => expect(listPracticeAnswers).toHaveBeenCalledWith(7, 1));

    // Both questions show as answered from the server's rows.
    expect(await screen.findByText(/已提交 2 题/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '第 1 题（已提交）' })
    ).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '第 2 题（已提交）' })
    ).toBeInTheDocument();
    // Every question answered on the server: nothing left to jump to.
    expect(screen.queryByRole('button', { name: '跳到未提交题' })).toBeNull();

    // The verdict comes through, tri-state, and this client may not write over
    // a question the server already has.
    expect(screen.getByText('答对')).toBeInTheDocument();
    expect(screen.getByText(/slot 11 read back from the server/)).toBeInTheDocument();
    expect(screen.getByText('正确答案：A')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '提交' })).toBeNull();
    expect(submitPracticeAnswer).not.toHaveBeenCalled();

    // …and no answer content is invented: the view does not echo it.
    expect(screen.getByTestId('answer-content-unavailable')).toBeInTheDocument();
    expect(screen.queryByText(/我的作答/)).toBeNull();

    await user.click(screen.getByRole('button', { name: '第 2 题（已提交）' }));
    expect(screen.getByText('第 2 题 · 判断')).toBeInTheDocument();
    expect(screen.getByText('答错')).toBeInTheDocument();
    expect(screen.getByText(/slot 12 read back from the server/)).toBeInTheDocument();
  });

  // An ungraded verdict from the read must not be rendered as 答错 (ADR-031).
  it('keeps the read verdict tri-state for a short answer', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
        ),
        listPracticeAnswers: vi.fn(async () =>
          ok([serverAnswer(11, { isCorrect: null, score: null, explanation: null })])
        ),
      }),
      createTestQueryClient()
    );

    expect(await screen.findByText('后端未返回逐题评分')).toBeInTheDocument();
    expect(screen.queryByText('答对')).toBeNull();
    expect(screen.queryByText('答错')).toBeNull();
    expect(screen.getByTestId('answer-feedback').getAttribute('data-tone')).toBe(
      'ungraded'
    );
  });

  // A failed read must not silently report an untouched paper.
  it('reports a failed answer read instead of pretending nothing was answered', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
        ),
        listPracticeAnswers: vi.fn(async () => {
          throw new ApiRequestError(500, {});
        }),
      }),
      createTestQueryClient()
    );

    const note = await screen.findByTestId('answers-unreadable');
    expect(note.textContent).toContain('无法读取服务端的已作答记录');
    expect(note.textContent).toContain('服务器暂时无法完成请求');
    expect(screen.getByRole('button', { name: '重试读取' })).toBeInTheDocument();
    expect(screen.queryByTestId('resume-notice')).toBeNull();
    // The paper is still usable: the read is a supplement, not a gate.
    expect(screen.getByText('Pick one')).toBeInTheDocument();
  });

  // W03: the review is built from the PracticeAnswerView each POST returned.
  it('lists each answered question with the server verdict, answer and explanation', async () => {
    const user = userEvent.setup();
    const getPracticeSession = vi.fn(async () =>
      ok(session('SUBMITTED', [SINGLE, TRUE_FALSE]))
    );
    getPracticeSession.mockResolvedValueOnce(
      ok(session('IN_PROGRESS', [SINGLE, TRUE_FALSE]))
    );

    renderSession(
      mockApiClient({
        getPracticeSession,
        submitPracticeAnswer: vi.fn(answerServer),
        finishPracticeSession: vi.fn(async () =>
          ok({
            sessionId: 1,
            status: 'SUBMITTED',
            questionCount: 2,
            answeredCount: 2,
            correctCount: 1,
            score: 1,
            maxScore: 2,
          })
        ),
      }),
      createTestQueryClient()
    );

    await submitChoice(user);
    expect(screen.getByText('答对')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '下一题' }));
    await user.click(screen.getByRole('button', { name: '错误' }));
    await user.click(screen.getByRole('button', { name: '提交' }));
    await screen.findByTestId('answer-feedback');

    await user.click(screen.getByRole('button', { name: '交卷' }));

    expect(
      await screen.findByRole('heading', { name: '练习 #1 — 成绩' })
    ).toBeInTheDocument();
    expect(screen.getByText(/得分：1 \/ 2/)).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '逐题回顾' })).toBeInTheDocument();

    // Correct answer + explanation come from the server response, and the
    // user's own submission is shown as what was sent.
    expect(screen.getByText(/Alpha is right/)).toBeInTheDocument();
    expect(screen.getByText(/slot 12 explanation/)).toBeInTheDocument();
    expect(screen.getByText('我的作答：已选 A')).toBeInTheDocument();
    expect(screen.getByText('我的作答：选择：错误')).toBeInTheDocument();
    expect(screen.getByText('正确答案：A')).toBeInTheDocument();
    expect(screen.getByText('正确答案：true')).toBeInTheDocument();
    expect(
      screen.getAllByRole('link', { name: /题目 #/ }).length
    ).toBeGreaterThanOrEqual(1);
  });

  // G05 (reachable part): a session finished in an earlier visit has neither a
  // score nor per-question results — state the gap, never compute (ADR-031).
  it('states the missing result endpoint when a finished session is reopened', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(
            session('SUBMITTED', [SINGLE], {
              startedAt: '2026-10-13T07:00:00Z',
              finishedAt: '2026-10-13T09:00:00Z',
            })
          )
        ),
      }),
      createTestQueryClient()
    );

    expect(
      await screen.findByRole('heading', { name: '练习 #1 — 成绩' })
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '提交' })).toBeNull();
    expect(screen.queryByRole('button', { name: '交卷' })).toBeNull();
    expect(screen.queryByText(/得分：/)).toBeNull();

    const note = await screen.findByTestId('score-unavailable');
    expect(note.textContent).toContain('成绩需后端 result 端点');
    expect(note.textContent).toContain('C06');

    // The per-question read EXISTS now, so the empty list is a fact about this
    // session, not a missing endpoint: it says the server holds nothing.
    const review = await screen.findByTestId('review-unavailable');
    expect(review.textContent).toContain('/answers');
    expect(review.textContent).toContain('空列表');
    expect(review.textContent).not.toContain('没有逐题答案读取端点');
  });

  // A5.2 / W03: a session reopened after 交卷 still gets its per-question
  // review, because the rows come from GET …/answers rather than this client's
  // memory of the POSTs.
  it('builds the review of a finished session from the server answer read', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(
            session('SUBMITTED', [SINGLE, TRUE_FALSE], {
              startedAt: '2026-10-13T07:00:00Z',
              finishedAt: '2026-10-13T09:00:00Z',
            })
          )
        ),
        listPracticeAnswers: vi.fn(async () =>
          ok([serverAnswer(11), serverAnswer(12, { isCorrect: false })])
        ),
      }),
      createTestQueryClient()
    );

    expect(
      await screen.findByRole('heading', { name: '练习 #1 — 成绩' })
    ).toBeInTheDocument();
    expect(await screen.findByText(/已记录 2 \/ 2 题的后端逐题结果/)).toBeInTheDocument();
    expect(screen.getByText(/slot 11 read back from the server/)).toBeInTheDocument();
    expect(screen.getByText(/slot 12 read back from the server/)).toBeInTheDocument();
    // The review line carries the verdict and the server's score in one row, so
    // the label is matched as a substring; exactly one of each verdict.
    expect(screen.getAllByText(/答对/)).toHaveLength(1);
    expect(screen.getAllByText(/答错/)).toHaveLength(1);
    // No invented replies anywhere in the review either.
    expect(screen.getAllByTestId('review-answer-unreadable')).toHaveLength(2);
  });

  it('keeps the finish summary readable after leaving and returning', async () => {
    const user = userEvent.setup();
    const queryClient = createTestQueryClient();
    const getPracticeSession = vi.fn(async () =>
      ok(session('SUBMITTED', [SINGLE]))
    );
    getPracticeSession.mockResolvedValueOnce(ok(session('IN_PROGRESS', [SINGLE])));
    const apiClient = mockApiClient({
      getPracticeSession,
      finishPracticeSession: vi.fn(async () =>
        ok({
          sessionId: 1,
          status: 'SUBMITTED',
          questionCount: 1,
          answeredCount: 1,
          correctCount: 1,
          score: 1,
          maxScore: 1,
        })
      ),
    });

    const first = renderSession(apiClient, queryClient);
    await user.click(await screen.findByRole('button', { name: '交卷' }));
    await screen.findByText(/得分：1 \/ 1/);
    first.unmount();

    renderSession(apiClient, queryClient);
    expect(await screen.findByText(/得分：1 \/ 1/)).toBeInTheDocument();
    expect(screen.getByText(/已作答：1 \/ 1/)).toBeInTheDocument();
    expect(screen.getByText('答对：1')).toBeInTheDocument();
  });

  it('renders the answering UI for a FILL_BLANK question', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(async () =>
      ok({
        id: 13,
        practiceSessionQuestionId: 13,
        questionId: 103,
        isCorrect: true,
        correctAnswer: 'alpha,beta',
        explanation: 'two blanks, comma separated',
        score: 2,
        answeredAt: '2026-10-13T08:00:00Z',
      })
    );

    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', [FILL]))),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    // The shared renderer owns the text control now, so the fill-blank
    // affordance is identified by the comma rule it states, not by a
    // page-local copy of the textarea.
    const box = await screen.findByRole('textbox', { name: '作答' });
    expect(box).toHaveAttribute(
      'placeholder',
      '按题目顺序填写，多个空用英文逗号分隔（需与参考答案写法完全一致）'
    );
    expect(screen.getByRole('button', { name: '提交' })).toBeDisabled();
    await user.type(box, 'alpha,beta');
    await user.click(screen.getByRole('button', { name: '提交' }));

    await waitFor(() =>
      expect(submitPracticeAnswer).toHaveBeenCalledWith(7, 1, {
        practiceSessionQuestionId: 13,
        answer: { textAnswer: 'alpha,beta' },
      })
    );
    expect(
      await screen.findByText(/two blanks, comma separated/)
    ).toBeInTheDocument();
    expect(screen.getByText(/后端得分 2/)).toBeInTheDocument();
    expect(screen.getByText('我的作答：提交内容：alpha,beta')).toBeInTheDocument();
  });

  // C09 closed: ORDERING has its contract field, so the paper is answered with
  // 1-based positions — and only once every option has one.
  it('answers an ORDERING question with the exact orderingAnswer payload', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(answerServer);
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', [ORDERING]))),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    expect(await screen.findByText('Put these in order')).toBeInTheDocument();
    expect(screen.queryByTestId('unsupported-question-type')).toBeNull();

    // Nothing committed yet: the card's own gate keeps 提交 dark.
    const submit = screen.getByRole('button', { name: '提交' });
    expect(submit).toBeDisabled();
    expect(screen.getByTestId('submit-hint').textContent).toContain('排序题');
    await user.click(submit);
    expect(submitPracticeAnswer).not.toHaveBeenCalled();

    // Row 1 down = option 2 in front of option 1 -> positions [2,1].
    await user.click(screen.getByRole('button', { name: '下移第 1 项' }));
    expect(submit).toBeEnabled();
    await user.click(submit);

    await waitFor(() =>
      expect(submitPracticeAnswer).toHaveBeenCalledWith(7, 1, {
        practiceSessionQuestionId: 14,
        answer: { orderingAnswer: [2, 1] },
      })
    );
    expect(await screen.findByTestId('answer-feedback')).toBeInTheDocument();
    expect(screen.getByText('我的作答：提交顺序：第2项 → 第1项')).toBeInTheDocument();
  });

  // MATCHING: the payload is the serialized pair array the grader parses, with
  // exactly {left,right} per pair.
  it('answers a MATCHING question with the exact matchingAnswer payload', async () => {
    const user = userEvent.setup();
    const submitPracticeAnswer = vi.fn(answerServer);
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', [MATCHING]))),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    expect(await screen.findByText('Match the cities')).toBeInTheDocument();
    const submit = screen.getByRole('button', { name: '提交' });
    expect(submit).toBeDisabled();

    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「巴黎」选择配对项' }),
      '1'
    );
    expect(submit).toBeDisabled();
    await user.selectOptions(
      screen.getByRole('combobox', { name: '为「东京」选择配对项' }),
      '2'
    );
    expect(submit).toBeEnabled();
    await user.click(submit);

    await waitFor(() => expect(submitPracticeAnswer).toHaveBeenCalledTimes(1));
    const body = submitPracticeAnswer.mock.calls[0]?.[2] as {
      practiceSessionQuestionId: number;
      answer: { matchingAnswer: string };
    };
    expect(body.practiceSessionQuestionId).toBe(15);
    expect(Object.keys(body.answer)).toEqual(['matchingAnswer']);
    expect(JSON.parse(body.answer.matchingAnswer)).toEqual([
      { left: 'A', right: '1' },
      { left: 'B', right: '2' },
    ]);
    expect(
      await screen.findByText('我的作答：提交配对：A↔1、B↔2')
    ).toBeInTheDocument();
  });

  it('reports an empty session instead of an empty answer form', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', []))),
      }),
      createTestQueryClient()
    );

    expect(await screen.findByText('本次练习没有题目')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '交卷' })).toBeNull();
  });
});
