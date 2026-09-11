/**
 * Query retry policy (FE-001.5 PHASE 5).
 *
 * The retry decision must be deterministic per error class:
 * auth/forbidden/not-found never retry; network and 5xx retry with a
 * hard bound; mutations never replay.
 */

import { describe, expect, it } from 'vitest';
import { ApiRequestError } from './api-error';
import {
  QUERY_RETRY_MAX_ATTEMPTS,
  shouldRetryQuery,
} from './query-retry';

describe('shouldRetryQuery', () => {
  it('never retries 401', () => {
    expect(shouldRetryQuery(0, new ApiRequestError(401))).toBe(false);
    expect(shouldRetryQuery(1, new ApiRequestError(401))).toBe(false);
  });

  it('never retries 403', () => {
    expect(shouldRetryQuery(0, new ApiRequestError(403))).toBe(false);
  });

  it('never retries 404', () => {
    expect(shouldRetryQuery(0, new ApiRequestError(404))).toBe(false);
    expect(shouldRetryQuery(1, new ApiRequestError(404))).toBe(false);
  });

  it('retries network errors with a hard bound', () => {
    expect(shouldRetryQuery(0, new TypeError('fetch failed'))).toBe(true);
    expect(shouldRetryQuery(1, new TypeError('fetch failed'))).toBe(true);
    expect(shouldRetryQuery(QUERY_RETRY_MAX_ATTEMPTS, new TypeError('fetch failed'))).toBe(false);
  });

  it('retries 5xx with a hard bound', () => {
    expect(shouldRetryQuery(0, new ApiRequestError(500))).toBe(true);
    expect(shouldRetryQuery(1, new ApiRequestError(503))).toBe(true);
    expect(shouldRetryQuery(QUERY_RETRY_MAX_ATTEMPTS, new ApiRequestError(500))).toBe(false);
  });

  it('never retries other statuses or unknown errors', () => {
    expect(shouldRetryQuery(0, new ApiRequestError(400))).toBe(false);
    expect(shouldRetryQuery(0, new ApiRequestError(409))).toBe(false);
    expect(shouldRetryQuery(0, new Error('boom'))).toBe(false);
    expect(shouldRetryQuery(0, 'string error')).toBe(false);
  });

  it('exposes the retry bound constant so callers stay in sync', () => {
    expect(QUERY_RETRY_MAX_ATTEMPTS).toBeGreaterThan(0);
    expect(QUERY_RETRY_MAX_ATTEMPTS).toBeLessThanOrEqual(3);
  });
});
