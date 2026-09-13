/**
 * API client singleton for the Desktop renderer.
 *
 * Renderer code must ONLY reach the backend through the shared
 * @aistudy/api-client (FE-001 PHASE D). No business component ever
 * calls fetch/axios/XHR directly.
 *
 * Token session: FE-001 uses a development-only IN-MEMORY token
 * (FE-001 PHASE E). Nothing is persisted — no localStorage, no
 * sessionStorage, no cookie strategy, no hardcoded token.
 */

import { createApiClient } from '@aistudy/api-client';
import type { TokenProvider } from '@aistudy/api-client';
import { resolveApiBaseUrl } from '../../../shared/api-config';

/**
 * In-memory token session (FE-001 PHASE E1).
 *
 * - getAccessToken(): returns the current token or null
 * - setAccessToken(): dev session UI pastes a Bearer token here
 * - clear(): forgets the token
 *
 * Refreshing/reloading the Electron window destroys the token — this
 * is the CORRECT FE-001 behavior; formal auth is deferred.
 */
export class InMemoryTokenSession implements TokenProvider {
  private token: string | null = null;

  getAccessToken(): string | null {
    return this.token;
  }

  setAccessToken(token: string | null): void {
    this.token = token && token.trim().length > 0 ? token.trim() : null;
  }

  clear(): void {
    this.token = null;
  }
}

export const tokenSession = new InMemoryTokenSession();

export const apiBaseUrl = resolveApiBaseUrl(import.meta.env.VITE_API_BASE_URL);

export const apiClient = createApiClient(apiBaseUrl, tokenSession);
