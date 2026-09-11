/**
 * KnowledgePoint Detail + Publish flow (FE-001 PHASE I5/I6).
 *
 * DRAFT -> shows a single Publish action (publishKnowledgePoint).
 * PUBLISHED -> shows a Published badge; no repeated publish CTA
 * (backend publish is idempotent, but the UI must not fabricate
 * repeat requests).
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import { unwrap, normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { formatDateTime } from '../../lib/format';
import { parsePositiveIdParam, isPositiveId } from '../../lib/ids';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function KnowledgePointDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam, knowledgePointId: pointIdParam } = useParams();

  const spaceId = parsePositiveIdParam(spaceIdParam);
  const knowledgePointId = parsePositiveIdParam(pointIdParam);
  const idsValid = spaceId !== null && knowledgePointId !== null;

  const pointQuery = useQuery({
    queryKey: queryKeys.knowledgePoint(spaceId ?? 0, knowledgePointId ?? 0),
    queryFn: () => {
      if (spaceId === null || knowledgePointId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.getKnowledgePoint(spaceId, knowledgePointId).then(unwrap);
    },
    enabled: idsValid,
  });

  // Category name resolution (review fix #5): reuse the catalog query
  // key so arriving from the Knowledge list hits the warm cache. A
  // failing category query must never take the detail page down —
  // resolution degrades to "#<id>" instead.
  const categoriesQuery = useQuery({
    queryKey: queryKeys.knowledgeCategories(spaceId ?? 0),
    queryFn: () => {
      if (spaceId === null) {
        return Promise.reject(new Error('invalid space id'));
      }
      return api.listKnowledgeCategories(spaceId).then(unwrap);
    },
    enabled: idsValid,
  });

  const publish = useMutation({
    mutationFn: () => {
      if (spaceId === null || knowledgePointId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.publishKnowledgePoint(spaceId, knowledgePointId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoint(spaceId ?? 0, knowledgePointId ?? 0),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoints(spaceId ?? 0),
      });
    },
  });

  if (!idsValid) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  if (pointQuery.isPending) {
    return <LoadingState text="加载知识点…" />;
  }

  if (pointQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(pointQuery.error).message}
        onRetry={() => void pointQuery.refetch()}
      />
    );
  }

  const point = pointQuery.data;
  if (!point) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const isDraft = point.status === 'DRAFT';
  const isPublished = point.status === 'PUBLISHED';

  // Category name resolution (STRETCH D): a Map lookup instead of an
  // array find() per render — degrades to "#<id>" when unresolved.
  const categoryNameById = new Map<number, string>();
  for (const category of categoriesQuery.data ?? []) {
    if (category.id !== undefined && category.name) {
      categoryNameById.set(category.id, category.name);
    }
  }
  const categoryNameFor = (categoryId: number): string =>
    categoryNameById.get(categoryId) ?? `#${categoryId}`;

  return (
    <div className="page">
      <PageHeader
        title={point.title ?? 'Untitled'}
        actions={
          <>
            <StatusBadge status={point.status} />
            {isDraft && (
              <Button
                variant="primary"
                disabled={publish.isPending}
                onClick={() => publish.mutate()}
              >
                {publish.isPending ? 'Publishing…' : 'Publish'}
              </Button>
            )}
          </>
        }
      />

      {publish.isError && (
        <p className="form__error" role="alert">
          {normalizeApiError(publish.error).message}
        </p>
      )}

      {isPublished && (
        <p className="page__note">
          Published at {formatDateTime(point.publishedAt)}
        </p>
      )}

      <dl className="detail-list">
        <div className="detail-list__row">
          <dt>Status</dt>
          <dd>
            <StatusBadge status={point.status} />
          </dd>
        </div>
        {point.originType && (
          <div className="detail-list__row">
            <dt>Origin</dt>
            <dd>{point.originType}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>Category</dt>
          <dd>
            {isPositiveId(point.categoryId)
              ? categoryNameFor(point.categoryId)
              : '—'}
          </dd>
        </div>
        {point.difficulty && (
          <div className="detail-list__row">
            <dt>Difficulty</dt>
            <dd>{point.difficulty}</dd>
          </div>
        )}
        {point.summary && (
          <div className="detail-list__row">
            <dt>Summary</dt>
            <dd>{point.summary}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>Created</dt>
          <dd>{formatDateTime(point.createdAt)}</dd>
        </div>
        <div className="detail-list__row">
          <dt>Updated</dt>
          <dd>{formatDateTime(point.updatedAt)}</dd>
        </div>
      </dl>

      <section className="content-block">
        <h2 className="section-title">Content</h2>
        <div className="content-block__body">{point.content || '—'}</div>
      </section>
    </div>
  );
}
