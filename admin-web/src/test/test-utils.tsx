import { render } from '@testing-library/react';
import { vi } from 'vitest';
import type { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { ApiClientProvider } from '../lib/api-context';
import { AuthProvider } from '../lib/auth-context';

export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
}

const noData = { data: undefined, response: { status: 200 } };

export function createMockApiClient(): ApiClient {
  return {
    login: vi.fn(async () => noData),
    refresh: vi.fn(async () => noData),
    logout: vi.fn(async () => noData),
    getCurrentUser: vi.fn(async () => noData),
    adminListUsers: vi.fn(async () => noData),
    adminGetUser: vi.fn(async () => noData),
    adminUpdateRoles: vi.fn(async () => noData),
    adminUpdateStatus: vi.fn(async () => noData),
    adminReconcileStorage: vi.fn(async () => noData),
  } as unknown as ApiClient;
}

type ApiClientMethod = (...args: never[]) => unknown;

export function mockApiClient(
  overrides: Partial<Record<keyof ApiClient, ApiClientMethod>> = {}
): ApiClient {
  return { ...createMockApiClient(), ...overrides } as unknown as ApiClient;
}

interface RenderOptions {
  apiClient?: ApiClient;
  queryClient?: QueryClient;
  initialEntries?: string[];
}

export function renderWithProviders(
  ui: ReactElement,
  {
    apiClient = createMockApiClient(),
    queryClient = createTestQueryClient(),
    initialEntries = ['/'],
  }: RenderOptions = {}
) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <AuthProvider client={apiClient}>
          <ApiClientProvider client={apiClient}>
            <MemoryRouter initialEntries={initialEntries}>
              {children}
            </MemoryRouter>
          </ApiClientProvider>
        </AuthProvider>
      </QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}
