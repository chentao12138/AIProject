/**
 * API error normalization for Admin Web (mirrors the Desktop contract).
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
    (this as { body?: unknown }).body = body;
  }
}

interface OpenApiResult<T> {
  data?: T;
  error?: unknown;
  response: { status: number };
}

export function unwrap<T>(result: OpenApiResult<T>): T {
  if (result.error !== undefined && result.error !== null) {
    throw new ApiRequestError(result.response.status, result.error);
  }
  return result.data as T;
}

const MESSAGES: Record<ApiErrorKind, string> = {
  unauthorized: '登录状态已失效，请重新登录。',
  forbidden: '当前账号无权执行此操作（需要管理员权限）。',
  'not-found': '资源不存在或当前不可访问。',
  server: '服务器暂时无法完成请求，请稍后重试。',
  network: '无法连接到后端服务，请确认服务已启动后重试。',
  unknown: '请求失败，请稍后重试。',
};

export function normalizeApiError(error: unknown): NormalizedApiError {
  if (error instanceof ApiRequestError) {
    const status = error.status;
    if (status === 401)
      return { kind: 'unauthorized', status, message: MESSAGES.unauthorized };
    if (status === 403)
      return { kind: 'forbidden', status, message: MESSAGES.forbidden };
    if (status === 404)
      return { kind: 'not-found', status, message: MESSAGES['not-found'] };
    if (status >= 500)
      return { kind: 'server', status, message: MESSAGES.server };
    return { kind: 'unknown', status, message: MESSAGES.unknown };
  }
  if (error instanceof TypeError) {
    return { kind: 'network', message: MESSAGES.network };
  }
  return { kind: 'unknown', message: MESSAGES.unknown };
}
