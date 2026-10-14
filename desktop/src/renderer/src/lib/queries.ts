/**
 * Read-query primitives (FE-ARCH stage C).
 *
 * A route id is `number | null` until the URL resolves it, so every query
 * needs the same trio: a key, `enabled`, and a queryFn that refuses to call
 * the API with a missing id. Pages wrote that trio by hand ~30 times, which
 * is how one of them ended up with a key that ignored its own pagination and
 * another with a placeholder id baked into the cache.
 */

import type { QueryKey } from '@tanstack/react-query';
import { unwrap, type ApiResult } from './api-error';
import { requireId } from './space-scope';

/** Query options for a read scoped to one LearningSpace. */
export function spaceQuery<T>(
  spaceId: number | null,
  key: QueryKey,
  run: (spaceId: number) => Promise<ApiResult<T>>
) {
  return {
    queryKey: key,
    queryFn: () => run(requireId(spaceId, 'space id')).then(unwrap),
    enabled: spaceId !== null,
  };
}

/** Query options for a read scoped to one entity inside a LearningSpace. */
export function entityQuery<T>(
  spaceId: number | null,
  entityId: number | null,
  key: QueryKey,
  run: (spaceId: number, entityId: number) => Promise<ApiResult<T>>
) {
  return {
    queryKey: key,
    queryFn: () =>
      run(requireId(spaceId, 'space id'), requireId(entityId)).then(unwrap),
    enabled: spaceId !== null && entityId !== null,
  };
}
