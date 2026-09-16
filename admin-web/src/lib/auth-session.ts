/**
 * Admin Web auth session (ADR-026 / ADR-028).
 *
 * ADR-026 targets HttpOnly-cookie refresh storage for the browser app.
 * The CURRENT backend contract (/auth/login, /auth/refresh) exchanges
 * JSON token pairs in the request/response bodies and sets no cookies,
 * which cannot be written to an HttpOnly cookie from JavaScript. Until
 * the backend gains Set-Cookie transport, the Admin Web keeps BOTH
 * tokens in MEMORY ONLY:
 *  - never localStorage / sessionStorage / IndexedDB;
 *  - closing the tab ends the session (a deliberate conservative
 *    fallback — strictly safer than any disk-backed JS storage).
 *
 * Rotation semantics mirror the Desktop: every successful /auth/refresh
 * rotates the opaque token; a rejected refresh family clears the
 * session (re-login). All API calls take the client as an argument for
 * testability.
 */

import type { ApiClient, TokenProvider } from '@aistudy/api-client';

interface TokenPair {
  accessToken?: string;
  tokenType?: string;
  expiresIn?: number;
  refreshToken?: string;
  refreshExpiresIn?: number;
}

export interface AuthUser {
  subject?: string;
  username?: string;
  roles?: string[];
}

export interface AuthSnapshot {
  authenticated: boolean;
  user: AuthUser | null;
}

export const ADMIN_ROLE = 'ROLE_ADMIN';

export function isCompleteTokenPair(pair: unknown): pair is TokenPair {
  if (typeof pair !== 'object' || pair === null) {
    return false;
  }
  const candidate = pair as Record<string, unknown>;
  return (
    typeof candidate.accessToken === 'string' &&
    candidate.accessToken.trim().length > 0 &&
    typeof candidate.refreshToken === 'string' &&
    candidate.refreshToken.trim().length > 0
  );
}

export class AuthLoginError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthLoginError';
  }
}

export class AuthSession implements TokenProvider {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private user: AuthUser | null = null;
  private inflightRefresh: Promise<boolean> | null = null;
  private readonly listeners = new Set<() => void>();

  getAccessToken(): string | null {
    return this.accessToken;
  }

  getUser(): AuthUser | null {
    return this.user;
  }

  isAuthenticated(): boolean {
    return this.accessToken !== null && this.user !== null;
  }

  isAdmin(): boolean {
    return this.user?.roles?.includes(ADMIN_ROLE) ?? false;
  }

  snapshot(): AuthSnapshot {
    return { authenticated: this.isAuthenticated(), user: this.user };
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit() {
    for (const listener of this.listeners) listener();
  }

  private acceptPair(pair: TokenPair) {
    this.accessToken = (pair.accessToken as string).trim();
    this.refreshToken = (pair.refreshToken as string).trim();
  }

  resetLocal() {
    this.accessToken = null;
    this.refreshToken = null;
    this.inflightRefresh = null;
    this.user = null;
    this.emit();
  }

  private async fetchCurrentUser(api: ApiClient): Promise<AuthUser> {
    const me = await api.getCurrentUser();
    if (me.error || !me.data) {
      throw new Error('failed to load current user');
    }
    this.user = {
      subject: me.data.subject,
      username: me.data.username,
      roles: me.data.roles,
    };
    return this.user;
  }

  async login(
    api: ApiClient,
    username: string,
    password: string
  ): Promise<AuthUser> {
    const result = await api.login({ username: username.trim(), password });
    if (result.error || !isCompleteTokenPair(result.data)) {
      throw new AuthLoginError(
        result.response?.status === 401
          ? '用户名或密码错误。'
          : '登录失败，请稍后重试。'
      );
    }
    this.acceptPair(result.data);
    try {
      const user = await this.fetchCurrentUser(api);
      this.emit();
      return user;
    } catch {
      this.resetLocal();
      throw new AuthLoginError('登录失败，请稍后重试。');
    }
  }

  rotateRefresh(api: ApiClient): Promise<boolean> {
    if (this.inflightRefresh) return this.inflightRefresh;
    const current = this.refreshToken;
    if (!current) return Promise.resolve(false);
    this.inflightRefresh = (async () => {
      try {
        const result = await api.refresh({ refreshToken: current });
        if (result.error || !isCompleteTokenPair(result.data)) {
          this.resetLocal();
          return false;
        }
        this.acceptPair(result.data);
        return true;
      } catch {
        this.resetLocal();
        return false;
      } finally {
        this.inflightRefresh = null;
      }
    })();
    return this.inflightRefresh;
  }

  async logout(api: ApiClient): Promise<void> {
    const current = this.refreshToken;
    if (current) {
      try {
        await api.logout({ refreshToken: current });
      } catch {
        // best-effort revoke
      }
    }
    this.resetLocal();
  }
}

export const authSession = new AuthSession();
