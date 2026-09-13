import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { ApiClientProvider } from './lib/api-context';
import { apiClient } from './lib/api-client';
import { shouldRetryQuery } from './lib/query-retry';
import { ErrorBoundary } from './components/ErrorBoundary';
import './styles/global.css';

/**
 * Server-state policy (FE-001.5 PHASE 5):
 *  - retry: per-error-class (401/403/404 never; network/5xx bounded)
 *  - staleTime 10s: fresh-enough lists avoid mount refetch churn in the
 *    Electron window while still refreshing on meaningful navigation
 *  - refetchOnWindowFocus: false (desktop app — no background tab churn)
 *  - refetchOnReconnect: true (backend comes back -> lists recover)
 *  - mutations: never auto-replay unsafe writes
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryQuery,
      staleTime: 10_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      refetchOnReconnect: true,
    },
    mutations: {
      retry: false,
    },
  },
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found');
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <ApiClientProvider client={apiClient}>
        <ErrorBoundary>
          <App />
        </ErrorBoundary>
      </ApiClientProvider>
    </QueryClientProvider>
  </StrictMode>
);
