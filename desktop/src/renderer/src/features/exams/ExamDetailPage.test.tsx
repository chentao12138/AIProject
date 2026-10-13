/**
 * ExamDetailPage — the exam entry point that used to be missing: paper
 * rendering, DRAFT publishing, starting an attempt, and (W07 / A6.3) the
 * learner's own attempt history for this paper, paged by the server.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { ATTEMPT_HISTORY_PAGE_SIZE, ExamDetailPage } from './ExamDetailPage';
import { NOT_FOUND_MESSAGE } from '../../lib/api-error';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const EXAM = {
  id: 21,
  spaceId: 7,
  title: 'Midterm',
  description: 'Chapter 1-3',
  timeLimitMinutes: 90,
  totalScore: 100,
  status: 'DRAFT',
  questions: [
    {
      examQuestionId: 31,
      questionId: 11,
      sortOrder: 1,
      score: 100,
      questionType: 'SINGLE_CHOICE',
      stem: 'Which engine?',
    },
  ],
};

/**
 * One `AttemptSummaryView` as the contract sends it: the server's own status,
 * and a null score/maxScore for an attempt it has not graded (NOT a 0).
 */
function attemptRow(overrides: Record<string, unknown> = {}) {
  return {
    id: 5,
    examId: 21,
    status: 'IN_PROGRESS',
    startedAt: '2026-10-13T09:00:00',
    submittedAt: null,
    score: null,
    maxScore: null,
    ...overrides,
  };
}

/** The `AttemptPageResponse` envelope, with the server's own totals. */
function okPage(
  items: unknown[],
  totals: { page?: number; totalItems?: number; totalPages?: number } = {}
) {
  return {
    data: {
      items,
      page: totals.page ?? 0,
      pageSize: ATTEMPT_HISTORY_PAGE_SIZE,
      totalItems: totals.totalItems ?? items.length,
      totalPages: totals.totalPages ?? 1,
    },
    response: { status: 200 },
  };
}

function renderExam(exam: Record<string, unknown>, overrides = {}) {
  return renderWithProviders(
    <ExamDetailPage />,
    {
      apiClient: mockApiClient({
        getExam: vi.fn(async () => ({ data: exam, response: { status: 200 } })),
        ...overrides,
      }),
      initialEntries: ['/spaces/7/exams/21'],
      routePath: '/spaces/:spaceId/exams/:examId',
    }
  );
}

