/**
 * SpaceScopeGuard (FE-001 pre-commit review fix #3).
 *
 * /spaces/:spaceId/* — validates the space scope ONCE before any child
 * resource query is allowed to run:
 *
 *   - invalid numeric id        -> inaccessible UI, zero API calls
 *   - getLearningSpace pending  -> loading
 *   - 404 / error / unowned     -> normalized error UI
 *   - success                   -> <Outlet /> renders child routes
 *
 * Uses queryKeys.space(spaceId) so the AppShell sidebar and the guard
 * share one TanStack Query cache entry — no second source of truth.
 */

import { Outlet } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../lib/api-context';
import { NOT_FOUND_MESSAGE, normalizeApiError } from '../lib/api-error';
import { spaceQuery } from '../lib/queries';
import { useSpaceId } from '../lib/space-scope';
import { queryKeys } from '../lib/query-keys';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';

export function SpaceScopeGuard() {
  const api = useApiClient();
  const spaceId = useSpaceId();
  const spaceIdValid = spaceId !== null;

  const spaceDetailQuery = useQuery(
    spaceQuery(spaceId, queryKeys.space(spaceId), (id) =>
      api.getLearningSpace(id)
    )
  );

  if (!spaceIdValid) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  if (spaceDetailQuery.isPending) {
    return <LoadingState text="加载空间…" />;
  }

  if (spaceDetailQuery.isError || !spaceDetailQuery.data) {
    return (
      <ErrorState
        message={normalizeApiError(spaceDetailQuery.error).message}
        onRetry={() => void spaceDetailQuery.refetch()}
      />
    );
  }

  return <Outlet />;
}
