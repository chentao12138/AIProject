/**
 * RequireAuth (FE-AUTH-001) — route guard for every authenticated page.
 *
 *  - bootstrapping (exchanging the encrypted refresh token): a neutral
 *    loading state; business queries never run before auth settles.
 *  - restore-unavailable: a refresh token is held but the backend could not
 *    confirm it. Offer retry (the token survives) or re-login; redirecting
 *    to /login here would throw away a recoverable session.
 *  - anonymous: redirect to /login, REMEMBERING where the user was going
 *    (location state `from`), so login returns them there.
 *  - authenticated: render the nested routes.
 */

import { type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/auth-context';
import { NETWORK_MESSAGE } from '../lib/api-error';
import { Button } from '../components/Button';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status, retryRestore, logout } = useAuth();
  const location = useLocation();

  if (status === 'bootstrapping') {
    return <LoadingState text="正在恢复登录状态…" />;
  }

  if (status === 'restore-unavailable') {
    return (
      <ErrorState message={NETWORK_MESSAGE} onRetry={() => void retryRestore()}>
        <Button variant="ghost" onClick={() => void logout()}>
          用其他账号登录
        </Button>
      </ErrorState>
    );
  }

  if (status === 'anonymous') {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: { pathname: location.pathname } }}
      />
    );
  }

  return <>{children}</>;
}
