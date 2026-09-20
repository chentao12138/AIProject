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
import { AuthProvider, type AuthStatus } from '../lib/auth-context';

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
    // FE-002A
    uploadSourceAsset: vi.fn(async () => noData),
    listSourceAssets: vi.fn(async () => noData),
    getSourceAsset: vi.fn(async () => noData),
    createIngestionJob: vi.fn(async () => noData),
    listIngestionJobs: vi.fn(async () => noData),
    getIngestionJob: vi.fn(async () => noData),
    retryIngestionJob: vi.fn(async () => noData),
    listSourcePages: vi.fn(async () => noData),
    listContentBlocks: vi.fn(async () => noData),
    addKnowledgePointSources: vi.fn(async () => noData),
    listKnowledgePointSources: vi.fn(async () => noData),
    createKnowledgeCategory: vi.fn(async () => noData),
    listKnowledgeCategories: vi.fn(async () => noData),
    getKnowledgeCategory: vi.fn(async () => noData),
    createKnowledgePoint: vi.fn(async () => noData),
    listKnowledgePoints: vi.fn(async () => noData),
    getKnowledgePoint: vi.fn(async () => noData),
    publishKnowledgePoint: vi.fn(async () => noData),
    // FE-AI-001
    getAiSettings: vi.fn(async () => noData),
    putAiSettings: vi.fn(async () => noData),
    testAiConnection: vi.fn(async () => noData),
    deleteAiApiKey: vi.fn(async () => noData),
    listConversations: vi.fn(async () => noData),
    createConversation: vi.fn(async () => noData),
    getConversation: vi.fn(async () => noData),
    sendMessage: vi.fn(async () => noData),
    archiveConversation: vi.fn(async () => noData),
    listMessages: vi.fn(async () => noData),
    // FE-ADMIN-001
    login: vi.fn(async () => noData),
    refresh: vi.fn(async () => noData),
    logout: vi.fn(async () => noData),
    getCurrentUser: vi.fn(async () => noData),
    // FE-ADMIN-001
    adminListUsers: vi.fn(async () => noData),
    adminGetUser: vi.fn(async () => noData),
    adminUpdateRoles: vi.fn(async () => noData),
    adminUpdateStatus: vi.fn(async () => noData),
    adminReconcileStorage: vi.fn(async () => noData),
    // Questions
    listQuestions: vi.fn(async () => noData),
    getQuestion: vi.fn(async () => noData),
    createQuestion: vi.fn(async () => noData),
    publishQuestion: vi.fn(async () => noData),
    // Practice
    listPracticeSessions: vi.fn(async () => noData),
    createPracticeSession: vi.fn(async () => noData),
    getPracticeSession: vi.fn(async () => noData),
    startPracticeSession: vi.fn(async () => noData),
    finishPracticeSession: vi.fn(async () => noData),
    submitPracticeAnswer: vi.fn(async () => noData),
    // Exams
    listExams: vi.fn(async () => noData),
    createExam: vi.fn(async () => noData),
    getExam: vi.fn(async () => noData),
    publishExam: vi.fn(async () => noData),
    createExamAttempt: vi.fn(async () => noData),
    startExamAttempt: vi.fn(async () => noData),
    getExamAttempt: vi.fn(async () => noData),
    submitExamAttempt: vi.fn(async () => noData),
    getExamResult: vi.fn(async () => noData),
    getExamDiagnosis: vi.fn(async () => noData),
    submitExamAnswer: vi.fn(async () => noData),
    // Search
    search: vi.fn(async () => noData),
    // Study Coach
    studyCoach: vi.fn(async () => noData),
    explainPracticeAnswer: vi.fn(async () => noData),
    explainExamAnswer: vi.fn(async () => noData),
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
  /** AuthProvider start status (defaults to anonymous; bootstrap-flow
   *  tests pass 'bootstrapping'). */
  authInitialStatus?: AuthStatus;
  /** Whether AuthProvider restores a persisted session on mount. */
  authAutoBootstrap?: boolean;
}

export function renderWithProviders(
  ui: ReactElement,
  {
    apiClient = createMockApiClient(),
    queryClient = createTestQueryClient(),
    initialEntries = ['/'],
    routePath,
    authInitialStatus = 'anonymous',
    authAutoBootstrap = false,
  }: RenderOptions = {}
) {
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <AuthProvider
          client={apiClient}
          initialStatus={authInitialStatus}
          autoBootstrap={authAutoBootstrap}
        >
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
        </AuthProvider>
      </QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper });
}
