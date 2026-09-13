/**
 * Development Session (FE-001 PHASE E2/E3, FE-001.5 PHASE 6).
 *
 * DEV only: lets a developer paste a Bearer access token into the
 * in-memory session. Nothing is persisted, nothing is remembered.
 *
 * Hardening (PHASE 6):
 *  - Apply is disabled for empty / whitespace-only input
 *  - tokens are trimmed on Apply (setAccessToken already trims)
 *  - any subsequent edit clears the stale applied/cleared status
 *  - status messages live in an aria-live region for screen readers
 *  - Apply/Clear remain the AUTH CACHE BOUNDARY (resetQueries, never
 *    invalidate) so a previous principal's cached data never survives
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
  const [status, setStatus] = useState<'applied' | 'cleared' | null>(null);
  const queryClient = useQueryClient();

  if (!IS_DEV) {
    return (
      <div className="auth-placeholder" role="status">
        Authentication integration pending
      </div>
    );
  }

  const canApply = token.trim().length > 0;

  /**
   * The dev session is the AUTH CACHE BOUNDARY: switching the active
   * token must drop every previous principal's server-state cache, so
   * the old user's data can never survive a token change. resetQueries
   * clears cached data AND refetches active queries under the new
   * token (invalidate alone would keep serving stale cached data).
   */
  const apply = () => {
    if (!canApply) {
      return;
    }
    tokenSession.setAccessToken(token);
    void queryClient.resetQueries();
    setStatus('applied');
  };

  const clear = () => {
    tokenSession.clear();
    void queryClient.resetQueries();
    setToken('');
    setStatus('cleared');
  };

  const handleTokenChange = (value: string) => {
    setToken(value);
    // Any edit invalidates the previous status message.
    setStatus(null);
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
          onChange={(event) => handleTokenChange(event.target.value)}
          spellCheck={false}
          autoComplete="off"
        />
        <Button variant="primary" onClick={apply} disabled={!canApply}>
          Apply
        </Button>
        <Button onClick={clear}>Clear</Button>
      </div>
      {status && (
        <p className="dev-session__status" role="status" aria-live="polite">
          {status === 'applied'
            ? 'Token applied (in-memory only).'
            : 'Token cleared.'}
        </p>
      )}
    </div>
  );
}
