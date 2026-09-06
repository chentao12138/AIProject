/**
 * K1.7 — Source page: Desktop createSource payload must carry
 * sourceType = DESKTOP_UPLOAD (no admin sourceType dropdown).
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SourcesPage } from './SourcesPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('SourcesPage', () => {
  it('submits createSource with sourceType DESKTOP_UPLOAD (K1.7)', async () => {
    const user = userEvent.setup();
    const listSources = vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    }));
    const createSource = vi.fn(async () => ({
      data: {
        id: 1,
        title: 'Notes',
        sourceType: 'DESKTOP_UPLOAD',
        status: 'PROCESSING',
      },
      response: { status: 201 },
    }));

    const apiClient = mockApiClient({
      listSources,
      createSource,
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await user.click(
      await screen.findByRole('button', { name: 'Create Source' })
    );
    await user.type(await screen.findByLabelText('Title'), 'Chapter notes');
    await user.click(screen.getByRole('button', { name: 'Create' }));

    await waitFor(() => {
      expect(createSource).toHaveBeenCalledWith(7, {
        title: 'Chapter notes',
        sourceType: 'DESKTOP_UPLOAD',
      });
    });
  });

  it('does not expose an admin sourceType dropdown to the user', async () => {
    const apiClient = mockApiClient({
      listSources: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<SourcesPage />, {
      apiClient,
      initialEntries: ['/spaces/7/sources'],
      routePath: '/spaces/:spaceId/sources',
    });

    await userEvent.setup().click(
      await screen.findByRole('button', { name: 'Create Source' })
    );

    // The dialog must NOT offer a source type picker.
    expect(screen.queryByLabelText(/source type/i)).not.toBeInTheDocument();
    expect(
      screen.getByText(/Source type is fixed to DESKTOP_UPLOAD/)
    ).toBeInTheDocument();
  });
});
