/**
 * Formal authentication session (FE-AUTH-001, ADR-026).
 *
 * Token storage contract for the Desktop:
 *  - ACCESS token: renderer MEMORY ONLY. Lost on app quit by design.
 *  - REFRESH token (opaque, rotating): written ONLY through the main
 *    process safeStorage vault (encrypted at rest by the OS). Never in
 *    localStorage/sessionStorage/cookies, never logged.
 *
 * Backend behaviour this assumes (see server AuthController):
 *  - /auth/login and /auth/refresh return a TokenPairResponse or, on
 *    failure, HTTP 401 with a null-token body.
 *  - every successful /auth/refresh ROTATES the refresh token; reusing
 *    a rotated token revokes the whole refresh family -> 401, which we
 *    treat as an unrecoverable session (clear local state, re-login).
 *
 * The session is framework-free and stores no React state; AuthProvider
 * subscribes and mirrors the snapshot. All API calls take the client as
 * an argument so the module stays trivially unit-testable.
 */

import type { ApiClient, TokenProvider } from '@aistudy/api-client';
import {
  clearStoredRefreshToken,
  loadStoredRefreshToken,
  saveStoredRefreshToken,
} from './auth-vault-bridge';

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

/** Structural validation: a usable pair has both non-empty tokens. */
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

/** Raised when credentials are rejected (HTTP 401 / malformed pair). */
export class AuthLoginError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthLoginError';
  }
}

/** Raised when an in-flight refresh could not renew the session. */
export class SessionExpiredError extends Error {
  constructor() {
    super('session-expired');
    this.name = 'SessionExpiredError';
  }
}

export class AuthSession implements TokenProvider {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private user: AuthUser | null = null;
  private inflightRefresh: Promise<boolean> | null = null;
  private readonly listeners = new Set<() => void>();

  /** TokenProvider hook for the shared api-client. */
  getAccessToken(): string | null {
    return this.accessToken;
  }

  getUser(): AuthUser | null {
    return this.user;
  }

  isAuthenticated(): boolean {
    return this.accessToken !== null && this.user !== null;
  }

  snapshot(): AuthSnapshot {
    return {
      authenticated: this.isAuthenticated(),
      user: this.user,
    };
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private emit(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }

  /** Accept a rotated/issued pair: access in memory, refresh encrypted. */
  private async acceptPair(pair: TokenPair): Promise<void> {
    this.accessToken = (pair.accessToken as string).trim();
    this.refreshToken = (pair.refreshToken as string).trim();
    await saveStoredRefreshToken(this.refreshToken);
  }

  /** Wipe every local trace of the session (memory + encrypted store). */
  async resetLocal(): Promise<void> {
    this.accessToken = null;
    this.refreshToken = null;
    this.inflightRefresh = null;
    this.user = null;
    await clearStoredRefreshToken();
    this.emit();
  }

  /** Fetch the current principal and store it; throws on failure. */
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

  /**
   * App-startup restore: exchange the persisted (encrypted) refresh
   * token for a fresh pair. Resolves to the final snapshot; never throws
   * (a failed restore degrades to the anonymous state).
   */
  async bootstrap(api: ApiClient): Promise<AuthSnapshot> {
    const stored = await loadStoredRefreshToken().catch(() => null);
    if (!stored) {
      this.emit();
      return this.snapshot();
    }
    this.refreshToken = stored;
    const renewed = await this.exchangeRefresh(api);
    if (renewed) {
      this.emit();
    } else {
      await this.resetLocal();
    }
    return this.snapshot();
  }

  /** Username/password login -> pair -> current user. */
  async login(
    api: ApiClient,
    username: string,
    password: string
  ): Promise<AuthUser> {
    const result = await api.login({
      username: username.trim(),
      password,
    });
    if (result.error || !isCompleteTokenPair(result.data)) {
      const status = result.response?.status;
      throw new AuthLoginError(
        status === 401
          ? '用户名或密码错误。'
          : '登录失败，请稍后重试。'
      );
    }
    await this.acceptPair(result.data);
    try {
      const user = await this.fetchCurrentUser(api);
      this.emit();
      return user;
    } catch (error) {
      await this.resetLocal();
      throw error instanceof AuthLoginError
        ? error
        : new AuthLoginError('登录失败，请稍后重试。');
    }
  }

  /**
   * Rotate the refresh token once (single-flight): concurrent 401s
   * share one /auth/refresh call. Resolves true on success, false (and
   * clears the session) on family rejection / transport failure.
   */
  rotateRefresh(api: ApiClient): Promise<boolean> {
    if (this.inflightRefresh) {
      return this.inflightRefresh;
    }
    const current = this.refreshToken;
    if (!current) {
      return Promise.resolve(false);
    }
    this.inflightRefresh = (async () => {
      try {
        const result = await api.refresh({ refreshToken: current });
        if (result.error || !isCompleteTokenPair(result.data)) {
          await this.resetLocal();
          return false;
        }
        await this.acceptPair(result.data);
        return true;
      } catch {
        await this.resetLocal();
        return false;
      } finally {
        this.inflightRefresh = null;
      }
    })();
    return this.inflightRefresh;
  }

  /** Exchange used only by bootstrap (no single-flight needed there). */
  private async exchangeRefresh(api: ApiClient): Promise<boolean> {
    const current = this.refreshToken;
    if (!current) {
      return false;
    }
    try {
      const result = await api.refresh({ refreshToken: current });
      if (result.error || !isCompleteTokenPair(result.data)) {
        return false;
      }
      await this.acceptPair(result.data);
      await this.fetchCurrentUser(api);
      return true;
    } catch {
      return false;
    }
  }

  /** Best-effort server-side revoke, then unconditional local reset. */
  async logout(api: ApiClient): Promise<void> {
    const current = this.refreshToken;
    if (current) {
      try {
        await api.logout({ refreshToken: current });
      } catch {
        // Revocation is best-effort; local state is cleared regardless.
      }
    }
    await this.resetLocal();
  }
}

/** App-wide singleton (the api-client singleton reads from it). */
export const authSession = new AuthSession();
