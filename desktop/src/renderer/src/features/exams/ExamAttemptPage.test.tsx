/**
 * ExamAttemptPage — per-question saving (G03/W04), visible failures (W05),
 * progress + server deadline (W06).
 *
 * The first test is the G03 regression gate: it fails against the old page,
 * which fed the single shared `submitAnswer.isSuccess` into every slot, so the
 * second question's "保存答案" stayed disabled forever.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ApiClient } from '@aistudy/api-client';
import { ExamAttemptPage } from './ExamAttemptPage';
import { NetworkUnavailableError } from '../../lib/api-error';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

/** Same shape `mockApiClient` takes, spelled out so a helper can forward it. */
type ApiClientOverrides = Partial<
  Record<keyof ApiClient, (...args: never[]) => unknown>
>;

const QUESTION_1 = {
  examQuestionId: 11,
  questionId: 101,
  sortOrder: 1,
  score: 5,
  questionType: 'SINGLE_CHOICE',
  stem: '第一题题干',
  options: [
    { optionKey: 'A', content: '甲选项' },
    { optionKey: 'B', content: '乙选项' },
  ],
};

const QUESTION_2 = {
  examQuestionId: 12,
  questionId: 102,
  sortOrder: 2,
  score: 5,
  questionType: 'SINGLE_CHOICE',
  stem: '第二题题干',
  options: [
    { optionKey: 'A', content: '丙选项' },
    { optionKey: 'B', content: '丁选项' },
  ],
};

const ok = (data: unknown) => ({ data, response: { status: 200 } });
const fail = (status: number, detail: string) => ({
  error: { code: 'CONFLICT', detail },
  response: { status },
});

/** The contract's ExamAnswerView: identity + gradingStatus + answeredAt. */
const answerRecord = (examQuestionId: number) => ({
  id: 900 + examQuestionId,
  examAttemptId: 5,
  examQuestionId,
  gradingStatus: 'GRADED',
  answeredAt: '2026-10-13T10:00:00',
});

function mockSave() {
  return vi.fn(
    async (_spaceId: number, _attemptId: number, body: { examQuestionId: number }) =>
      ok(answerRecord(body.examQuestionId))
  );
}

function renderAttempt(overrides: ApiClientOverrides = {}) {
  return renderWithProviders(<ExamAttemptPage />, {
    apiClient: mockApiClient({
      getExamAttempt: vi.fn(async () =>
        ok({
          id: 5,
          examId: 21,
          examPaperId: 31,
          status: 'IN_PROGRESS',
          questions: [QUESTION_1, QUESTION_2],
        })
      ),
      ...overrides,
    }),
    initialEntries: ['/spaces/7/exams/21/attempts/5'],
    routePath: '/spaces/:spaceId/exams/:examId/attempts/:attemptId',
  });
}

