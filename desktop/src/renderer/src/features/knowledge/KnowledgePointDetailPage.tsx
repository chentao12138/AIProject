/**
 * KnowledgePoint Detail + Publish flow (FE-001 PHASE I5/I6,
 * FE-002A PHASE 19 provenance).
 *
 * DRAFT -> shows a single Publish action (publishKnowledgePoint).
 * PUBLISHED -> shows a Published badge; no repeated publish CTA
 * (backend publish is idempotent, but the UI must not fabricate
 * repeat requests).
 *
 * Provenance: BUSINESS-007 stable links (listKnowledgePointSources)
 * are shown when the contract exposes them. Navigation only for
 * positive source ids that have a real route.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery, spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
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
  const spaceId = useSpaceId();
  const { knowledgePointId: pointIdParam } = useParams();

  const knowledgePointId = parsePositiveIdParam(pointIdParam);
  const idsValid = spaceId !== null && knowledgePointId !== null;

  const pointQuery = useQuery(
    entityQuery(
      spaceId,
      knowledgePointId,
      queryKeys.knowledgePoint(spaceId, knowledgePointId),
      (id, point) => api.getKnowledgePoint(id, point)
    )
  );

  // Category name resolution (review fix #5): reuse the catalog query
  // key so arriving from the Knowledge list hits the warm cache. A
  // failing category query must never take the detail page down —
  // resolution degrades to "#<id>" instead.
  const categoriesQuery = useQuery({
    ...spaceQuery(
      spaceId,
      queryKeys.knowledgeCategories(spaceId),
      (id) => api.listKnowledgeCategories(id)
    ),
    enabled: idsValid,
  });

  // BUSINESS-007 provenance links. Failure never blocks the detail page.
  const provenanceQuery = useQuery(
    entityQuery(
      spaceId,
      knowledgePointId,
      queryKeys.knowledgePointSources(spaceId, knowledgePointId),
      (id, point) => api.listKnowledgePointSources(id, point)
    )
  );

  const publish = useMutation({
    mutationFn: () =>
      api
        .publishKnowledgePoint(
          requireId(spaceId, 'space id'),
          requireId(knowledgePointId, 'knowledge point id')
        )
        .then(unwrap),
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
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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

  const provenanceLinks = provenanceQuery.data ?? [];

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

      <section className="workbench-section" aria-label="Provenance">
        <h2 className="section-title">Provenance</h2>
        {provenanceQuery.isPending && (
          <p className="muted">Loading sources…</p>
        )}
        {provenanceQuery.isError && (
          <p className="muted">Provenance unavailable.</p>
        )}
        {provenanceQuery.isSuccess && provenanceLinks.length === 0 && (
          <p className="muted">No linked source content yet.</p>
        )}
        {provenanceQuery.isSuccess && provenanceLinks.length > 0 && (
          <ul className="point-list">
            {provenanceLinks.map((link, index) => {
              const blockId = link.contentBlockId;
              const sourceId = (link as unknown as { sourceId?: number }).sourceId;
              const sourcePageId = (link as unknown as { sourcePageId?: number }).sourcePageId;
              const hasSourceNav =
                isPositiveId(sourceId) && isPositiveId(blockId);
              // Built as a real query string: `?page=` is optional, so the
              // old `&block=` concatenation produced `/sources/5&block=9`
              // whenever a link had no sourcePageId — an unreadable source
              // id that landed on not-found.
              const deepLink = new URLSearchParams();
              if (isPositiveId(sourcePageId)) {
                deepLink.set('page', String(sourcePageId));
              }
              if (isPositiveId(blockId)) {
                deepLink.set('block', String(blockId));
              }
              const suffix = deepLink.size > 0 ? `?${deepLink.toString()}` : '';
              return (
                <li key={link.id ?? `prov-${index}`}>
                  {hasSourceNav ? (
                    <Link
                      to={`/spaces/${spaceId}/sources/${sourceId}${suffix}`}
                      className="nav-link"
                    >
                      Content block #{blockId}
                    </Link>
                  ) : (
                    /* Provenance without a resolvable source cannot be
                       navigated: the reader needs both ids. Linking to the
                       plain source list (as before) was a dead end dressed
                       up as a deep link. */
                    <span className="muted">
                      Content block #{blockId ?? '—'}
                    </span>
                  )}
                  {link.relationType ? ` · ${link.relationType}` : ''}
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
