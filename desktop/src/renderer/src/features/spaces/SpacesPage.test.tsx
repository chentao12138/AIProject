/**
 * K1.4–K1.6 + K1.12 — Spaces page behavior tests.
 *
 * - empty state
 * - displays returned spaces
 * - create LearningSpace: submit -> ApiClient method called -> success
 *   state / invalidate behavior
 * - 401 -> auth/session error UI
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SpacesPage } from './SpacesPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('SpacesPage', () => {
  it('shows empty state when there are no spaces (K1.4)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText('No learning spaces yet')
    ).toBeInTheDocument();
    // header button + empty-state CTA share the same label
    expect(
      screen.getAllByRole('button', { name: 'Create Learning Space' }).length
    ).toBeGreaterThanOrEqual(1);
  });

  it('displays returned spaces (K1.5)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [
          { id: 1, name: 'Math 101', description: 'Basics', status: 'ACTIVE' },
          { id: 2, name: 'Physics', status: 'ACTIVE' },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Math 101')).toBeInTheDocument();
    expect(screen.getByText('Physics')).toBeInTheDocument();
    expect(screen.getByText('Basics')).toBeInTheDocument();
  });

  it('create LearningSpace calls the ApiClient and invalidates the list (K1.6)', async () => {
    const user = userEvent.setup();
    const listLearningSpaces = vi
      .fn()
      .mockResolvedValueOnce({
        data: [],
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: [
          { id: 1, name: 'Math 101', description: 'Basics', status: 'ACTIVE' },
        ],
        response: { status: 200 },
      });
    const createLearningSpace = vi.fn(async () => ({
      data: { id: 1, name: 'Math 101', status: 'ACTIVE' },
      response: { status: 201 },
    }));

    const apiClient = mockApiClient({
      listLearningSpaces,
      createLearningSpace,
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const createButtons = await screen.findAllByRole('button', {
      name: 'Create Learning Space',
    });
    await user.click(createButtons[0]);

    const dialog = await screen.findByRole('dialog');
    expect(dialog).toBeInTheDocument();

    await user.type(screen.getByLabelText('Name'), 'Math 101');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(createLearningSpace).toHaveBeenCalledWith({
        name: 'Math 101',
        description: undefined,
      });
    });
    // invalidate -> list is refetched
    await waitFor(() => {
      expect(listLearningSpaces).toHaveBeenCalledTimes(2);
    });
  });

  it('shows auth/session error UI on 401 (K1.12)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: undefined,
        error: { message: 'unauthorized' },
        response: { status: 401 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(
      await screen.findByText(/认证已失效或未提供访问凭证/)
    ).toBeInTheDocument();
  });

  it('renders space cards as real links into the space (review #4)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: 'Math 101', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const link = await screen.findByRole('link', { name: /Math 101/ });
    expect(link.tagName).toBe('A');
    expect(link).toHaveAttribute('href', '/spaces/1/sources');
  });

  it('space card links are keyboard-focusable via Tab (review #4)', async () => {
    const user = userEvent.setup();
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: 1, name: 'Math 101', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    const link = await screen.findByRole('link', { name: /Math 101/ });
    // first Tab lands on the header CTA, second Tab on the space link
    await user.tab();
    await user.tab();
    expect(link).toHaveFocus();
  });

  it('space without a valid id renders non-navigable card (review #6)', async () => {
    const apiClient = mockApiClient({
      listLearningSpaces: vi.fn(async () => ({
        data: [{ id: undefined, name: 'Broken', status: 'ACTIVE' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SpacesPage />, { apiClient });

    expect(await screen.findByText('Broken')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Broken/ })).not.toBeInTheDocument();
  });
});
