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
 *    treat as an unrecoverable session (clear local state, re-login). A 5xx
 *    or a transport failure is NOT: the encrypted token survives and the
 *    next attempt (or the offline retry) completes the restore.
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
  /**
   * A refresh token is held but the exchange could not complete because the
   * backend was unreachable or 5xx'd. The session is recoverable (retry),
   * NOT dead: nothing was wiped.
   */
  restoreUnavailable: boolean;
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

/** Raised when /auth/me cannot produce a principal; carries the HTTP status. */
export class AuthUserLookupError extends Error {
  constructor(readonly status?: number) {
    super('failed to load current user');
    this.name = 'AuthUserLookupError';
  }
}

/**
 * Only these statuses mean "this token is burnt": invalid, expired or
 * revoked credentials. Everything else (5xx, 408/429, a thrown transport
 * error surfacing as `undefined`) is a backend problem, not a session
 * problem — the rotating refresh token stays on disk and is retried.
 */
export function isDeadCredentialStatus(status: number | undefined): boolean {
  return status === 400 || status === 401 || status === 403;
}

export class AuthSession implements TokenProvider {
  private accessToken: string | null = null;
  private refreshToken: string | null = null;
  private user: AuthUser | null = null;
  private inflightRefresh: Promise<boolean> | null = null;
  private restoreUnavailable = false;
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
      restoreUnavailable: this.restoreUnavailable,
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
    this.restoreUnavailable = false;
    await saveStoredRefreshToken(this.refreshToken);
  }

  /** Wipe every local trace of the session (memory + encrypted store). */
  async resetLocal(): Promise<void> {
    this.accessToken = null;
    this.refreshToken = null;
    this.inflightRefresh = null;
    this.user = null;
    this.restoreUnavailable = false;
    await clearStoredRefreshToken();
    this.emit();
  }

  /** Fetch the current principal and store it; throws on failure. */
  private async fetchCurrentUser(api: ApiClient): Promise<AuthUser> {
    const me = await api.getCurrentUser();
    const status = me.response?.status;
    if (me.error || !me.data) {
      throw new AuthUserLookupError(status);
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
   * token for a fresh pair. Resolves to the final snapshot; never throws.
   * Safe to re-enter — the offline retry affordance calls it again, so a
   * backend that is merely restarting does not cost the user their session.
   */
  async bootstrap(api: ApiClient): Promise<AuthSnapshot> {
    if (this.isAuthenticated()) {
      return this.snapshot();
    }
    if (!this.refreshToken) {
      const stored = await loadStoredRefreshToken().catch(() => null);
      if (!stored) {
        this.emit();
        return this.snapshot();
      }
      this.refreshToken = stored;
    }
    const outcome = await this.exchangeRefresh(api);
    if (outcome === 'rotated') {
      this.emit();
    } else if (outcome === 'unavailable') {
      this.restoreUnavailable = true;
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
      if (
        error instanceof AuthUserLookupError &&
        isDeadCredentialStatus(error.status)
      ) {
        await this.resetLocal();
      } else {
        // The pair was issued for this login; only its confirmation failed.
        // Keep it so a retry or a restart can finish, and let the caller
        // surface a retryable error.
        this.restoreUnavailable = true;
        this.emit();
      }
      throw error instanceof AuthLoginError
        ? error
        : new AuthLoginError('登录失败，请稍后重试。');
    }
  }

  /**
   * Rotate the refresh token once (single-flight): concurrent 401s
   * share one /auth/refresh call. Resolves true on success, false on
   * rejection. Only a credential-rejecting status clears the session —
   * a 5xx leaves the token usable so the next 401 rotates again.
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
          if (isDeadCredentialStatus(result.response?.status)) {
            await this.resetLocal();
          }
          return false;
        }
        await this.acceptPair(result.data);
        return true;
      } catch {
        return false;
      } finally {
        this.inflightRefresh = null;
      }
    })();
    return this.inflightRefresh;
  }

  /**
   * Exchange used only by bootstrap (no single-flight needed there: one
   * restore runs per renderer process, and the vault holds one token).
   */
  private async exchangeRefresh(
    api: ApiClient
  ): Promise<'rotated' | 'dead' | 'unavailable'> {
    const current = this.refreshToken;
    if (!current) {
      return 'dead';
    }
    try {
      const result = await api.refresh({ refreshToken: current });
      if (result.error || !isCompleteTokenPair(result.data)) {
        return isDeadCredentialStatus(result.response?.status)
          ? 'dead'
          : 'unavailable';
      }
      await this.acceptPair(result.data);
      try {
        await this.fetchCurrentUser(api);
      } catch (error) {
        return error instanceof AuthUserLookupError &&
          isDeadCredentialStatus(error.status)
          ? 'dead'
          : 'unavailable';
      }
      return 'rotated';
    } catch {
      return 'unavailable';
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
