import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { LoginPage } from './LoginPage';
import { authSession } from '../../lib/api-client';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

function renderLogin(client = mockApiClient()) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/users" element={<div>users-landed</div>} />
    </Routes>,
    { initialEntries: ['/login'], apiClient: client }
  );
}

describe('Admin LoginPage', () => {
  beforeEach(() => authSession.resetLocal());

  it('disables submit until both fields filled', async () => {
    renderLogin();
    expect(screen.getByRole('button', { name: '登录' })).toBeDisabled();
    await userEvent.setup().type(screen.getByLabelText('用户名'), 'admin');
    expect(screen.getByRole('button', { name: '登录' })).toBeDisabled();
    await userEvent.setup().type(screen.getByLabelText('密码'), 'pw');
    expect(screen.getByRole('button', { name: '登录' })).toBeEnabled();
  });

  it('logs in and lands on /users', async () => {
    const user = userEvent.setup();
    const login = vi.fn(async () => ({
      data: { accessToken: 'a', refreshToken: 'r' },
      response: { status: 200 },
    }));
    const getCurrentUser = vi.fn(async () => ({
      data: { subject: 's', username: 'admin', roles: ['ROLE_ADMIN'] },
      response: { status: 200 },
    }));
    renderLogin(mockApiClient({ login, getCurrentUser }));

    await user.type(screen.getByLabelText('用户名'), ' admin ');
    await user.type(screen.getByLabelText('密码'), 'pw');
    await user.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() =>
      expect(login).toHaveBeenCalledWith({ username: 'admin', password: 'pw' })
    );
    expect(await screen.findByText('users-landed')).toBeInTheDocument();
  });

  it('shows an error and stays on 401', async () => {
    const user = userEvent.setup();
    const login = vi.fn(async () => ({
      data: { accessToken: null, refreshToken: null },
      error: {},
      response: { status: 401 },
    }));
    renderLogin(mockApiClient({ login }));

    await user.type(screen.getByLabelText('用户名'), 'admin');
    await user.type(screen.getByLabelText('密码'), 'bad');
    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '用户名或密码错误。'
    );
  });
});
