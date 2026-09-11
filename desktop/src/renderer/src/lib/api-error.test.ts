/**
 * Error normalization + user-facing semantics (FE-001.5 PHASE 4).
 *
 * Every error class must map to a distinct, authorization-safe,
 * implementation-detail-free message, and the same model must drive
 * both query errors and mutation errors (pages already route both
 * through normalizeApiError).
 */

import { describe, expect, it } from 'vitest';
import {
  ApiRequestError,
  normalizeApiError,
  unwrap,
} from './api-error';

describe('normalizeApiError — error classes', () => {
  it('maps 401 to unauthorized with a dev build message', () => {
    const normalized = normalizeApiError(new ApiRequestError(401), {
      isDev: true,
    });
    expect(normalized.kind).toBe('unauthorized');
    expect(normalized.status).toBe(401);
    expect(normalized.message).toBe(
      '当前开发会话未认证或令牌已失效。请更新 Development Session 令牌。'
    );
  });

  it('maps 401 to the auth-pending placeholder in production builds', () => {
    const normalized = normalizeApiError(new ApiRequestError(401), {
      isDev: false,
    });
    expect(normalized.kind).toBe('unauthorized');
    expect(normalized.message).toBe('Authentication integration pending.');
  });

  it('maps 403 to forbidden', () => {
    const normalized = normalizeApiError(new ApiRequestError(403));
    expect(normalized.kind).toBe('forbidden');
    expect(normalized.message).toBe('当前会话无权执行此操作。');
  });

  it('maps 404 to not-found with authorization-safe wording', () => {
    const normalized = normalizeApiError(new ApiRequestError(404));
    expect(normalized.kind).toBe('not-found');
    expect(normalized.message).toBe('资源不存在或当前不可访问。');
    expect(normalized.message).not.toMatch(/other user|另一个用户|不属于/);
  });

  it('maps 5xx to server with retry-encouraging wording', () => {
    for (const status of [500, 502, 503, 504]) {
      const normalized = normalizeApiError(new ApiRequestError(status));
      expect(normalized.kind).toBe('server');
      expect(normalized.status).toBe(status);
      expect(normalized.message).toBe('服务器暂时无法完成请求，请稍后重试。');
    }
  });

  it('maps TypeError (fetch failure) to network', () => {
    const normalized = normalizeApiError(new TypeError('fetch failed'));
    expect(normalized.kind).toBe('network');
    expect(normalized.status).toBeUndefined();
    expect(normalized.message).toBe('无法连接到后端服务。请确认服务已启动后重试。');
  });

  it('maps other HTTP statuses to unknown', () => {
    for (const status of [400, 409, 422, 429]) {
      const normalized = normalizeApiError(new ApiRequestError(status));
      expect(normalized.kind).toBe('unknown');
      expect(normalized.status).toBe(status);
    }
  });

  it('maps arbitrary thrown values to unknown', () => {
    expect(normalizeApiError('boom').kind).toBe('unknown');
    expect(normalizeApiError(undefined).kind).toBe('unknown');
    expect(normalizeApiError(new Error('x')).kind).toBe('unknown');
  });

  it('never exposes backend stack traces or raw bodies in messages', () => {
    const withBody = normalizeApiError(
      new ApiRequestError(500, { trace: 'com.aistudy...', stack: 'at ...' })
    );
    expect(withBody.message).not.toMatch(/com\.aistudy|at /);
    expect(withBody.message).toBe('服务器暂时无法完成请求，请稍后重试。');
  });
});

describe('unwrap', () => {
  it('returns data on success', () => {
    expect(unwrap({ data: [1, 2], response: { status: 200 } })).toEqual([1, 2]);
  });

  it('throws ApiRequestError carrying the status on error', () => {
    const error = { message: 'nope' };
    let thrown: unknown;
    try {
      unwrap({ data: undefined, error, response: { status: 404 } });
    } catch (caught) {
      thrown = caught;
    }
    expect(thrown).toBeInstanceOf(ApiRequestError);
    expect((thrown as ApiRequestError).status).toBe(404);
  });
});
