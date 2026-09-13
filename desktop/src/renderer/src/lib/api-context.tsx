/**
 * ApiClientContext — dependency injection for the shared API client
 * (FE-001 PHASE K2).
 *
 * Production: real createApiClient instance (lib/api-client.ts).
 * Tests: typed fake ApiClient.
 *
 * Page components must never create their own client.
 */

import { createContext, useContext } from 'react';
import type { ReactNode } from 'react';
import type { ApiClient } from '@aistudy/api-client';

const ApiClientContext = createContext<ApiClient | null>(null);

export function ApiClientProvider({
  client,
  children,
}: {
  client: ApiClient;
  children: ReactNode;
}) {
  return (
    <ApiClientContext.Provider value={client}>{children}</ApiClientContext.Provider>
  );
}

export function useApiClient(): ApiClient {
  const client = useContext(ApiClientContext);
  if (client === null) {
    throw new Error('useApiClient must be used inside <ApiClientProvider>');
  }
  return client;
}
