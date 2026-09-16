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
      <form className="card login-card" onSubmit={handleSubmit}>
        <h1 id="login-title" className="page__title">
          登录 AIStudy
        </h1>
        <p className="login-card__hint">
          使用后端账号登录。访问令牌仅保存在内存中，刷新令牌由系统加密存储。
        </p>

        {errorMessage && (
          <div className="form-error" role="alert">
            {errorMessage}
          </div>
        )}

        <Input
          label="用户名"
          name="username"
          autoComplete="username"
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

        <Button
          variant="primary"
          type="submit"
          disabled={!canSubmit}
          className="login-card__submit"
        >
          {submitting ? '登录中…' : '登录'}
        </Button>
      </form>
    </main>
  );
}