describe('ExamDetailPage', () => {
  it('renders the exam paper', async () => {
    renderExam(EXAM);

    expect(await screen.findByText('Midterm')).toBeInTheDocument();
    expect(screen.getByText('Chapter 1-3')).toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'Which engine?' })
    ).toHaveAttribute('href', '/spaces/7/questions/11');
  });

  it('rejects a malformed id without calling the API', async () => {
    const getExam = vi.fn(async () => ({ data: EXAM, response: { status: 200 } }));
    renderWithProviders(<ExamDetailPage />, {
      apiClient: mockApiClient({ getExam }),
      initialEntries: ['/spaces/7/exams/not-an-id'],
      routePath: '/spaces/:spaceId/exams/:examId',
    });

    expect(await screen.findByText(NOT_FOUND_MESSAGE)).toBeInTheDocument();
    expect(getExam).not.toHaveBeenCalled();
  });

  it('publishes a draft exam', async () => {
    const user = userEvent.setup();
    const publishExam = vi.fn(async () => ({
      data: { ...EXAM, status: 'PUBLISHED' },
      response: { status: 200 },
    }));

    renderExam(EXAM, { publishExam });

    await user.click(await screen.findByRole('button', { name: '发布考试' }));
    await waitFor(() => expect(publishExam).toHaveBeenCalledWith(7, 21));
  });

  it('starts an attempt on a published exam and routes to it', async () => {
    const user = userEvent.setup();
    const createExamAttempt = vi.fn(async () => ({
      data: { id: 5, examId: 21, status: 'IN_PROGRESS', questions: [] },
      response: { status: 201 },
    }));

    // A splat parent lets the child route change in place, so the assertion
    // is "the attempt screen really rendered" (the target pattern itself is
    // pinned by route-contract.test.ts).
    renderWithProviders(
      <Routes>
        <Route path="exams/:examId" element={<ExamDetailPage />} />
        <Route
          path="exams/:examId/attempts/:attemptId"
          element={<div>attempt-screen</div>}
        />
      </Routes>,
      {
        apiClient: mockApiClient({
          getExam: vi.fn(async () => ({
            data: { ...EXAM, status: 'PUBLISHED' },
            response: { status: 200 },
          })),
          createExamAttempt,
        }),
        initialEntries: ['/spaces/7/exams/21'],
        routePath: '/spaces/:spaceId/*',
      }
    );

    await user.click(await screen.findByRole('button', { name: '开始考试' }));
    await waitFor(() => expect(createExamAttempt).toHaveBeenCalledWith(7, 21));
    expect(await screen.findByText('attempt-screen')).toBeInTheDocument();
  });

  // W05: 409 used to print "请求失败，请稍后重试。", which sends the user to
  // retry something that can never succeed on an unpublished paper.
  it('explains why starting an attempt was refused', async () => {
    const user = userEvent.setup();
    const createExamAttempt = vi.fn(async () => ({
      error: {
        code: 'EXAM_NOT_AVAILABLE',
        detail: 'exam is not available: DRAFT',
      },
      response: { status: 409 },
    }));

    renderExam({ ...EXAM, status: 'PUBLISHED' }, { createExamAttempt });

    await user.click(await screen.findByRole('button', { name: '开始考试' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('该考试当前不可作答');
    // The server's English reason must not reach the UI.
    expect(alert).not.toHaveTextContent('EXAM_NOT_AVAILABLE');
    expect(within(alert).getByRole('link', { name: '返回考试列表' })).toHaveAttribute(
      'href',
      '/spaces/7/exams'
    );
  });

  /*
   * W07 / A6.3 — the paper DOES list this learner's past attempts now:
   * `GET /api/v1/spaces/{spaceId}/exam-attempts?examId=&page=&size=` is in the
   * contract, so the honest assertion is that the real list renders, is paged
   * by the SERVER, and links every row to the attempt route.
   */
  it('lists my attempts for this paper, paged by the server', async () => {
    const listExamAttempts = vi.fn(async () =>
      okPage([
        attemptRow({ id: 5, status: 'IN_PROGRESS' }),
        attemptRow({
          id: 6,
          status: 'SUBMITTED',
          submittedAt: '2026-10-12T11:00:00',
          score: 18,
          maxScore: 20,
        }),
      ])
    );
    renderExam({ ...EXAM, status: 'PUBLISHED' }, { listExamAttempts });

    // examId + page/size go to the server: a local slice of some other page is
    // exactly what "paged by the server" rules out.
    await waitFor(() =>
      expect(listExamAttempts).toHaveBeenCalledWith(7, {
        examId: 21,
        page: 0,
        size: ATTEMPT_HISTORY_PAGE_SIZE,
      })
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByRole('row', { name: /#5/ })).toBeInTheDocument();
    expect(within(section).getByRole('row', { name: /#6/ })).toBeInTheDocument();
    // The pager reads the server's totals, not `items.length`.
    expect(within(section).getByTestId('attempt-history-total')).toHaveTextContent(
      '第 1 / 1 页 · 共 2 条'
    );

    // Every row links to the existing attempt route.
    expect(within(section).getByRole('link', { name: '继续作答 #5' })).toHaveAttribute(
      'href',
      '/spaces/7/exams/21/attempts/5'
    );
    expect(within(section).getByRole('link', { name: '查看成绩 #6' })).toHaveAttribute(
      'href',
      '/spaces/7/exams/21/attempts/6'
    );
  });

  it('asks the server for the next page instead of slicing locally', async () => {
    const user = userEvent.setup();
    const listExamAttempts = vi.fn(
      async (_spaceId: number, query?: { page?: number }) =>
        okPage([attemptRow({ id: 5 + (query?.page ?? 0) })], {
          page: query?.page ?? 0,
          totalItems: 25,
          totalPages: 3,
        })
    );
    renderExam({ ...EXAM, status: 'PUBLISHED' }, { listExamAttempts });

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByTestId('attempt-history-total')).toHaveTextContent(
      '第 1 / 3 页 · 共 25 条'
    );
    expect(within(section).getByRole('row', { name: /#5/ })).toBeInTheDocument();

    await user.click(within(section).getByRole('button', { name: '下一页' }));

    await waitFor(() =>
      expect(listExamAttempts).toHaveBeenCalledWith(7, {
        examId: 21,
        page: 1,
        size: ATTEMPT_HISTORY_PAGE_SIZE,
      })
    );
    // Page 2 is the SERVER's page 2 — the row is the one that fixture put there.
    expect(await within(section).findByRole('row', { name: /#6/ })).toBeInTheDocument();
    expect(within(section).getByTestId('attempt-history-total')).toHaveTextContent(
      '第 2 / 3 页'
    );
  });

  it('keeps an attempt the server has not graded scoreless instead of 0 分', async () => {
    renderExam(
      { ...EXAM, status: 'PUBLISHED' },
      {
        listExamAttempts: vi.fn(async () =>
          okPage([
            // The server's own row for a submitted attempt whose result does
            // not exist yet: score/maxScore are null, NOT 0.
            attemptRow({ id: 7, status: 'SUBMITTED', score: null, maxScore: null }),
            attemptRow({ id: 8, status: 'SUBMITTED', score: 0, maxScore: 20 }),
          ])
        ),
      }
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    const ungraded = within(section).getByRole('row', { name: /#7/ });
    expect(within(ungraded).getByText('已交卷，等待评分')).toBeInTheDocument();
    // The score cell itself — the 交卷时间 cell is — too, and the pager note
    // uses — for nothing at all, so the column is addressed by name.
    expect(within(ungraded).getByTestId('attempt-score-7')).toHaveTextContent('—');
    expect(ungraded.textContent).not.toContain('0 / 0');
    expect(ungraded.textContent).not.toContain('答错');

    // A graded 0 分 IS a score and is printed as one — the two must not be
    // confused by a falsy check.
    const graded = within(section).getByRole('row', { name: /#8/ });
    expect(within(graded).getByText('已评分')).toBeInTheDocument();
    expect(within(graded).getByTestId('attempt-score-8')).toHaveTextContent('0 / 20');
  });

  it('carries the server score of a graded attempt and nothing of an open one', async () => {
    renderExam(
      { ...EXAM, status: 'PUBLISHED' },
      {
        listExamAttempts: vi.fn(async () =>
          okPage([
            attemptRow({ id: 9, status: 'SUBMITTED', score: 12, maxScore: 20 }),
            attemptRow({ id: 10, status: 'IN_PROGRESS', score: 7, maxScore: 20 }),
          ])
        ),
      }
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByTestId('attempt-score-9')).toHaveTextContent('12 / 20');
    // A score the server sent on an attempt that is still being answered is
    // not a result yet: status decides, and 进行中 is not graded.
    expect(within(section).getByTestId('attempt-score-10')).toHaveTextContent('—');
    expect(within(section).getByText('作答进行中')).toBeInTheDocument();
  });

  it('offers to continue only what the server still has open', async () => {
    renderExam(
      { ...EXAM, status: 'PUBLISHED' },
      {
        listExamAttempts: vi.fn(async () =>
          okPage([
            attemptRow({ id: 5, status: 'NOT_STARTED' }),
            attemptRow({ id: 6, status: 'IN_PROGRESS' }),
            attemptRow({ id: 7, status: 'SUBMITTED', score: 12, maxScore: 20 }),
          ])
        ),
      }
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByRole('link', { name: '开始作答 #5' })).toBeInTheDocument();
    expect(within(section).getByRole('link', { name: '继续作答 #6' })).toBeInTheDocument();
    // No resume affordance for a submitted attempt, however it is worded.
    expect(within(section).queryByRole('link', { name: /继续作答 #7/ })).not.toBeInTheDocument();
    expect(within(section).getByRole('link', { name: '查看成绩 #7' })).toBeInTheDocument();
    expect(within(section).getByText('作答进行中')).toBeInTheDocument();
  });

  it('says so when the history read fails instead of showing an empty list', async () => {
    const user = userEvent.setup();
    const listExamAttempts = vi
      .fn()
      .mockResolvedValueOnce({
        error: { code: 'INTERNAL_ERROR', detail: 'boom' },
        response: { status: 500 },
      })
      .mockResolvedValue(okPage([attemptRow({ id: 5 })]));

    renderExam({ ...EXAM, status: 'PUBLISHED' }, { listExamAttempts });

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByRole('alert')).toHaveTextContent('服务器暂时无法完成请求');
    expect(within(section).queryByText('还没有作答记录')).not.toBeInTheDocument();

    await user.click(within(section).getByRole('button', { name: '重试' }));
    expect(await within(section).findByRole('row', { name: /#5/ })).toBeInTheDocument();
  });

  // The backend scopes this path: a non-member gets 404 from
  // `SpaceScopeInterceptor`, NOT an empty page — and 404 copy must not be
  // rendered as "you have no attempts".
  it('reads a 404 on the history path as inaccessible, not as an empty list', async () => {
    renderExam(
      { ...EXAM, status: 'PUBLISHED' },
      {
        listExamAttempts: vi.fn(async () => ({
          error: { code: 'NOT_FOUND', detail: 'space not visible' },
          response: { status: 404 },
        })),
      }
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByRole('alert')).toHaveTextContent(
      '资源不存在或当前不可访问'
    );
    expect(within(section).queryByText('还没有作答记录')).not.toBeInTheDocument();
  });

  it('shows an empty state for a paper that was never attempted', async () => {
    renderExam(
      { ...EXAM, status: 'PUBLISHED' },
      { listExamAttempts: vi.fn(async () => okPage([])) }
    );

    const section = await screen.findByRole('region', { name: '作答历史' });
    expect(within(section).getByText('还没有作答记录')).toBeInTheDocument();
  });
});