describe('ExamAttemptPage', () => {
  // G03 regression gate.
  it('keeps the next question saveable after one answer was saved', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = mockSave();
    renderAttempt({ submitExamAnswer });

    await user.click(await screen.findByRole('button', { name: '甲选项' }));
    await user.click(screen.getByRole('button', { name: '保存答案' }));

    // Slot 11 is saved...
    expect(await screen.findByRole('button', { name: '已保存' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '下一题' }));
    expect(await screen.findByText('第二题题干')).toBeInTheDocument();

    // ...and slot 12 must still have its OWN working button. Against the old
    // page this lookup throws (the label was the inherited '已保存') and the
    // enablement assertion fails on the inherited `disabled`.
    await user.click(screen.getByRole('button', { name: '丙选项' }));
    const secondSave = screen.getByRole('button', { name: '保存答案' });
    expect(secondSave).toBeEnabled();

    await user.click(secondSave);

    await waitFor(() =>
      expect(submitExamAnswer).toHaveBeenCalledWith(7, 5, {
        examQuestionId: 12,
        answer: { selectedOptionKeys: ['A'] },
      })
    );
    expect(await screen.findByRole('button', { name: '已保存' })).toBeInTheDocument();

    // Both slots stay saved independently: going back shows slot 11 as saved.
    await user.click(screen.getByRole('button', { name: '上一题' }));
    expect(await screen.findByRole('button', { name: '已保存' })).toBeInTheDocument();
    expect(await screen.findByText('第一题题干')).toBeInTheDocument();
  });

  it('marks a three-question paper saveable slot by slot', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = mockSave();
    const QUESTION_3 = {
      ...QUESTION_2,
      examQuestionId: 13,
      questionId: 103,
      stem: '第三题题干',
    };

    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({
          id: 5,
          examId: 21,
          status: 'IN_PROGRESS',
          questions: [QUESTION_1, QUESTION_2, QUESTION_3],
        })
      ),
      submitExamAnswer,
    });

    for (const [optionName, examQuestionId] of [
      ['甲选项', 11],
      ['丙选项', 12],
    ] as const) {
      await user.click(await screen.findByRole('button', { name: optionName }));
      await user.click(screen.getByRole('button', { name: '保存答案' }));
      await waitFor(() =>
        expect(submitExamAnswer).toHaveBeenCalledWith(
          7,
          5,
          expect.objectContaining({ examQuestionId })
        )
      );
      await user.click(screen.getByRole('button', { name: '下一题' }));
    }

    // The third slot was never touched, so it must not inherit anything:
    // choosing an option has to make ITS own button clickable.
    expect(await screen.findByText('第三题题干')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '丁选项' }));
    const thirdSave = screen.getByRole('button', { name: '保存答案' });
    expect(thirdSave).toBeEnabled();

    await user.click(thirdSave);
    await waitFor(() =>
      expect(submitExamAnswer).toHaveBeenCalledWith(
        7,
        5,
        expect.objectContaining({ examQuestionId: 13 })
      )
    );
  });

  // W06: answered/unanswered from server-confirmed slots, never from drafts.
  it('reports answered/unanswered counts from the server, not from drafts', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = mockSave();
    renderAttempt({ submitExamAnswer });

    expect(await screen.findByText(/已答 0 · 未答 2/)).toBeInTheDocument();

    // Typing without saving must NOT count as answered.
    await user.click(screen.getByRole('button', { name: '甲选项' }));
    expect(screen.getByText(/已答 0 · 未答 2/)).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '保存答案' }));
    expect(await screen.findByText(/已答 1 · 未答 1/)).toBeInTheDocument();
  });

  it('marks the sidebar slot of a saved answer as answered', async () => {
    const user = userEvent.setup();
    renderAttempt({ submitExamAnswer: mockSave() });

    // `<aside>` -> complementary: the option list is a `list` too, so the
    // sidebar has to be scoped before its buttons are picked.
    const sidebar = await screen.findByRole('complementary');
    const list = within(sidebar).getByRole('list');

    await user.click(await screen.findByRole('button', { name: '甲选项' }));
    await user.click(screen.getByRole('button', { name: '保存答案' }));
    await screen.findByRole('button', { name: '已保存' });

    // Leaving slot 1 is what lets its own state show as answered rather than
    // "current" in the list.
    await user.click(screen.getByRole('button', { name: '下一题' }));
    expect(await screen.findByText('第二题题干')).toBeInTheDocument();

    expect(within(list).getByRole('button', { name: '1' })).toHaveClass(
      'btn--secondary'
    );
    // Slot 2 is only highlighted because it is the current one — it must not
    // be styled as answered.
    expect(within(list).getByRole('button', { name: '2' })).not.toHaveClass(
      'btn--secondary'
    );
  });

  // W06: the server deadline is now visible DURING the attempt, not only on
  // the start screen.
  it('shows the remaining time of an attempt that carries a deadline', async () => {
    const deadlineAt = new Date(Date.now() + 10 * 60_000).toISOString();
    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({
          id: 5,
          examId: 21,
          status: 'IN_PROGRESS',
          deadlineAt,
          questions: [QUESTION_1, QUESTION_2],
        })
      ),
    });

    expect(await screen.findByText(/^剩余时间 \d{2}:\d{2}$/)).toBeInTheDocument();
  });

  it('shows no timer when the attempt has no server deadline', async () => {
    renderAttempt();
    expect(await screen.findByText('第一题题干')).toBeInTheDocument();
    // `ExamAttemptService.start` leaves deadlineAt null without a time limit,
    // so nothing may be invented here.
    expect(screen.queryByText(/剩余时间/)).not.toBeInTheDocument();
    expect(screen.queryByText(/答题时间已结束/)).not.toBeInTheDocument();
  });

  // W06: expired handling defers to the server instead of guessing.
  it('stops answering once the server deadline has passed', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = mockSave();
    const getExamAttempt = vi.fn(async () =>
      ok({
        id: 5,
        examId: 21,
        status: 'IN_PROGRESS',
        deadlineAt: new Date(Date.now() - 60_000).toISOString(),
        questions: [QUESTION_1, QUESTION_2],
      })
    );
    renderAttempt({ getExamAttempt, submitExamAnswer });

    expect(await screen.findByText(/答题时间已结束/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '保存答案' })).toBeDisabled();
    // 交卷 can only ever 409 now (`submit` finalizes first), so it is replaced
    // by the action that actually ends the attempt.
    expect(screen.queryByRole('button', { name: '交卷' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '保存答案' }));
    expect(submitExamAnswer).not.toHaveBeenCalled();
  });

  it('ends an overdue attempt through the result endpoint, then goes read-only', async () => {
    const user = userEvent.setup();
    // Mirrors the backend: the overdue attempt keeps reporting IN_PROGRESS
    // until `getResult` runs `finalizeIfExpired`, after which the re-read says
    // SUBMITTED.
    let calls = 0;
    const getExamAttempt = vi.fn(async () => {
      calls += 1;
      return ok({
        id: 5,
        examId: 21,
        status: calls === 1 ? 'IN_PROGRESS' : 'SUBMITTED',
        deadlineAt: new Date(Date.now() - 60_000).toISOString(),
        questions: calls === 1 ? [QUESTION_1, QUESTION_2] : [],
      });
    });
    const getExamResult = vi.fn(async () =>
      ok({
        attemptId: 5,
        status: 'SUBMITTED',
        score: 0,
        maxScore: 10,
        correctCount: 0,
        wrongCount: 0,
        unansweredCount: 2,
        items: [],
      })
    );
    renderAttempt({ getExamAttempt, getExamResult });

    await user.click(await screen.findByRole('button', { name: '结束并查看成绩' }));

    await waitFor(() => expect(getExamResult).toHaveBeenCalledWith(7, 5));
    expect(await screen.findByRole('heading', { name: /成绩/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /保存答案|交卷/ })).not.toBeInTheDocument();
  });

  // W05: failures must never be silent.
  it('surfaces a startAttempt 409 with a reload instead of a dead retry', async () => {
    const user = userEvent.setup();
    const getExamAttempt = vi.fn(async () =>
      ok({
        id: 5,
        examId: 21,
        status: 'NOT_STARTED',
        questions: [QUESTION_1, QUESTION_2],
      })
    );
    const startExamAttempt = vi.fn(async () =>
      fail(409, 'exam attempt is not IN_PROGRESS (EXAM_ATTEMPT_NOT_IN_PROGRESS): SUBMITTED')
    );

    renderAttempt({ getExamAttempt, startExamAttempt });

    await user.click(await screen.findByRole('button', { name: '开始考试' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('本次考试已不在作答中状态');
    // Never leak the server's English reason.
    expect(alert).not.toHaveTextContent('IN_PROGRESS');

    await user.click(within(alert).getByRole('button', { name: '重试' }));
    await waitFor(() => expect(getExamAttempt).toHaveBeenCalledTimes(2));
    expect(startExamAttempt).toHaveBeenCalledTimes(1);
  });

  it('surfaces a network failure on start and lets the user retry it', async () => {
    const user = userEvent.setup();
    const startExamAttempt = vi
      .fn()
      .mockRejectedValueOnce(new NetworkUnavailableError())
      .mockResolvedValue(ok({ id: 5, status: 'IN_PROGRESS', questions: [] }));

    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({ id: 5, examId: 21, status: 'NOT_STARTED', questions: [QUESTION_1] })
      ),
      startExamAttempt,
    });

    await user.click(await screen.findByRole('button', { name: '开始考试' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('无法连接到后端服务');

    await user.click(within(alert).getByRole('button', { name: '重试' }));
    await waitFor(() => expect(startExamAttempt).toHaveBeenCalledTimes(2));
  });

  it('offers only the fallback route for a 401 on start', async () => {
    const user = userEvent.setup();
    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({ id: 5, examId: 21, status: 'NOT_STARTED', questions: [QUESTION_1] })
      ),
      startExamAttempt: vi.fn(async () => ({
        error: { code: 'UNAUTHORIZED' },
        response: { status: 401 },
      })),
    });

    await user.click(await screen.findByRole('button', { name: '开始考试' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('登录状态已失效，请重新登录。');
    // Retrying cannot fix an expired session.
    expect(within(alert).queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
    expect(within(alert).getByRole('link', { name: '返回考试列表' })).toHaveAttribute(
      'href',
      '/spaces/7/exams'
    );
  });

  // W05: the retry offered has to be one that can work.
  it('re-runs a submit that failed transiently', async () => {
    const user = userEvent.setup();
    const submitExamAttempt = vi
      .fn()
      .mockResolvedValueOnce({ error: { code: 'INTERNAL_ERROR' }, response: { status: 500 } })
      .mockResolvedValue(ok({ attemptId: 5, status: 'SUBMITTED', score: 1 }));

    renderAttempt({ submitExamAttempt });

    await user.click(await screen.findByRole('button', { name: '交卷' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求');

    await user.click(within(alert).getByRole('button', { name: '重试' }));
    await waitFor(() => expect(submitExamAttempt).toHaveBeenCalledTimes(2));
  });

  it('re-reads the attempt when the submit says it is already settled', async () => {
    const user = userEvent.setup();
    const getExamAttempt = vi.fn(async () =>
      ok({
        id: 5,
        examId: 21,
        status: 'IN_PROGRESS',
        questions: [QUESTION_1, QUESTION_2],
      })
    );
    const submitExamAttempt = vi.fn(async () =>
      fail(409, 'only IN_PROGRESS attempts can be submitted: SUBMITTED')
    );

    renderAttempt({ getExamAttempt, submitExamAttempt });

    await user.click(await screen.findByRole('button', { name: '交卷' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('本次考试状态已变更');

    // Re-submitting can never succeed here, so "重试" refreshes the server
    // state instead — the result view is where an already submitted attempt
    // belongs (W04 read-only).
    await user.click(within(alert).getByRole('button', { name: '重试' }));
    await waitFor(() => expect(getExamAttempt).toHaveBeenCalledTimes(2));
    expect(submitExamAttempt).toHaveBeenCalledTimes(1);
  });

  it('keeps an answer failure on its own slot', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = vi.fn(async (_s: number, _a: number, body: { examQuestionId: number }) =>
      body.examQuestionId === 11
        ? fail(409, 'exam deadline exceeded (EXAM_DEADLINE_EXCEEDED)')
        : ok(answerRecord(body.examQuestionId))
    );
    renderAttempt({ submitExamAnswer });

    await user.click(await screen.findByRole('button', { name: '甲选项' }));
    await user.click(screen.getByRole('button', { name: '保存答案' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('答题时间已结束');
    expect(screen.getByRole('button', { name: '重试保存' })).toBeEnabled();

    // The failure must not follow the candidate to slot 12.
    await user.click(screen.getByRole('button', { name: '下一题' }));
    expect(await screen.findByText('第二题题干')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: '丙选项' }));
    expect(screen.queryAllByRole('alert')).toHaveLength(0);

    const secondSave = screen.getByRole('button', { name: '保存答案' });
    expect(secondSave).toBeEnabled();
    await user.click(secondSave);
    await waitFor(() =>
      expect(submitExamAnswer).toHaveBeenCalledWith(7, 5, {
        examQuestionId: 12,
        answer: { selectedOptionKeys: ['A'] },
      })
    );
  });

  // W04: "已保存" is decided by the server record, not by a resolved promise.
  it('does not claim saved when the POST returned no answeredAt', async () => {
    const user = userEvent.setup();
    renderAttempt({
      submitExamAnswer: vi.fn(async () =>
        ok({ id: 7, examAttemptId: 5, examQuestionId: 11, gradingStatus: 'GRADED' })
      ),
    });

    await user.click(await screen.findByRole('button', { name: '甲选项' }));
    await user.click(screen.getByRole('button', { name: '保存答案' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '服务器未返回已保存的答题记录'
    );
    expect(screen.queryByRole('button', { name: '已保存' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试保存' })).toBeEnabled();
  });

  it('lets a saved answer be corrected instead of locking the slot', async () => {
    const user = userEvent.setup();
    const submitExamAnswer = mockSave();
    renderAttempt({ submitExamAnswer });

    await user.click(await screen.findByRole('button', { name: '甲选项' }));
    await user.click(screen.getByRole('button', { name: '保存答案' }));
    await screen.findByRole('button', { name: '已保存' });

    // Editing makes the draft differ from the stored record -> idle again.
    // (The backend overwrites an answer for the same examQuestionId.)
    await user.click(screen.getByRole('button', { name: '乙选项' }));
    const resave = screen.getByRole('button', { name: '保存答案' });
    expect(resave).toBeEnabled();
    await user.click(resave);

    await waitFor(() => expect(submitExamAnswer).toHaveBeenCalledTimes(2));
    expect(submitExamAnswer).toHaveBeenLastCalledWith(7, 5, {
      examQuestionId: 11,
      answer: { selectedOptionKeys: ['B'] },
    });
  });

  // W04: a SUBMITTED attempt is read-only, with no way to write again.
  it('renders a submitted attempt as read-only result', async () => {
    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({
          id: 5,
          examId: 21,
          status: 'SUBMITTED',
          submittedAt: '2026-10-13T10:30:00',
          questions: [QUESTION_1],
        })
      ),
      getExamResult: vi.fn(async () =>
        ok({
          attemptId: 5,
          status: 'SUBMITTED',
          score: 5,
          maxScore: 10,
          correctCount: 1,
          wrongCount: 0,
          unansweredCount: 0,
          items: [
            {
              examQuestionId: 11,
              questionId: 101,
              score: 5,
              maxScore: 10,
              isCorrect: true,
              answered: true,
            },
          ],
        })
      ),
      submitExamAnswer: vi.fn(),
      submitExamAttempt: vi.fn(),
    });

    expect(await screen.findByRole('heading', { name: /成绩/ })).toBeInTheDocument();
    expect(screen.getByText(/得分：5 \/ 10/)).toBeInTheDocument();
    expect(screen.getByText(/只读/)).toBeInTheDocument();
    // No writing affordances at all.
    expect(screen.queryByRole('button', { name: /保存答案|已保存/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '交卷' })).not.toBeInTheDocument();
    expect(screen.queryByText('第一题题干')).not.toBeInTheDocument();
  });

  // Not-started screen keeps its server deadline display.
  it('shows the server deadline on the not-started screen', async () => {
    renderAttempt({
      getExamAttempt: vi.fn(async () =>
        ok({
          id: 5,
          examId: 21,
          status: 'NOT_STARTED',
          deadlineAt: '2026-10-13T12:00:00',
          questions: [QUESTION_1],
        })
      ),
    });

    expect(await screen.findByRole('button', { name: '开始考试' })).toBeInTheDocument();
    expect(screen.getByText(/截止时间：/)).toBeInTheDocument();
    // Nothing is answered yet, so no countdown on a paper not started.
    expect(screen.queryByText(/^剩余时间/)).not.toBeInTheDocument();
  });
});
