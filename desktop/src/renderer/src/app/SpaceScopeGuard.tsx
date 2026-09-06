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

import { Outlet, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../lib/api-context';
import { unwrap, normalizeApiError } from '../lib/api-error';
import { queryKeys } from '../lib/query-keys';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';

export function SpaceScopeGuard() {
  const api = useApiClient();
  const { spaceId: spaceIdParam } = useParams();

  const spaceId = Number(spaceIdParam);
  const spaceIdValid = Number.isFinite(spaceId) && spaceId > 0;

  const spaceQuery = useQuery({
    queryKey: queryKeys.space(spaceId),
    queryFn: () => api.getLearningSpace(spaceId).then(unwrap),
    enabled: spaceIdValid,
  });

  if (!spaceIdValid) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  if (spaceQuery.isPending) {
    return <LoadingState text="加载空间…" />;
  }

  if (spaceQuery.isError || !spaceQuery.data) {
    return (
      <ErrorState
        message={normalizeApiError(spaceQuery.error).message}
        onRetry={() => void spaceQuery.refetch()}
      />
    );
  }

  return <Outlet />;
}
