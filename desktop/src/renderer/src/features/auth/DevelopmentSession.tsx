/**
 * Development Session (FE-001 PHASE E2/E3).
 *
 * DEV only: lets a developer paste a Bearer access token into the
 * in-memory session. Nothing is persisted, nothing is remembered.
 *
 * Non-DEV builds: no token input at all — shows the formal-auth
 * placeholder instead (no fake production login).
 */

import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { tokenSession } from '../../lib/api-client';
import { Button } from '../../components/Button';

const IS_DEV = import.meta.env.DEV;

export function DevelopmentSession() {
  const [token, setToken] = useState('');
  const [applied, setApplied] = useState(false);
  const queryClient = useQueryClient();

  if (!IS_DEV) {
    return (
      <div className="auth-placeholder" role="status">
        Authentication integration pending
      </div>
    );
  }

  /**
   * The dev session is the AUTH CACHE BOUNDARY: switching the active
   * token must drop every previous principal's server-state cache, so
   * the old user's data can never survive a token change. resetQueries
   * clears cached data AND refetches active queries under the new
   * token (invalidate alone would keep serving stale cached data).
   */
  const apply = () => {
    tokenSession.setAccessToken(token);
    void queryClient.resetQueries();
    setApplied(Boolean(token && token.trim().length > 0));
  };

  const clear = () => {
    tokenSession.clear();
    void queryClient.resetQueries();
    setToken('');
    setApplied(false);
  };

  return (
    <div className="dev-session">
      <p className="dev-session__note">
        Development-only token injection. Not persisted.
      </p>
      <div className="dev-session__row">
        <input
          type="password"
          className="input"
          aria-label="Development access token"
          placeholder="Paste Bearer token…"
          value={token}
          onChange={(event) => setToken(event.target.value)}
          spellCheck={false}
        />
        <Button variant="primary" onClick={apply}>
          Apply
        </Button>
        <Button onClick={clear}>Clear</Button>
      </div>
      {applied && (
        <p className="dev-session__status" role="status">
          Token applied (in-memory only).
        </p>
      )}
    </div>
  );
}
