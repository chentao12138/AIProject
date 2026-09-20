/**
 * ExamDetailPage — the exam entry point that used to be missing: paper
 * rendering, DRAFT publishing and starting an attempt.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { ExamDetailPage } from './ExamDetailPage';
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
});
