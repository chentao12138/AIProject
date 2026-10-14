import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useAuth } from '../lib/auth-context';

export function AppShell() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);

  async function handleLogout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await logout();
      navigate('/login', { replace: true });
    } finally {
      setLoggingOut(false);
    }
  }

  return (
    <div className="app-shell">
      <header className="app-shell__header">
        <div className="app-shell__brand">AIStudy Admin</div>
        <div className="app-shell__auth">
          <span className="user-menu__name">
            {user?.username ?? user?.subject ?? '已登录'}
          </span>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={handleLogout}
            disabled={loggingOut}
          >
            {loggingOut ? '退出中…' : '退出登录'}
          </button>
        </div>
      </header>
      <div className="app-shell__body">
        <nav className="app-shell__nav" aria-label="Admin navigation">
          <NavLink
            to="/users"
            className={({ isActive }) =>
              isActive ? 'nav-link nav-link--active' : 'nav-link'
            }
          >
            用户管理
          </NavLink>
          <NavLink
            to="/operations"
            className={({ isActive }) =>
              isActive ? 'nav-link nav-link--active' : 'nav-link'
            }
          >
            存储运维
          </NavLink>
        </nav>
        <main className="app-shell__main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
