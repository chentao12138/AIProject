/**
 * API error normalization (FE-001 PHASE D3, FE-001.5 PHASE 4,
 * FE-AUTH-001 formal-auth update).
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
 * 401 semantics (FE-AUTH-001): the refreshing-fetch wrapper rotates an
 * expired access token once; a 401 that reaches the UI means the
 * session could not be renewed -> a uniform re-login hint (no DEV token
 * paste workflow exists anymore).
 */

export type ApiErrorKind =
  | 'network'
  | 'unauthorized'
  | 'forbidden'
  | 'not-found'
  | 'server'
  | 'upstream'
  | 'unknown';

export interface NormalizedApiError {
  kind: ApiErrorKind;
  status?: number;
  message: string;
}

export class ApiRequestError extends Error {
  readonly status: number;
  /** Raw problem body — read only by normalizeApiError, never rendered whole. */
  readonly body?: unknown;

  constructor(status: number, body?: unknown) {
    super(`API request failed with status ${status}`);
    this.name = 'ApiRequestError';
    this.status = status;
    this.body = body;
  }
}

/**
 * The only error that genuinely means "the backend could not be reached":
 * our own fetch wrapper saw the request reject. Anything else that is a bare
 * TypeError (a client method missing, a bad argument) must NOT be reported as
 * a backend outage — that misdirected every report of a stale bundle.
 */
export class NetworkUnavailableError extends Error {
  constructor(cause?: unknown) {
    super('network request failed', { cause });
    this.name = 'NetworkUnavailableError';
  }
}

/** Shape every shared-client method resolves to (openapi-fetch result). */
export interface ApiResult<T> {
  data?: T;
  error?: unknown;
  response: { status: number };
}

/** Throws ApiRequestError on HTTP error; returns data otherwise. */
export function unwrap<T>(result: ApiResult<T>): T {
  if (result.error !== undefined && result.error !== null) {
    throw new ApiRequestError(result.response.status, result.error);
  }
  return result.data as T;
}

const UNAUTHORIZED_MESSAGE = '登录状态已失效，请重新登录。';
const FORBIDDEN_MESSAGE = '当前会话无权执行此操作。';
/** Anti-IDOR wording — exported so guards and pages share one source. */
export const NOT_FOUND_MESSAGE = '资源不存在或当前不可访问。';
const SERVER_MESSAGE = '服务器暂时无法完成请求，请稍后重试。';
/** 502 means our server reached something that refused us — most often the AI
 * provider rejecting a key or model. Telling the user to "retry later" sends
 * them to the wrong place, so it gets its own copy. */
const UPSTREAM_MESSAGE = '上游服务拒绝了请求，请检查相关配置（如 API Key、模型名）。';
/** Also the copy for a session that cannot be confirmed because the
 * backend is unreachable — reused by RequireAuth so the wording has one source. */
export const NETWORK_MESSAGE = '无法连接到后端服务。请确认服务已启动后重试。';
const UNKNOWN_MESSAGE = '请求失败，请稍后重试。';
const CLIENT_BUG_MESSAGE = '页面请求未能发出，应用内部出错。请刷新后重试。';

/** Shape every 4xx problem body the backend returns for user input. */
interface FieldViolation {
  field?: unknown;
  message?: unknown;
}

interface ProblemBody {
  code?: unknown;
  detail?: unknown;
  errors?: unknown;
}

/** Only a validation `detail` may be shown; other details can echo internals. */
const VALIDATION_CODES = new Set(['VALIDATION_ERROR', 'BAD_REQUEST']);

/**
 * Turn a ProblemDetail into one readable line. The backend reports input
 * problems two ways: a structured `errors[]` list, or a bare `detail` under
 * `code: VALIDATION_ERROR` (e.g. "TRUE_FALSE requires correctBoolean").
 * Anything else keeps the generic copy, so a server-side detail can never
 * reach the UI.
 */
function validationMessage(body: unknown): string | null {
  const problem = (body ?? {}) as ProblemBody;

  if (Array.isArray(problem.errors) && problem.errors.length > 0) {
    const parts = (problem.errors as FieldViolation[])
      .map((v) => {
        const field = typeof v.field === 'string' ? v.field : '';
        const message = typeof v.message === 'string' ? v.message : '';
        return [field, message].filter(Boolean).join('：');
      })
      .filter(Boolean);
    if (parts.length > 0) {
      return `校验未通过：${parts.join('；')}`;
    }
  }

  if (
    typeof problem.code === 'string' &&
    VALIDATION_CODES.has(problem.code) &&
    typeof problem.detail === 'string' &&
    problem.detail.trim() !== ''
  ) {
    return `校验未通过：${problem.detail.trim()}`;
  }

  return null;
}

/**
 * The ProblemDetail `code`, when the body carries one.
 *
 * `normalizeApiError` deliberately collapses every status into safe copy, so a
 * page that must react to one specific server condition reads its code here.
 * The alternative — matching the English `detail` text — is the defect C12
 * exists to remove, and a detail can echo internals.
 */
export function apiProblemCode(error: unknown): string | null {
  if (!(error instanceof ApiRequestError)) {
    return null;
  }
  const code = (error.body as ProblemBody | undefined)?.code;
  return typeof code === 'string' && code.trim() !== '' ? code.trim() : null;
}

export function normalizeApiError(
  error: unknown
): NormalizedApiError {
  if (error instanceof ApiRequestError) {
    const status = error.status;
    if (status === 401) {
      return {
        kind: 'unauthorized',
        status,
        message: UNAUTHORIZED_MESSAGE,
      };
    }
    if (status === 403) {
      return { kind: 'forbidden', status, message: FORBIDDEN_MESSAGE };
    }
    if (status === 404) {
      return { kind: 'not-found', status, message: NOT_FOUND_MESSAGE };
    }
    if (status === 502) {
      return { kind: 'upstream', status, message: UPSTREAM_MESSAGE };
    }
    if (status >= 500) {
      return { kind: 'server', status, message: SERVER_MESSAGE };
    }
    const validation = validationMessage(error.body);
    if (validation) {
      return { kind: 'unknown', status, message: validation };
    }
    return { kind: 'unknown', status, message: UNKNOWN_MESSAGE };
  }
  if (error instanceof NetworkUnavailableError) {
    return { kind: 'network', message: NETWORK_MESSAGE };
  }
  if (error instanceof TypeError) {
    // A TypeError that did not come from fetch is a defect in the call site
    // (missing method, wrong argument), not a backend outage.
    return { kind: 'unknown', message: CLIENT_BUG_MESSAGE };
  }
  return { kind: 'unknown', message: UNKNOWN_MESSAGE };
}
