/**
 * TanStack Query resilience policy (FE-001.5 PHASE 5).
 *
 * Deliberate, testable retry policy for the Electron renderer:
 *
 *  - 401 / 403 / 404: deterministic outcomes — NEVER retried.
 *  - network errors and 5xx: transient — small bounded retry
 *    (QUERY_RETRY_MAX_ATTEMPTS extra attempts, exponential backoff
 *    from TanStack's default retryDelay).
 *  - 502: our server reached an upstream that refused us (bad AI key,
 *    unknown model). Retrying cannot fix the configuration, so the
 *    actionable message should appear on the first failure.
 *  - anything else (4xx, unknown): not retried.
 *
 * Mutations never auto-replay: unsafe writes are not replayed unless
 * a future feature explicitly justifies it.
 */

import { normalizeApiError } from './api-error';

/** Maximum extra attempts after the initial failure. */
export const QUERY_RETRY_MAX_ATTEMPTS = 2;

export function shouldRetryQuery(
  failureCount: number,
  error: unknown
): boolean {
  if (failureCount >= QUERY_RETRY_MAX_ATTEMPTS) {
    return false;
  }
  const kind = normalizeApiError(error).kind;
  if (
    kind === 'unauthorized' ||
    kind === 'forbidden' ||
    kind === 'not-found' ||
    kind === 'upstream'
  ) {
    return false;
  }
  return kind === 'network' || kind === 'server';
}
