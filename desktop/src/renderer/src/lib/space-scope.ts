/**
 * Current LearningSpace scope (FE-001 §8, ADR-038).
 *
 * The URL is the single source of truth for "which space am I in": no
 * store, no localStorage, no mirror in component state. Every space-scoped
 * page reads it through `useSpaceId()` so the parsing rule lives once, and
 * every cache key is produced by `queryKeys`, which encodes the
 * "no space resolved yet" case itself — pages never invent a placeholder id.
 */

import { useLocation, useParams } from 'react-router-dom';
import { parsePositiveIdParam } from './ids';

/**
 * The space id for the current location, or null when the URL does not
 * carry a valid one.
 *
 * An explicit `:spaceId` route param is authoritative: a malformed value
 * must resolve to null (and render not-found), never to a prefix of itself.
 * The pathname fallback only applies where no such param exists (the shell,
 * rendered above the space routes).
 */
export function useSpaceId(): number | null {
  const params = useParams();
  const location = useLocation();
  if (params.spaceId !== undefined) {
    return parsePositiveIdParam(params.spaceId);
  }
  const match = location.pathname.match(/^\/spaces\/(\d+)(?:\/|$)/);
  return match ? parsePositiveIdParam(match[1]) : null;
}

/**
 * Narrow an optional id inside a `queryFn` / `mutationFn`.
 *
 * Callers already gate on it (`enabled`, the route guard), so this throws
 * only if a page forgets to. It exists so the compiler — not 30 copies of
 * an inline `Promise.reject` — proves the id reaching the API is real.
 */
export function requireId(value: number | null, what = 'id'): number {
  if (value === null) {
    throw new Error(`invalid ${what}`);
  }
  return value;
}
