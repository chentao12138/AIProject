/**
 * Search Page (A7.2 + A7.3).
 *
 * /spaces/:spaceId/search?q=...
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import { isPositiveId } from '../../lib/ids';
import { resultTypeLabel } from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import type { SearchPageResult } from '../../lib/types';


const PAGE_SIZE = 20;

export function SearchPage() {
  const api = useApiClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const spaceId = useSpaceId();

  const rawQuery = searchParams.get('q') ?? '';
  const types = searchParams.get('types') || undefined;
  const page = Math.max(0, Number(searchParams.get('page') ?? '0') || 0);
  const [localQuery, setLocalQuery] = useState(rawQuery);

  // Key and request read the very same values: a key without `types`/
  // `page`/`size` made page 2 overwrite page 1's cache entry.
  const searchQuery = useQuery({
    ...spaceQuery<SearchPageResult>(
      spaceId,
      queryKeys.search(spaceId, rawQuery, {
        types,
        page,
        size: PAGE_SIZE,
      }),
      (id) =>
        api.search(id, {
          q: rawQuery,
          types,
          page,
          size: PAGE_SIZE,
        })
    ),
    // No query typed yet is not a search: keep the request from firing.
    enabled: spaceId !== null && rawQuery.trim().length > 0,
  });

  const results = searchQuery.data?.content ?? [];
  const total = searchQuery.data?.totalElements ?? 0;
  const totalPages = searchQuery.data?.totalPages ?? 0;

  // A disabled query is `isPending` with `fetchStatus: 'idle'`, so `isPending`
  // alone would paint "searching…" on a page that never searched anything.
  const searching =
    searchQuery.isPending && searchQuery.fetchStatus !== 'idle';

  function doSearch() {
    const trimmed = localQuery.trim();
    const next = new URLSearchParams();
    if (trimmed) next.set('q', trimmed);
    if (types) next.set('types', types);
    next.set('page', '0');
    setSearchParams(next, { replace: true });
  }

  function goPage(p: number) {
    const next = new URLSearchParams(searchParams);
    next.set('page', String(p));
    setSearchParams(next, { replace: true });
  }

  return (
    <div className="page">
      <PageHeader title="搜索" />
      <section className="workbench-section" aria-label="搜索">
        <h2 className="section-title">搜索</h2>
        <form
          className="form"
          onSubmit={(e) => {
            e.preventDefault();
            doSearch();
          }}
        >
          <div className="form__field">
            <label htmlFor="s-q" className="form__label">关键词</label>
            <input
              id="s-q"
              className="input"
              value={localQuery}
              onChange={(e) => setLocalQuery(e.target.value)}
              placeholder="搜索学习来源、知识、题目、笔记…"
            />
          </div>
          <div className="form__actions">
            <Button type="submit" variant="primary">搜索</Button>
          </div>
        </form>
      </section>

      {searching && <LoadingState text="搜索中…" />}
      {searchQuery.isError && (
        <ErrorState
          message={normalizeApiError(searchQuery.error).message}
          onRetry={() => void searchQuery.refetch()}
        />
      )}
      {!searching && !searchQuery.isError && rawQuery && results.length === 0 && (
        <EmptyState title="没有匹配结果" description={`没有与“${rawQuery}”匹配的内容。`} />
      )}
      {!searching && !searchQuery.isError && results.length > 0 && (
        <>
          <p className="page__note">共 {total} 条结果</p>
          <ul className="point-list">
            {results.map((r, idx) => {
              const rType = r.type ?? 'UNKNOWN';
              let href: string | undefined;
              if (rType === 'SOURCE' && isPositiveId(r.sourceId)) {
                href = `/spaces/${spaceId}/sources/${r.sourceId}`;
              } else if (rType === 'CONTENT_BLOCK' && isPositiveId(r.sourceId) && isPositiveId(r.sourcePageId)) {
                href = `/spaces/${spaceId}/sources/${r.sourceId}?page=${r.sourcePageId}&block=${r.id}`;
              } else if (rType === 'KNOWLEDGE_POINT' && isPositiveId(r.knowledgePointId)) {
                href = `/spaces/${spaceId}/knowledge/${r.knowledgePointId}`;
              } else if (rType === 'QUESTION' && isPositiveId(r.questionId)) {
                href = `/spaces/${spaceId}/questions/${r.questionId}`;
              }
              return (
                <li key={r.id ?? `r-${idx}`}>
                  {href ? (
                    <Link to={href} className="nav-link">{r.title ?? `#${r.id}`}</Link>
                  ) : (
                    <span>{r.title ?? `#${r.id}`}</span>
                  )}
                  <span className="muted"> · {resultTypeLabel(rType)}</span>
                  {r.snippet && <p className="muted">{r.snippet.slice(0, 120)}</p>}
                </li>
              );
            })}
          </ul>
          {totalPages > 1 && (
            <div className="practice-nav">
              <Button type="button" onClick={() => goPage(page - 1)} disabled={page <= 0}>上一页</Button>
              <span className="muted">第 {page + 1} / {totalPages} 页</span>
              <Button type="button" onClick={() => goPage(page + 1)} disabled={page >= totalPages - 1}>下一页</Button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
