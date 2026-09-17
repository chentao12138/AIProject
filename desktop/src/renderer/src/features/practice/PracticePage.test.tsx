/**
 * PracticePage — start form and history rendering.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { PracticePage } from './PracticePage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('PracticePage', () => {
  it('renders an empty history state when there are no sessions', async () => {
    renderWithProviders(<PracticePage />, {
      apiClient: mockApiClient({
        listPracticeSessions: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        listKnowledgePoints: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/practice'],
      routePath: '/spaces/:spaceId/practice',
    });

    expect(await screen.findByText('No practice sessions yet.')).toBeInTheDocument();
  });

  it('starts a practice session and navigates to the session page', async () => {
    const createPracticeSession = vi.fn(async () => ({
      data: { id: 101, status: 'IN_PROGRESS', questionCount: 5 },
      response: { status: 201 },
    }));

    renderWithProviders(<PracticePage />, {
      apiClient: mockApiClient({
        listPracticeSessions: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        listKnowledgePoints: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        createPracticeSession,
      }),
      initialEntries: ['/spaces/7/practice'],
      routePath: '/spaces/:spaceId/practice',
    });

    const countInput = await screen.findByLabelText('Number of Questions');
    fireEvent.change(countInput, { target: { value: '5' } });
    fireEvent.submit(countInput.closest('form')!);

    await waitFor(() => {
      expect(createPracticeSession).toHaveBeenCalledWith(7, { count: 5 });
    });
  });
});
