/**
 * RequireAuth tests (FE-AUTH-001): bootstrapping -> anonymous redirect
 * to /login (remembering the target) -> authenticated outlet, plus the
 * recoverable branch where the backend cannot confirm a held refresh token.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { RequireAuth } from './RequireAuth';
import { authSession } from '../lib/api-client';
import { NETWORK_MESSAGE } from '../lib/api-error';
import type { AuthStatus } from '../lib/auth-context';
import { mockApiClient, renderWithProviders } from '../test/test-utils';
import { act } from 'react';

function vaultWith(stored: string | null) {
  return {
    save: vi.fn(async () => ({ ok: true as const })),
    load: vi.fn(async () => stored),
    clear: vi.fn(async () => ({ ok: true as const })),
  };
}

function renderGuarded(
  client: ApiClient,
  auth: { initialStatus: AuthStatus; autoBootstrap: boolean }
) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/protected"
        element={
          <RequireAuth>
            <div>protected-content</div>
          </RequireAuth>
        }
      />
      <Route path="/login" element={<div>login-screen</div>} />
    </Routes>,
    {
      initialEntries: ['/protected'],
      apiClient: client,
      authInitialStatus: auth.initialStatus,
      authAutoBootstrap: auth.autoBootstrap,
    }
  );
}

describe('RequireAuth', () => {
  afterEach(async () => {
    delete window.aistudyAuthVault;
    await act(async () => {
      await authSession.resetLocal();
    });
  });

  it('redirects an anonymous session to /login', async () => {
    renderGuarded(mockApiClient(), {
      initialStatus: 'anonymous',
      autoBootstrap: false,
    });
    expect(await screen.findByText('login-screen')).toBeInTheDocument();
    expect(screen.queryByText('protected-content')).not.toBeInTheDocument();
  });

  it('renders protected content once bootstrap restores a session', async () => {
    window.aistudyAuthVault = vaultWith('stored-refresh');
    const client = mockApiClient({
      refresh: vi.fn(async () => ({
        data: { accessToken: 'access-1', refreshToken: 'refresh-1' },
        response: { status: 200 },
      })),
      getCurrentUser: vi.fn(async () => ({
        data: { subject: 'u1', username: 'alice', roles: [] },
        response: { status: 200 },
      })),
    });

    renderGuarded(client, {
      initialStatus: 'bootstrapping',
      autoBootstrap: true,
    });
    await waitFor(() =>
      expect(screen.getByText('protected-content')).toBeInTheDocument()
    );
  });

  it('keeps a recoverable session while the backend is down and recovers on retry', async () => {
    const vault = vaultWith('stored-refresh');
    window.aistudyAuthVault = vault;
    const client = mockApiClient({
      refresh: vi
        .fn()
        .mockResolvedValueOnce({
          data: undefined,
          error: {},
          response: { status: 503 },
        })
        .mockResolvedValueOnce({
          data: { accessToken: 'access-1', refreshToken: 'refresh-1' },
          response: { status: 200 },
        }),
      getCurrentUser: vi.fn(async () => ({
        data: { subject: 'u1', username: 'alice', roles: [] },
        response: { status: 200 },
      })),
    });

    renderGuarded(client, {
      initialStatus: 'bootstrapping',
      autoBootstrap: true,
    });

    // A 5xx is not a dead session: no forced re-login, no vault wipe.
    expect(await screen.findByText(NETWORK_MESSAGE)).toBeInTheDocument();
    expect(screen.queryByText('login-screen')).not.toBeInTheDocument();
    expect(vault.clear).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    await waitFor(() =>
      expect(screen.getByText('protected-content')).toBeInTheDocument()
    );
  });
});
