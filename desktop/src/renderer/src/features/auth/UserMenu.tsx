/**
 * UserMenu (FE-AUTH-001) — authenticated identity + logout in the shell
 * header. Replaces the temporary DevelopmentSession token-paste widget:
 * formal auth carries the user's name/roles from /auth/me, and logout
 * revokes the refresh session server-side (best-effort) before clearing
 * local state.
 */

import { useState } from 'react';
import { useAuth } from '../../lib/auth-context';
import { Button } from '../../components/Button';

export function UserMenu() {
  const { user, logout } = useAuth();
  const [loggingOut, setLoggingOut] = useState(false);

  const displayName = user?.username ?? user?.subject ?? '已登录';
  const rolesLabel = (user?.roles ?? []).join(', ');

  async function handleLogout() {
    if (loggingOut) {
      return;
    }
    setLoggingOut(true);
    try {
      await logout();
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <div className="user-menu">
      <div className="user-menu__identity" title={rolesLabel}>
        <span className="user-menu__name">{displayName}</span>
      </div>
      <Button variant="ghost" onClick={handleLogout} disabled={loggingOut}>
        {loggingOut ? '退出中…' : '退出登录'}
      </Button>
    </div>
  );
}
