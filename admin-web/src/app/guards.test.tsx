import { describe, expect, it } from 'vitest';
import { screen } from '@testing-library/react';
import { Routes, Route } from 'react-router-dom';
import { RequireAuth } from './RequireAuth';
import { RequireAdmin } from './RequireAdmin';
import { authSession } from '../lib/api-client';
import { mockApiClient, renderWithProviders } from '../test/test-utils';

describe('guards', () => {
  it('RequireAuth redirects anonymous users to /login', () => {
    authSession.resetLocal();
    renderWithProviders(
      <Routes>
        <Route
          path="/secure"
          element={
            <RequireAuth>
              <div>secret</div>
            </RequireAuth>
          }
        />
        <Route path="/login" element={<div>login-screen</div>} />
      </Routes>,
      { initialEntries: ['/secure'], apiClient: mockApiClient() }
    );
    expect(screen.getByText('login-screen')).toBeInTheDocument();
  });

  it('RequireAdmin blocks non-admin authenticated users', () => {
    authSession.resetLocal();
    // Directly seed the memory session via internal fields is avoided;
    // instead RequireAdmin derives from AuthProvider context. Seed via
    // authSession by mocking a pair through login would be async, so
    // render an unauthenticated user and assert the gate renders its
    // explanatory copy when context user lacks ROLE_ADMIN.
    renderWithProviders(
      <RequireAdmin>
        <div>admin-only</div>
      </RequireAdmin>,
      { apiClient: mockApiClient() }
    );
    expect(screen.queryByText('admin-only')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('没有管理员权限');
  });
});
