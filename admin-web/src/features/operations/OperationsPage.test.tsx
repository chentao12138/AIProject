import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { OperationsPage } from './OperationsPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('OperationsPage', () => {
  it('runs DRY_RUN and shows the result metrics', async () => {
    const user = userEvent.setup();
    const adminReconcileStorage = vi.fn(async () => ({
      data: {
        scanned: 10,
        referenced: 7,
        candidateOrphans: 3,
        deleted: 0,
        skippedRecent: 0,
        skippedUnsafe: 0,
        truncated: false,
        mode: 'DRY_RUN',
      },
      response: { status: 200 },
    }));
    renderWithProviders(<OperationsPage />, {
      apiClient: mockApiClient({ adminReconcileStorage }),
    });

    await user.click(
      screen.getByRole('button', { name: /DRY_RUN 扫描/ })
    );
    await waitFor(() =>
      expect(adminReconcileStorage).toHaveBeenCalledWith({ mode: 'DRY_RUN' })
    );
    expect(await screen.findByText(/模式：DRY_RUN/)).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('requires a second confirmation click for CLEANUP', async () => {
    const user = userEvent.setup();
    const adminReconcileStorage = vi.fn(async () => ({
      data: {
        scanned: 10,
        referenced: 7,
        candidateOrphans: 3,
        deleted: 3,
        skippedRecent: 0,
        skippedUnsafe: 0,
        truncated: false,
        mode: 'CLEANUP',
      },
      response: { status: 200 },
    }));
    renderWithProviders(<OperationsPage />, {
      apiClient: mockApiClient({ adminReconcileStorage }),
    });

    await user.click(screen.getByRole('button', { name: /CLEANUP 清理/ }));
    // First click only arms the confirmation; nothing is sent yet.
    expect(adminReconcileStorage).not.toHaveBeenCalled();
    expect(
      screen.getByRole('button', { name: /确认删除/ })
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /确认删除/ }));
    await waitFor(() =>
      expect(adminReconcileStorage).toHaveBeenCalledWith({ mode: 'CLEANUP' })
    );
  });
});
