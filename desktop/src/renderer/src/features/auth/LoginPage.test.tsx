/**
 * LoginPage tests (FE-AUTH-001).
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes } from 'react-router-dom';
import { LoginPage } from './LoginPage';
import { authSession } from '../../lib/api-client';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import type { ApiClient } from '@aistudy/api-client';

const PAIR = {
  accessToken: 'access-1',
  tokenType: 'Bearer',
  expiresIn: 300,
  refreshToken: 'refresh-1',
  refreshExpiresIn: 86400,
};

function renderLogin(client: ApiClient) {
  return renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/spaces" element={<div>spaces-landed</div>} />
    </Routes>,
    { initialEntries: ['/login'], apiClient: client }
  );
}

describe('LoginPage', () => {
  // The app-wide authSession singleton must start anonymous for every
  // test (a previous successful login must not leak across cases).
  beforeEach(async () => {
    await authSession.resetLocal();
  });

  it('disables submit until username and password are both present', async () => {
    renderLogin(mockApiClient());
    expect(screen.getByRole('button', { name: '登录' })).toBeDisabled();

    await userEvent.setup().type(screen.getByLabelText('用户名'), 'alice');
    expect(screen.getByRole('button', { name: '登录' })).toBeDisabled();
    await userEvent.setup().type(screen.getByLabelText('密码'), 'secret');
    expect(screen.getByRole('button', { name: '登录' })).toBeEnabled();
  });

  it('logs in with a trimmed username and navigates to /spaces', async () => {
    const user = userEvent.setup();
    const login = vi.fn(async () => ({
      data: PAIR,
      response: { status: 200 },
    }));
    const getCurrentUser = vi.fn(async () => ({
      data: { subject: 'u1', username: 'alice', roles: [] },
      response: { status: 200 },
    }));
    const client = mockApiClient({ login, getCurrentUser });
    renderLogin(client);

    await user.type(screen.getByLabelText('用户名'), '  alice  ');
    await user.type(screen.getByLabelText('密码'), 'secret');
    await user.click(screen.getByRole('button', { name: '登录' }));

    await waitFor(() =>
      expect(login).toHaveBeenCalledWith({
        username: 'alice',
        password: 'secret',
      })
    );
    expect(await screen.findByText('spaces-landed')).toBeInTheDocument();
  });

  it('shows the credential error and stays on /login after 401', async () => {
    const user = userEvent.setup();
    const login = vi.fn(async () => ({
      data: { accessToken: null, refreshToken: null },
      error: {},
      response: { status: 401 },
    }));
    renderLogin(mockApiClient({ login }));

    await user.type(screen.getByLabelText('用户名'), 'alice');
    await user.type(screen.getByLabelText('密码'), 'wrong');
    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '用户名或密码错误。'
    );
    expect(screen.queryByText('spaces-landed')).not.toBeInTheDocument();
  });

  it('masks the password and never prints it into the document', async () => {
    const user = userEvent.setup();
    renderLogin(mockApiClient());
    const password = screen.getByLabelText('密码');
    expect(password).toHaveAttribute('type', 'password');
    await user.type(password, 'super-secret');
    expect(document.body.textContent).not.toContain('super-secret');
  });
});
