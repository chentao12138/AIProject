/**
 * QuestionsPage — list rendering and filter behavior.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionsPage } from './QuestionsPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('QuestionsPage', () => {
  it('renders an empty state when there are no questions', async () => {
    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({
        listQuestions: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/questions'],
      routePath: '/spaces/:spaceId/questions',
    });

    expect(await screen.findByText('No questions yet')).toBeInTheDocument();
  });

  it('renders a question list and links to detail', async () => {
    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({
        listQuestions: vi.fn(async () => ({
          data: [
            { id: 1, questionType: 'SINGLE_CHOICE', stem: 'What is 1+1?', status: 'PUBLISHED', difficulty: 'EASY' },
            { id: 2, questionType: 'BOOLEAN', stem: 'The sky is blue.', status: 'DRAFT', difficulty: 'EASY' },
          ],
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/questions'],
      routePath: '/spaces/:spaceId/questions',
    });

    expect(await screen.findByText('What is 1+1?')).toBeInTheDocument();
    expect(screen.getByText('The sky is blue.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: 'View' })).toHaveLength(2);
  });

  it('filters by status and question type via URL search params', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    }));

    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({ listQuestions }),
      initialEntries: ['/spaces/7/questions?status=PUBLISHED&questionType=SINGLE_CHOICE'],
      routePath: '/spaces/:spaceId/questions',
    });

    await waitFor(() => {
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'PUBLISHED',
        questionType: 'SINGLE_CHOICE',
        knowledgePointId: undefined,
      });
    });

    await user.selectOptions(await screen.findByLabelText('Status'), 'DRAFT');
    await waitFor(() => {
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'DRAFT',
        questionType: 'SINGLE_CHOICE',
        knowledgePointId: undefined,
      });
    });
  });
});
