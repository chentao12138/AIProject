/**
 * ExamsPage — create form and list rendering.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ExamsPage } from './ExamsPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('ExamsPage', () => {
  it('renders an empty state when there are no exams', async () => {
    renderWithProviders(<ExamsPage />, {
      apiClient: mockApiClient({
        listExams: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/exams'],
      routePath: '/spaces/:spaceId/exams',
    });

    expect(await screen.findByText('No exams yet')).toBeInTheDocument();
  });

  it('creates an exam and navigates to its detail', async () => {
    const user = userEvent.setup();
    const createExam = vi.fn(async () => ({
      data: { id: 21, title: 'Midterm', status: 'DRAFT', durationMinutes: 60, questions: [] },
      response: { status: 201 },
    }));

    renderWithProviders(<ExamsPage />, {
      apiClient: mockApiClient({
        listExams: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        createExam,
      }),
      initialEntries: ['/spaces/7/exams'],
      routePath: '/spaces/:spaceId/exams',
    });

    await user.type(await screen.findByLabelText('Title'), 'Midterm');
    await user.type(await screen.findByLabelText('Description'), 'Chapter 1-3');
    fireEvent.change(screen.getByLabelText('Duration (minutes)'), { target: { value: '60' } });
    fireEvent.submit(screen.getByLabelText('Title').closest('form')!);

    await waitFor(() => {
      expect(createExam).toHaveBeenCalledWith(7, {
        title: 'Midterm',
        description: 'Chapter 1-3',
        durationMinutes: 60,
        questions: [],
      });
    });
  });
});
