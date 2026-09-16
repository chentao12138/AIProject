/**
 * Minimal DI context so components receive a typed ApiClient (tests
 * inject a mock; production uses the singleton).
 */

import { createContext, useContext, type ReactNode } from 'react';
import type { ApiClient } from '@aistudy/api-client';
import { apiClient } from './api-client';

const ApiClientContext = createContext<ApiClient>(apiClient);

export function ApiClientProvider({
  client,
  children,
}: {
  client: ApiClient;
  children: ReactNode;
}) {
  return (
    <ApiClientContext.Provider value={client}>
      {children}
    </ApiClientContext.Provider>
  );
}

export function useApiClient(): ApiClient {
  return useContext(ApiClientContext);
}
