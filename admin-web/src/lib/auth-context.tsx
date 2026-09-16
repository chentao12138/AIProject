/**
 * AuthProvider for Admin Web. Unlike the Desktop there is no encrypted
 * vault to restore from (tokens are memory-only), so the initial status
 * is 'anonymous' and login/logout are the only transitions.
 */

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { apiClient, authSession } from './api-client';
import { AuthLoginError, type AuthUser } from './auth-session';

export type AuthStatus = 'anonymous' | 'authenticated';

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  isAdmin: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  client = apiClient,
}: {
  children: ReactNode;
  client?: ApiClient;
}) {
  const queryClient = useQueryClient();
  const [user, setUser] = useState<AuthUser | null>(null);

  const sync = useCallback(() => {
    setUser(authSession.getUser());
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      await authSession.login(client, username, password);
      queryClient.clear();
      sync();
    },
    [client, queryClient, sync]
  );

  const logout = useCallback(async () => {
    await authSession.logout(client);
    queryClient.clear();
    sync();
  }, [client, queryClient, sync]);

  const value = useMemo<AuthContextValue>(
    () => ({
      status: user ? 'authenticated' : 'anonymous',
      user,
      isAdmin: user?.roles?.includes('ROLE_ADMIN') ?? false,
      login,
      logout,
    }),
    [user, login, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within <AuthProvider>');
  }
  return context;
}

export { AuthLoginError };
