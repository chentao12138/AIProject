import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { UsersPage } from './UsersPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const PAGE = {
  items: [
    {
      id: 1,
      subject: 'subj-1',
      username: 'alice',
      status: 'ACTIVE',
      roles: ['ROLE_USER'],
      createdAt: '2026-01-01T00:00:00Z',
    },
    {
      id: 2,
      subject: 'subj-2',
      username: 'boss',
      status: 'DISABLED',
      roles: ['ROLE_USER', 'ROLE_ADMIN'],
      createdAt: '2026-01-02T00:00:00Z',
    },
  ],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
};

describe('UsersPage', () => {
  it('renders the paged user list', async () => {
    const adminListUsers = vi.fn(async () => ({
      data: PAGE,
      response: { status: 200 },
    }));
    renderWithProviders(<UsersPage />, {
      apiClient: mockApiClient({ adminListUsers }),
    });

    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getByText('boss')).toBeInTheDocument();
    expect(screen.getAllByText('ACTIVE').length).toBeGreaterThan(0);
    expect(screen.getByText(/共 2 人/)).toBeInTheDocument();
  });

  it('loads detail, edits roles and updates status', async () => {
    const user = userEvent.setup();
    const adminUpdateRoles = vi.fn(async () => ({
      data: {},
      response: { status: 200 },
    }));
    const adminUpdateStatus = vi.fn(async () => ({
      data: {},
      response: { status: 200 },
    }));
    const adminGetUser = vi.fn(async () => ({
      data: {
        id: 1,
        subject: 'subj-1',
        username: 'alice',
        status: 'ACTIVE',
        roles: ['ROLE_USER'],
        createdAt: '',
        updatedAt: '',
      },
      response: { status: 200 },
    }));
    renderWithProviders(<UsersPage />, {
      apiClient: mockApiClient({
        adminListUsers: vi.fn(async () => ({
          data: PAGE,
          response: { status: 200 },
        })),
        adminGetUser,
        adminUpdateRoles,
        adminUpdateStatus,
      }),
    });

    await screen.findByText('alice');
    await user.click(screen.getAllByRole('button', { name: '管理' })[0]);
    expect(await screen.findByText(/subject: subj-1/)).toBeInTheDocument();

    // Add ROLE_ADMIN to the draft and save.
    const draft = screen.getByDisplayValue('ROLE_USER');
    await user.clear(draft);
    await user.type(draft, 'ROLE_USER, ROLE_ADMIN');
    await user.click(screen.getByRole('button', { name: '保存角色' }));
    await waitFor(() =>
      expect(adminUpdateRoles).toHaveBeenCalledWith('subj-1', {
        roles: ['ROLE_USER', 'ROLE_ADMIN'],
      })
    );

    // ACTIVE user -> the toggle offers "停用账号".
    await user.click(screen.getByRole('button', { name: '停用账号' }));
    await waitFor(() =>
      expect(adminUpdateStatus).toHaveBeenCalledWith('subj-1', {
        status: 'DISABLED',
      })
    );
  });
});
