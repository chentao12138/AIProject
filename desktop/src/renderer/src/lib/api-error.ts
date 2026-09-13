/**
 * API error normalization (FE-001 PHASE D3, FE-001.5 PHASE 4).
 *
 * openapi-fetch returns { data, error, response } — HTTP errors are NOT
 * thrown, they arrive in `error` with the response status. Network
 * failures throw (TypeError: fetch failed).
 *
 * `unwrap` converts both shapes into a thrown ApiRequestError so every
 * queryFn can fail uniformly; `normalizeApiError` then maps it to a
 * user-facing, authorization-safe message.
 *
 * Anti-IDOR: 404 UI copy NEVER says "this resource belongs to another
 * user". The only allowed wording is "资源不存在或当前不可访问。"
 *
 * 401 semantics are build-aware: the Development Session hint is DEV
 * tooling; production shows the auth-pending placeholder instead of a
 * fake login or a generic "request failed".
 */

export type ApiErrorKind =
  | 'network'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'server'
  | 'unknown';

export interface NormalizedApiError {
  kind: ApiErrorKind;
  status?: number;
  message: string;
}

export class ApiRequestError extends Error {
  readonly status: number;

  constructor(status: number, body?: unknown) {
    super(`API request failed with status ${status}`);
    this.name = 'ApiRequestError';
    this.status = status;
    // keep body available for debugging without leaking it to the UI
    (this as { body?: unknown }).body = body;
  }
}

interface OpenApiResult<T> {
  data?: T;
  error?: unknown;
  response: { status: number };
}

/** Throws ApiRequestError on HTTP error; returns data otherwise. */
export function unwrap<T>(result: OpenApiResult<T>): T {
  if (result.error !== undefined && result.error !== null) {
    throw new ApiRequestError(result.response.status, result.error);
  }
  return result.data as T;
}

const DEV_UNAUTHORIZED_MESSAGE =
  '当前开发会话未认证或令牌已失效。请更新 Development Session 令牌。';
const PROD_UNAUTHORIZED_MESSAGE = 'Authentication integration pending.';
const FORBIDDEN_MESSAGE = '当前会话无权执行此操作。';
const NOT_FOUND_MESSAGE = '资源不存在或当前不可访问。';
const SERVER_MESSAGE = '服务器暂时无法完成请求，请稍后重试。';
const NETWORK_MESSAGE = '无法连接到后端服务。请确认服务已启动后重试。';
const UNKNOWN_MESSAGE = '请求失败，请稍后重试。';

export interface NormalizeApiErrorOptions {
  /** DEV builds show the Development Session hint for 401; production
   * shows the formal-auth placeholder. Defaults to import.meta.env.DEV. */
  isDev?: boolean;
}

export function normalizeApiError(
  error: unknown,
  options: NormalizeApiErrorOptions = {}
): NormalizedApiError {
  const isDev = options.isDev ?? import.meta.env.DEV;

  if (error instanceof ApiRequestError) {
    const status = error.status;
    if (status === 401) {
      return {
        kind: 'unauthorized',
        status,
        message: isDev ? DEV_UNAUTHORIZED_MESSAGE : PROD_UNAUTHORIZED_MESSAGE,
      };
    }
    if (status === 403) {
      return { kind: 'forbidden', status, message: FORBIDDEN_MESSAGE };
    }
    if (status === 404) {
      return { kind: 'not-found', status, message: NOT_FOUND_MESSAGE };
    }
    if (status >= 500) {
      return { kind: 'server', status, message: SERVER_MESSAGE };
    }
    return { kind: 'unknown', status, message: UNKNOWN_MESSAGE };
  }
  if (error instanceof TypeError) {
    return { kind: 'network', message: NETWORK_MESSAGE };
  }
  return { kind: 'unknown', message: UNKNOWN_MESSAGE };
}
