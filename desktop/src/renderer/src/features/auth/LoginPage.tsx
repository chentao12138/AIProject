/**
 * LoginPage (FE-AUTH-001, ADR-026) — formal username/password login.
 *
 * On success the AuthProvider stores the access token in memory and the
 * rotated refresh token in the main-process safeStorage vault, then we
 * navigate to the originally requested route (or /spaces). The page is
 * rendered OUTSIDE AppShell (public route); an already-authenticated
 * visit redirects away.
 */

import { type FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../lib/auth-context';
import { AuthLoginError } from '../../lib/auth-session';
import { Button } from '../../components/Button';
import { Input } from '../../components/Input';

interface LocationFromState {
  from?: { pathname?: string };
}

export function LoginPage() {
  const { status, login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  // Already authenticated -> leave the login screen.
  useEffect(() => {
    if (status === 'authenticated') {
      const from = (location.state as LocationFromState | null)?.from?.pathname;
      navigate(from ?? '/spaces', { replace: true });
    }
  }, [status, navigate, location.state]);

  const trimmedName = username.trim();
  const canSubmit =
    !submitting && trimmedName.length > 0 && password.length > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }
    setSubmitting(true);
    setErrorMessage(null);
    try {
      await login(trimmedName, password);
      const from = (location.state as LocationFromState | null)?.from?.pathname;
      navigate(from ?? '/spaces', { replace: true });
    } catch (error) {
      setErrorMessage(
        error instanceof AuthLoginError
          ? error.message
          : '登录失败，请稍后重试。'
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page" aria-labelledby="login-title">
      {/* Decorative background layers: no content, no focus targets. */}
      <div className="login-bg" aria-hidden="true">
        <span className="login-bg__blob login-bg__blob--a" />
        <span className="login-bg__blob login-bg__blob--b" />
        <span className="login-bg__blob login-bg__blob--c" />
        <span className="login-bg__blob login-bg__blob--d" />
        <span className="login-bg__grid" />
      </div>

      <div className="login-shell">
        <section className="login-brand">
          <div className="login-brand__mark" aria-hidden="true">
            AI
          </div>
          <p className="login-brand__name">AIStudy</p>
          <p className="login-brand__tagline">
            把资料、知识点、题目与练习收进同一个学习空间。
          </p>
          <ul className="login-brand__list">
            <li>上传的资料自动解析为页面与内容块</li>
            <li>知识点、题库、练习、考试共享同一空间作用域</li>
            <li>AI 辅导基于你自己的资料作答</li>
          </ul>
        </section>

        <form className="login-card" onSubmit={handleSubmit}>
          <header className="login-card__header">
            <h1 id="login-title" className="login-card__title">
              登录 AIStudy
            </h1>
            <p className="login-card__subtitle">使用后端账号继续</p>
          </header>

          {errorMessage && (
            <div className="login-alert" role="alert">
              <span className="login-alert__dot" aria-hidden="true" />
              <span>{errorMessage}</span>
            </div>
          )}

          <div className="login-card__fields">
            <Input
              label="用户名"
              name="username"
              autoComplete="username"
              autoFocus
              value={username}
              onChange={(event) => {
                setUsername(event.target.value);
                setErrorMessage(null);
              }}
            />
            <Input
              label="密码"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => {
                setPassword(event.target.value);
                setErrorMessage(null);
              }}
            />
          </div>

          <Button
            variant="primary"
            type="submit"
            disabled={!canSubmit}
            className="login-card__submit"
          >
            {submitting ? '登录中…' : '登录'}
          </Button>

          <p className="login-card__note">
            访问令牌只保存在内存中，刷新令牌交由系统加密存储；退出或卸载后即清除。
          </p>
        </form>
      </div>
    </main>
  );
}
