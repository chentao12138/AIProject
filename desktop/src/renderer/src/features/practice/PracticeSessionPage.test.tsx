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

  it('warns honestly when a started session has no readable answers', async () => {
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () =>
          ok(session('IN_PROGRESS', [SINGLE], { startedAt: '2026-10-13T07:00:00Z' }))
        ),
      }),
      createTestQueryClient()
    );

    const notice = await screen.findByTestId('resume-notice');
    expect(notice.textContent).toContain('此会话此前已开始');
    expect(notice.textContent).toContain('后端没有逐题答案读取端点');
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

    const review = await screen.findByTestId('review-unavailable');
    expect(review.textContent).toContain('PracticeAnswerView');
    expect(review.textContent).toContain('result');
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

    const box = await screen.findByRole('textbox', { name: '填空作答' });
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

  // Audit C09: AnswerPayloadView has no orderingAnswer / matchingAnswer, so
  // those types cannot be answered from this client at all.
  it('explains why an ORDERING question cannot be answered yet', async () => {
    const submitPracticeAnswer = vi.fn(async () => ok({ id: 14 }));
    renderSession(
      mockApiClient({
        getPracticeSession: vi.fn(async () => ok(session('IN_PROGRESS', [ORDERING]))),
        submitPracticeAnswer,
      }),
      createTestQueryClient()
    );

    const note = await screen.findByTestId('unsupported-question-type');
    expect(note.textContent).toContain('orderingAnswer');
    expect(screen.queryByRole('button', { name: '提交' })).toBeNull();
    expect(submitPracticeAnswer).not.toHaveBeenCalled();
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
