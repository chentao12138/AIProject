import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import App from './App';
import { ApiClientProvider } from './lib/api-context';
import { AuthProvider } from './lib/auth-context';
import { apiClient } from './lib/api-client';
import './styles/global.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 401/403/404 are deterministic; network/5xx bounded retry.
      retry: (failureCount, error) => {
        if (failureCount >= 2) return false;
        const status = (error as { status?: number })?.status;
        if (status === 401 || status === 403 || status === 404) return false;
        return true;
      },
      staleTime: 10_000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});

const container = document.getElementById('root');
if (!container) {
  throw new Error('Root container #root not found');
}

createRoot(container).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider client={apiClient}>
        <ApiClientProvider client={apiClient}>
          <App />
        </ApiClientProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>
);
