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
 *
 * W16 cross-module shortcuts (相关学习): question list, practice start,
 * Notes prefill (`?knowledgePointId=`) and an AI-workspace entry are all
 * reachable from the point itself, with the ids this entity really has.
 * A3.2 (G07): `SendMessageRequest` now takes the four optional context ids, so
 * the AI entry carries `?attachKnowledgePointId=` — the one context id this
 * entity owns — and `AiChatPage` sends it in the body and lists it above the
 * composer. The `attach` prefix is that page's own key vocabulary, not the
 * Notes prefill keys.
 * A2.1 (G06): every provenance row that names a source deep-links into the
 * reader with the source/page/block ids `KnowledgePointSourceResponse` sends.
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
import { formatDateTime, NOTE_FORM_LIMITS, originLabel } from '../../lib/format';
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

  // C-Notes-reverse: the notes that belong to THIS point. The server matches the
  // id against both the note's own context pointer and its M:N link rows, so one
  // query covers both ways a note can be related — no `/knowledge-points/{id}/notes`
  // sub-resource was needed. Failure must not take the detail page down.
  const relatedNotesQuery = useQuery({
    ...spaceQuery(
      spaceId,
      queryKeys.notes(spaceId, {
        knowledgePointId: isPositiveId(knowledgePointId) ? knowledgePointId : undefined,
        page: 0,
        size: NOTE_FORM_LIMITS.pageSize,
      }),
      (id) =>
        api.listNotes(id, {
          knowledgePointId: requireId(knowledgePointId, 'knowledge point id'),
          page: 0,
          size: NOTE_FORM_LIMITS.pageSize,
        })
    ),
    enabled: idsValid,
  });

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
        title={point.title ?? '未命名'}
        actions={
          <>
            <StatusBadge status={point.status} />
            {isDraft && (
              <Button
                variant="primary"
                disabled={publish.isPending}
                onClick={() => publish.mutate()}
              >
                {publish.isPending ? '发布中…' : '发布'}
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
          发布时间：{formatDateTime(point.publishedAt)}
        </p>
      )}

      <dl className="detail-list">
        <div className="detail-list__row">
          <dt>状态</dt>
          <dd>
            <StatusBadge status={point.status} />
          </dd>
        </div>
        {point.originType && (
          <div className="detail-list__row">
            <dt>来源</dt>
            <dd>{originLabel(point.originType)}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>分类</dt>
          <dd>
            {isPositiveId(point.categoryId)
              ? categoryNameFor(point.categoryId)
              : '—'}
          </dd>
        </div>
        {point.difficulty && (
          <div className="detail-list__row">
            <dt>难度</dt>
            <dd>{point.difficulty}</dd>
          </div>
        )}
        {point.summary && (
          <div className="detail-list__row">
            <dt>摘要</dt>
            <dd>{point.summary}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>创建时间</dt>
          <dd>{formatDateTime(point.createdAt)}</dd>
        </div>
        <div className="detail-list__row">
          <dt>更新时间</dt>
          <dd>{formatDateTime(point.updatedAt)}</dd>
        </div>
      </dl>

      <section className="content-block">
        <h2 className="section-title">正文</h2>
        <div className="content-block__body">{point.content || '—'}</div>
      </section>

      <section className="workbench-section" aria-label="相关学习">
        <h2 className="section-title">相关学习</h2>
        {/* W16: the question list already filters by knowledgePointId from the
            URL, so arriving from a knowledge point must not require typing an
            id by hand. Only contract-supported query keys are used here. */}
        <ul className="point-list">
          <li>
            <Link
              className="nav-link"
              to={`/spaces/${spaceId}/questions?${new URLSearchParams({
                knowledgePointId: String(knowledgePointId),
              }).toString()}`}
            >
              在题库中查看相关题目
            </Link>
          </li>
          <li>
            <Link
              className="nav-link"
              to={`/spaces/${spaceId}/practice?${new URLSearchParams({
                knowledgePointId: String(knowledgePointId),
              }).toString()}`}
            >
              就此知识点开始练习
            </Link>
          </li>
          <li>
            {/* W16: `knowledgePointId` is the one context id this entity owns;
                the source/page/block trio belongs to 来源追溯 below, where the
                server supplies those ids per row. The Notes list page reads
                exactly this key (its own listing keys — `page`, `size`,
                `keyword` — are not reused for a prefill). */}
            <Link
              className="nav-link"
              data-testid="point-note-link"
              to={`/spaces/${spaceId}/notes?${new URLSearchParams({
                knowledgePointId: String(knowledgePointId),
              }).toString()}`}
            >
              就此知识点新建笔记
            </Link>
          </li>
          <li>
            {/* A3.2 / G07: this is the id the request body can really carry for
                this entity, so the AI page attaches it (`SendMessageRequest.
                knowledgePointId`) and shows it above the composer. The source /
                page / block trio belongs to the 来源追溯 rows below, where the
                server supplies those ids per row — naming one here would be a
                guess about which block the learner means. */}
            <Link
              className="nav-link"
              data-testid="point-ai-link"
              to={`/spaces/${spaceId}/ai?${new URLSearchParams({
                attachKnowledgePointId: String(knowledgePointId),
              }).toString()}`}
            >
              就此知识点问 AI
            </Link>
          </li>
        </ul>
        <p className="page__note" data-testid="ai-entry-note">
          问 AI 打开 AI 辅导，并把这个知识点附带为上下文；发送前可在输入框上方看到或取消它。
        </p>
      </section>

      <section className="workbench-section" aria-label="来源追溯">
        <h2 className="section-title">来源追溯</h2>
        {provenanceQuery.isPending && (
          <p className="muted">加载来源…</p>
        )}
        {provenanceQuery.isError && (
          <p className="muted">来源追溯暂不可用。</p>
        )}
        {provenanceQuery.isSuccess && provenanceLinks.length === 0 && (
          <p className="muted">尚未关联任何来源内容。</p>
        )}
        {provenanceQuery.isSuccess && provenanceLinks.length > 0 && (
          <ul className="point-list">
            {provenanceLinks.map((link, index) => {
              const blockId = link.contentBlockId;
              // A2.1 / G06: sourceId and sourcePageId are contract fields of
              // KnowledgePointSourceResponse now, so this reads plain fields —
              // there is no escape hatch to route them through any more.
              const { sourceId, sourcePageId } = link;
              // The reader route is `/spaces/{spaceId}/sources/{sourceId}`, so a
              // row without a usable source id has no destination at all and
              // renders as text. Linking it anyway produced a source that 404s.
              const canOpen = isPositiveId(sourceId);
              // `?page=` is the source PAGE ROW id the reader selects on
              // (SourceWorkbenchPage reads `page` off the URL), never a document
              // page number. A block with no page to look in cannot be located,
              // so `block` is only ever emitted together with `page`. Built as a
              // real query string: the old `&block=` concatenation produced
              // `/sources/5&block=9` whenever a link had no sourcePageId.
              const params = new URLSearchParams();
              if (isPositiveId(sourcePageId)) {
                params.set('page', String(sourcePageId));
                if (isPositiveId(blockId)) {
                  params.set('block', String(blockId));
                }
              }
              const query = params.toString();
              // A row may name its block, its source, or neither — the label
              // says which of the three this is, using only ids that exist.
              const label = isPositiveId(blockId)
                ? `正文片段 #${blockId}`
                : canOpen
                  ? `学习来源 #${sourceId}`
                  : '来源内容';
              return (
                <li key={link.id ?? `prov-${index}`} data-testid="provenance-row">
                  {canOpen ? (
                    <Link
                      to={`/spaces/${spaceId}/sources/${sourceId}${
                        query ? `?${query}` : ''
                      }`}
                      className="nav-link"
                      data-testid="provenance-link"
                    >
                      {label}
                    </Link>
                  ) : (
                    /* Provenance without a resolvable source cannot be
                       navigated: the reader needs the source id to exist at
                       all. Linking to the plain source list (as before) was a
                       dead end dressed up as a deep link. */
                    <span className="muted">{label}</span>
                  )}
                  {link.relationType ? ` · ${link.relationType}` : ''}
                </li>
              );
            })}
          </ul>
        )}
      </section>
      <section className="workbench-section" aria-label="关联笔记">
        <h2 className="section-title">关联笔记</h2>
        {relatedNotesQuery.isPending && <LoadingState text="加载关联笔记…" />}
        {/* A failed read is never shown as "no notes": that would tell the
            reader nothing is related when the server only failed to answer. */}
        {relatedNotesQuery.isError && (
          <div data-testid="related-notes-error">
            <ErrorState
              message={normalizeApiError(relatedNotesQuery.error).message}
              detail="无法读取与该知识点相关的笔记，本页其他内容不受影响。"
              onRetry={() => void relatedNotesQuery.refetch()}
            />
          </div>
        )}
        {relatedNotesQuery.isSuccess &&
          (relatedNotesQuery.data?.items?.length ?? 0) === 0 && (
            <p className="page__note" data-testid="related-notes-empty">
              还没有笔记引用这个知识点（正文指针与知识点的 M:N 链接都算引用）。
            </p>
          )}
        {relatedNotesQuery.isSuccess &&
          (relatedNotesQuery.data?.items?.length ?? 0) > 0 && (
            <ul className="point-list" data-testid="related-notes">
              {(relatedNotesQuery.data?.items ?? []).map((note, index) => (
                <li key={note.id ?? `note-${index}`} data-testid="related-note">
                  {isPositiveId(note.id) ? (
                    <Link
                      className="nav-link"
                      to={`/spaces/${spaceId}/notes/${note.id}`}
                    >
                      {note.title?.trim() === '' ? `笔记 #${note.id}` : note.title}
                    </Link>
                  ) : (
                    '（该笔记没有返回 ID，无法打开）'
                  )}
                  {note.archivedAt ? <span className="muted"> · 已归档</span> : null}
                </li>
              ))}
            </ul>
          )}
      </section>
    </div>
  );
}
