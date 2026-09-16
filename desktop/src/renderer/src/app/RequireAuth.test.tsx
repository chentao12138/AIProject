/**
 * RequireAuth tests (FE-AUTH-001): bootstrapping -> anonymous redirect
 * to /login (remembering the target) -> authenticated outlet.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { RequireAuth } from './RequireAuth';
import { authSession } from '../lib/api-client';
import { mockApiClient, renderWithProviders } from '../test/test-utils';

function renderGuarded(initialEntry: string, client = mockApiClient()) {
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
    { initialEntries: [initialEntry], apiClient: client }
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
    renderGuarded('/protected');
    expect(await screen.findByText('login-screen')).toBeInTheDocument();
    expect(screen.queryByText('protected-content')).not.toBeInTheDocument();
  });

  it('renders protected content once bootstrap restores a session', async () => {
    window.aistudyAuthVault = {
      save: vi.fn(async () => ({ ok: true as const })),
      load: vi.fn(async () => 'stored-refresh'),
      clear: vi.fn(async () => ({ ok: true as const })),
    };
    const client = mockApiClient({
      refresh: vi.fn(async () => ({
        data: {
          accessToken: 'access-1',
          refreshToken: 'refresh-1',
        },
        response: { status: 200 },
      })),
      getCurrentUser: vi.fn(async () => ({
        data: { subject: 'u1', username: 'alice', roles: [] },
        response: { status: 200 },
      })),
    });

    renderWithProviders(
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
        authInitialStatus: 'bootstrapping',
        authAutoBootstrap: true,
      }
    );
    await waitFor(() =>
      expect(screen.getByText('protected-content')).toBeInTheDocument()
    );
  });
});
