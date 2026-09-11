/**
 * Shared test helpers (FE-001 PHASE K2).
 *
 * - createMockApiClient(): typed fake of the shared ApiClient — tests
 *   mock the client INTERFACE, never global fetch, never bypassing our
 *   API abstraction.
 * - renderWithProviders(): wraps UI in QueryClientProvider +
 *   ApiClientProvider + MemoryRouter; optional routePath renders the UI
 *   inside <Routes> so components using useParams/useNavigate work.
 */

import { render } from '@testing-library/react';
import { vi } from 'vitest';
import type { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { ApiClientProvider } from '../lib/api-context';

/**
 * Test QueryClient (FE-001.5 PHASE 22): fresh per test, retries off so
 * error-class behavior is deterministic, gcTime Infinity so observers
 * never disappear mid-assertion.
 */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
    },
  });
}

export function createMockApiClient(): ApiClient {
  const noData = { data: undefined, response: { status: 200 } };
  return {
    createLearningSpace: vi.fn(async () => noData),
    listLearningSpaces: vi.fn(async () => noData),
    getLearningSpace: vi.fn(async () => noData),
    createSource: vi.fn(async () => noData),
    listSources: vi.fn(async () => noData),
    getSource: vi.fn(async () => noData),
    createKnowledgeCategory: vi.fn(async () => noData),
    listKnowledgeCategories: vi.fn(async () => noData),
    getKnowledgeCategory: vi.fn(async () => noData),
    createKnowledgePoint: vi.fn(async () => noData),
    listKnowledgePoints: vi.fn(async () => noData),
    getKnowledgePoint: vi.fn(async () => noData),
    publishKnowledgePoint: vi.fn(async () => noData),
  } as unknown as ApiClient;
}

/**
 * Mock client with per-method overrides. Overrides are spread over the
 * full mock; cast to ApiClient keeps the fake type-compatible without
 * hand-rolling openapi-fetch response types in every test.
 */
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
  /** When set, the UI element is rendered under <Route path=...>. */
  routePath?: string;
}

export function renderWithProviders(
  ui: ReactElement,
  {
    apiClient = createMockApiClient(),
    queryClient = createTestQueryClient(),
    initialEntries = ['/'],
    routePath,
  }: RenderOptions = {}
) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ApiClientProvider client={apiClient}>
          <MemoryRouter initialEntries={initialEntries}>
            {routePath ? (
              <Routes>
                <Route path={routePath} element={children} />
              </Routes>
            ) : (
              children
            )}
          </MemoryRouter>
        </ApiClientProvider>
      </QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}
