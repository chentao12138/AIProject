/**
 * PracticeSessionPage — active session answer flow and result view.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PracticeSessionPage } from './PracticeSessionPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('PracticeSessionPage', () => {
  it('shows a loading state while fetching the session', async () => {
    renderWithProviders(<PracticeSessionPage />, {
      apiClient: mockApiClient({
        getPracticeSession: vi.fn(async () => ({
          data: {
            id: 1,
            status: 'IN_PROGRESS',
            questions: [
              {
                practiceSessionQuestionId: 11,
                questionId: 101,
                questionType: 'SINGLE_CHOICE',
                stem: 'Pick one',
                options: [
                  { optionKey: 'A', content: 'Alpha' },
                  { optionKey: 'B', content: 'Beta' },
                ],
              },
            ],
          },
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/practice/1'],
      routePath: '/spaces/:spaceId/practice/:sessionId',
    });

    expect(await screen.findByText('Pick one')).toBeInTheDocument();
  });

  it('submits an answer and shows the result view when the session is finished', async () => {
    const user = userEvent.setup();
    const inProgressData = {
      data: {
        id: 1,
        status: 'IN_PROGRESS',
        questions: [
          {
            practiceSessionQuestionId: 11,
            questionId: 101,
            questionType: 'SINGLE_CHOICE',
            stem: 'Pick one',
            options: [
              { optionKey: 'A', content: 'Alpha' },
              { optionKey: 'B', content: 'Beta' },
            ],
          },
        ],
      },
      response: { status: 200 },
    } as const;

    const completedData = {
      data: {
        id: 1,
        status: 'COMPLETED',
        score: 1,
        maxScore: 1,
        answeredCount: 1,
        correctCount: 1,
        questionCount: 1,
      },
      response: { status: 200 },
    } as const;

    const getPracticeSession = vi.fn(async () => inProgressData);

    renderWithProviders(<PracticeSessionPage />, {
      apiClient: mockApiClient({
        getPracticeSession,
        submitPracticeAnswer: vi.fn(async () => ({
          data: { id: 11, isCorrect: true, score: 1, maxScore: 1 },
          response: { status: 200 },
        })),
        finishPracticeSession: vi.fn(async () => completedData),
      }),
      initialEntries: ['/spaces/7/practice/1'],
      routePath: '/spaces/:spaceId/practice/:sessionId',
    });

    getPracticeSession.mockImplementationOnce(async () => inProgressData as any);
    getPracticeSession.mockImplementationOnce(async () => completedData as any);

    await user.click(await screen.findByRole('button', { name: 'Alpha' }));
    await user.click(screen.getByRole('button', { name: 'Submit' }));
    await user.click(screen.getByRole('button', { name: 'Finish' }));

    expect(await screen.findByText(/Practice #1/)).toBeInTheDocument();
    expect(screen.getByText(/Score:/)).toBeInTheDocument();
  });
});
