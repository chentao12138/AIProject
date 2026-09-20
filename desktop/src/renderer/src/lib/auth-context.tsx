/**
 * AuthProvider (FE-AUTH-001) — React binding for the framework-free
 * AuthSession singleton.
 *
 * Lifecycle:
 *  - 'bootstrapping': exchanging the encrypted refresh token persisted
 *    by the OS vault (app start). The UI shows a neutral loading screen;
 *    no business query runs before this settles.
 *  - 'anonymous': no session -> RequireAuth redirects to /login.
 *  - 'restore-unavailable': a refresh token is held but the backend could
 *    not confirm it -> RequireAuth offers retry / re-login instead of
 *    silently discarding the session.
 *  - 'authenticated': access token in memory + current user loaded.
 *
 * Cache boundary (same rule the old DevelopmentSession established):
 * whenever the principal CHANGES — login, logout, or an unrecoverable
 * refresh-family rejection — ALL TanStack Query server state is cleared,
 * so a previous principal's data can never survive under a new identity.
 * The rule is enforced on every transition out of 'authenticated', so a
 * silent session loss (rotateRefresh rejection) cannot leave data behind.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import { authSession, getApiClient } from './api-client';
import { AuthLoginError, type AuthUser } from './auth-session';

export type AuthStatus =
  | 'bootstrapping'
  | 'anonymous'
  | 'restore-unavailable'
  | 'authenticated';

interface AuthContextValue {
  status: AuthStatus;
  user: AuthUser | null;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  /** Re-run the restore exchange (offline panel retry) without re-logging in. */
  retryRestore: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  client = getApiClient(),
  initialStatus = 'bootstrapping',
  autoBootstrap = true,
}: {
  children: ReactNode;
  /** Injectable for tests; defaults to the app-wide singleton. */
  client?: ApiClient;
  /** Starting status (tests inject 'anonymous'; production bootstraps). */
  initialStatus?: AuthStatus;
  /** Restore the persisted session on mount (tests may disable). */
  autoBootstrap?: boolean;
}) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<AuthStatus>(initialStatus);
  const [user, setUser] = useState<AuthUser | null>(null);
  const startedRef = useRef(false);
  const statusRef = useRef<AuthStatus>(initialStatus);

  const syncFromSession = useCallback(() => {
    const snapshot = authSession.snapshot();
    const next: AuthStatus = snapshot.authenticated
      ? 'authenticated'
      : snapshot.restoreUnavailable
        ? 'restore-unavailable'
        : 'anonymous';
    // Every transition out of an authenticated principal is the cache
    // boundary — including a refresh-family rejection nobody "logged out".
    if (statusRef.current === 'authenticated' && next !== 'authenticated') {
      queryClient.clear();
    }
    statusRef.current = next;
    setStatus(next);
    setUser(snapshot.user);
  }, [queryClient]);

  // Subscribe FIRST, then bootstrap, so transitions made during the
  // restore can never be missed.
  useEffect(() => {
    const unsubscribe = authSession.subscribe(syncFromSession);
    return unsubscribe;
  }, [syncFromSession]);

  useEffect(() => {
    if (!autoBootstrap || startedRef.current) {
      return;
    }
    startedRef.current = true;
    let cancelled = false;
    void (async () => {
      await authSession.bootstrap(client);
      if (!cancelled) {
        syncFromSession();
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [autoBootstrap, client, syncFromSession]);

  const login = useCallback(
    async (username: string, password: string) => {
      await authSession.login(client, username, password);
      // New principal: discard every cached server response.
      queryClient.clear();
      syncFromSession();
    },
    [client, queryClient, syncFromSession]
  );

  const logout = useCallback(async () => {
    // syncFromSession drops the query cache: logout leaves 'authenticated'.
    await authSession.logout(client);
    syncFromSession();
  }, [client, syncFromSession]);

  const retryRestore = useCallback(async () => {
    statusRef.current = 'bootstrapping';
    setStatus('bootstrapping');
    await authSession.bootstrap(client);
    syncFromSession();
  }, [client, syncFromSession]);

  const value = useMemo<AuthContextValue>(
    () => ({ status, user, login, logout, retryRestore }),
    [status, user, login, logout, retryRestore]
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
