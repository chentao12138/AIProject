/**
 * RequireAuth (FE-AUTH-001) — route guard for every authenticated page.
 *
 *  - bootstrapping (exchanging the encrypted refresh token): a neutral
 *    loading state; business queries never run before auth settles.
 *  - anonymous: redirect to /login, REMEMBERING where the user was going
 *    (location state `from`), so login returns them there.
 *  - authenticated: render the nested routes.
 */

import { type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../lib/auth-context';
import { LoadingState } from '../components/LoadingState';

export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === 'bootstrapping') {
    return <LoadingState text="正在恢复登录状态…" />;
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
