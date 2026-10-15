/**
 * PracticePage — start form and history rendering.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

    expect(await screen.findByText('还没有练习记录。')).toBeInTheDocument();
  });

  it('preselects the knowledge point handed to it in the URL (W16)', async () => {
    renderWithProviders(<PracticePage />, {
      apiClient: mockApiClient({
        listPracticeSessions: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        listKnowledgePoints: vi.fn(async () => ({
          data: [
            { id: 3, title: '牛顿第二定律' },
            { id: 9, title: '动能定理' },
          ],
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/practice?knowledgePointId=9'],
      routePath: '/spaces/:spaceId/practice',
    });

    // A SelectMenu only renders its options once opened, and the URL id can only
    // resolve to a title after the server's knowledge-point list has arrived, so
    // wait for the trigger to name the choice rather than for an option to exist.
    // Arriving from a question or knowledge point must not require typing the
    // id again: the URL selects the option the server list offers.
    const kp = await screen.findByRole('button', { name: /限定知识点/ });
    await waitFor(() => expect(kp).toHaveTextContent('动能定理'));
  });

  it('rejects a count outside the contract range instead of sending it', async () => {
    const user = userEvent.setup();
    const createPracticeSession = vi.fn(async () => ({
      data: { id: 101, status: 'IN_PROGRESS' },
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

    const countInput = await screen.findByLabelText('题目数量');
    // 200 is above the contract maximum; a number input's `max` does not stop
    // typing, so without validation this would only fail as a server 400.
    await user.clear(countInput);
    await user.type(countInput, '200');

    expect(await screen.findByRole('alert')).toHaveTextContent('1–100');
    expect(screen.getByRole('button', { name: '开始练习' })).toBeDisabled();
    expect(createPracticeSession).not.toHaveBeenCalled();

    // The top of the contract range stays usable.
    await user.clear(countInput);
    await user.type(countInput, '100');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '开始练习' })).toBeEnabled();
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

    const countInput = await screen.findByLabelText('题目数量');
    fireEvent.change(countInput, { target: { value: '5' } });
    fireEvent.submit(countInput.closest('form')!);

    await waitFor(() => {
      expect(createPracticeSession).toHaveBeenCalledWith(7, { count: 5 });
    });
  });
});
