import { type ReactNode } from 'react';
import { useAuth } from '../lib/auth-context';

/**
 * UI-level admin gate (UX only — the backend independently enforces
 * ROLE_ADMIN on every /api/v1/admin/** request, ADR-028).
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { user, isAdmin } = useAuth();
  if (!isAdmin) {
    return (
      <div className="error-state" role="alert">
        <p className="error-state__message">当前账号没有管理员权限。</p>
        <p className="error-state__detail">
          已登录用户：{user?.username ?? user?.subject ?? '未知'}（角色：
          {(user?.roles ?? []).join(', ') || '无'}）
        </p>
      </div>
    );
  }
  return <>{children}</>;
}
