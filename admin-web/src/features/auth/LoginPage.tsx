import { type FormEvent, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../../lib/auth-context';
import { AuthLoginError } from '../../lib/auth-session';

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

  useEffect(() => {
    if (status === 'authenticated') {
      const from = (location.state as LocationFromState | null)?.from?.pathname;
      navigate(from ?? '/users', { replace: true });
    }
  }, [status, navigate, location.state]);

  const trimmedName = username.trim();
  const canSubmit =
    !submitting && trimmedName.length > 0 && password.length > 0;

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setErrorMessage(null);
    try {
      await login(trimmedName, password);
      const from = (location.state as LocationFromState | null)?.from?.pathname;
      navigate(from ?? '/users', { replace: true });
    } catch (error) {
      setErrorMessage(
        error instanceof AuthLoginError ? error.message : '登录失败，请稍后重试。'
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="login-page" aria-labelledby="login-title">
      <form className="card login-card" onSubmit={handleSubmit}>
        <h1 id="login-title" className="page__title">
          AIStudy 管理后台
        </h1>
        <p className="login-card__hint">
          仅管理员可登录。令牌仅保存在当前标签页内存中，关闭标签页即退出。
        </p>
        {errorMessage && (
          <div className="form-error" role="alert">
            {errorMessage}
          </div>
        )}
        <label className="field">
          <span className="field__label">用户名</span>
          <input
            className="input"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(e) => {
              setUsername(e.target.value);
              setErrorMessage(null);
            }}
          />
        </label>
        <label className="field">
          <span className="field__label">密码</span>
          <input
            className="input"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
              setErrorMessage(null);
            }}
          />
        </label>
        <button
          type="submit"
          className="btn btn--primary login-card__submit"
          disabled={!canSubmit}
        >
          {submitting ? '登录中…' : '登录'}
        </button>
      </form>
    </main>
  );
}
