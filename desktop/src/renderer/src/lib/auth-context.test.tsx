/**
 * AuthProvider cache-boundary tests (ADR-026).
 *
 * The invariant README/README-docs promise is that a previous principal's
 * server state can never outlive their session — including the case where
 * nobody pressed "logout" (refresh-family rejection mid-flight).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, screen } from '@testing-library/react';
import { useAuth } from './auth-context';
import { authSession } from './api-client';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../test/test-utils';

function StatusProbe() {
  const { status } = useAuth();
  return <div>auth-{status}</div>;
}

describe('AuthProvider cache boundary', () => {
  afterEach(async () => {
    delete window.aistudyAuthVault;
  });

  it('drops every cached query when the session is silently revoked', async () => {
    window.aistudyAuthVault = {
      save: vi.fn(async () => ({ ok: true as const })),
      load: vi.fn(async () => null),
      clear: vi.fn(async () => ({ ok: true as const })),
    };
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(['spaces', 1], [{ id: 1, name: 'stale' }]);

    renderWithProviders(<StatusProbe />, {
      apiClient: mockApiClient(),
      queryClient,
      authInitialStatus: 'authenticated',
      authAutoBootstrap: false,
    });
    expect(screen.getByText('auth-authenticated')).toBeInTheDocument();

    // No logout() call: the session singleton just gave up.
    await act(async () => {
      await authSession.resetLocal();
    });

    expect(screen.getByText('auth-anonymous')).toBeInTheDocument();
    expect(queryClient.getQueryData(['spaces', 1])).toBeUndefined();
  });
});
