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
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { StatusBadge } from '../../components/StatusBadge';

export function KnowledgePointDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam, knowledgePointId: pointIdParam } = useParams();

  const spaceId = Number(spaceIdParam);
  const knowledgePointId = Number(pointIdParam);
  const idsValid =
    Number.isFinite(spaceId) &&
    spaceId > 0 &&
    Number.isFinite(knowledgePointId) &&
    knowledgePointId > 0;

  const pointQuery = useQuery({
    queryKey: queryKeys.knowledgePoint(spaceId, knowledgePointId),
    queryFn: () => api.getKnowledgePoint(spaceId, knowledgePointId).then(unwrap),
    enabled: idsValid,
  });

  // Category name resolution (review fix #5): reuse the catalog query
  // key so arriving from the Knowledge list hits the warm cache. A
  // failing category query must never take the detail page down —
  // resolution degrades to "#<id>" instead.
  const categoriesQuery = useQuery({
    queryKey: queryKeys.knowledgeCategories(spaceId),
    queryFn: () => api.listKnowledgeCategories(spaceId).then(unwrap),
    enabled: idsValid,
  });

  const publish = useMutation({
    mutationFn: () => api.publishKnowledgePoint(spaceId, knowledgePointId).then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoint(spaceId, knowledgePointId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.knowledgePoints(spaceId),
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

  /** Generated ids are optional — only finite positive ids resolve. */
  const isPositiveId = (value: unknown): value is number =>
    typeof value === 'number' && Number.isFinite(value) && value > 0;

  const categoryNameFor = (categoryId: number): string => {
    const categories = categoriesQuery.data ?? [];
    const found = categories.find((category) => category.id === categoryId);
    return found?.name ?? `#${categoryId}`;
  };

  return (
    <div className="page">
      <div className="page__header">
        <h1 className="page__title">{point.title ?? 'Untitled'}</h1>
        <div className="page__actions">
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
        </div>
      </div>

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
