/**
 * Search Page (A7.2 + A7.3).
 *
 * /spaces/:spaceId/search?q=...&types=SOURCE,QUESTION&page=1
 *
 * W12: the URL search params are the SINGLE source of truth for the
 * committed keyword, the result-type filter and the page. The text box is a
 * draft that is re-synced every time `?q=` changes, so browser back/forward,
 * a deep link or a refresh can never leave it showing one search while the
 * list (and the request behind it) carries another. `useState(rawQuery)` —
 * initialized once and never revisited — is exactly what used to break that.
 *
 * W13: the result-type filter is built from the contract enum (see
 * `searchContract.ts`) and its selection is written back to the URL. Nothing
 * checked is not "nothing searched": the endpoint treats an absent `types` as
 * every type, so an empty filter sends no `types` at all rather than a
 * client-invented default.
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import {
  SEARCH_RESULT_TYPES,
  parseSearchPage,
  parseSearchTypes,
  searchResultHref,
  searchTypeLabel,
  type SearchResultType,
} from './searchContract';
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
  const selectedTypes = parseSearchTypes(searchParams.get('types'));
  const typesParam = selectedTypes.join(',');
  const page = parseSearchPage(searchParams.get('page'));

  const [draftQuery, setDraftQuery] = useState(rawQuery);
  const [urlQuery, setUrlQuery] = useState(rawQuery);

  // W12: `?q=` moved underneath the component (back/forward, a link from
  // another page, a refresh). The draft follows the URL instead of keeping
  // whatever it was mounted with. Done during render rather than in an
  // effect so there is never a painted frame where the box and the results
  // describe different searches.
  if (urlQuery !== rawQuery) {
    setUrlQuery(rawQuery);
    setDraftQuery(rawQuery);
  }

  // Key and request read the very same values: a key without `types`/
  // `page`/`size` made page 2 overwrite page 1's cache entry.
  const searchQuery = useQuery({
    ...spaceQuery<SearchPageResult>(
      spaceId,
      queryKeys.search(spaceId, rawQuery, {
        types: typesParam || undefined,
        page,
        size: PAGE_SIZE,
      }),
      (id) =>
        api.search(id, {
          q: rawQuery,
          types: typesParam || undefined,
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

  /**
   * The only way this page changes state: write the URL, and the request, the
   * text box and the pager all follow it. Pushed rather than replaced — the
   * URL *is* the state here, so browser back/forward has to walk through the
   * searches the user actually performed.
   */
  function commitUrl(next: { q: string; types: string; page: number }) {
    const merged = new URLSearchParams(searchParams);
    if (next.q) {
      merged.set('q', next.q);
    } else {
      merged.delete('q');
    }
    if (next.types) {
      merged.set('types', next.types);
    } else {
      merged.delete('types');
    }
    if (next.page > 0) {
      merged.set('page', String(next.page));
    } else {
      merged.delete('page');
    }
    setSearchParams(merged);
  }

  function submitSearch() {
    commitUrl({ q: draftQuery.trim(), types: typesParam, page: 0 });
  }

  /**
   * Write a type selection into the URL, and the request, the checkboxes and
   * the pager follow it.
   */
  function commitTypes(picked: readonly SearchResultType[]) {
    // Re-canonicalize so a click never leaves the URL holding a permutation of
    // what the next click produces, and restart at page 0 — page 3 of a
    // narrower result set is usually nothing at all.
    commitUrl({
      q: rawQuery,
      types: SEARCH_RESULT_TYPES.filter((type) => picked.includes(type)).join(','),
      page: 0,
    });
  }

  function toggleType(type: SearchResultType) {
    commitTypes(
      selectedTypes.includes(type)
        ? selectedTypes.filter((t) => t !== type)
        : [...selectedTypes, type]
    );
  }

  function goPage(nextPage: number) {
    commitUrl({ q: rawQuery, types: typesParam, page: nextPage });
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
            submitSearch();
          }}
        >
          <div className="form__field">
            <label htmlFor="s-q" className="form__label">关键词</label>
            <input
              id="s-q"
              className="input"
              value={draftQuery}
              onChange={(e) => setDraftQuery(e.target.value)}
              placeholder="搜索学习来源、知识、题目、笔记…"
            />
          </div>
          <fieldset className="form__field">
            <legend className="form__label">结果类型</legend>
            <div className="content-nav">
              {SEARCH_RESULT_TYPES.map((type) => (
                <label key={type} className="nav-link" htmlFor={`s-type-${type}`}>
                  <input
                    id={`s-type-${type}`}
                    type="checkbox"
                    checked={selectedTypes.includes(type)}
                    onChange={() => toggleType(type)}
                  />
                  <span>{searchTypeLabel(type)}</span>
                </label>
              ))}
            </div>
            {/* A7.2: this is the endpoint's own default, not a client-side one —
                with no `types` on the wire every UNION branch runs
                (`MySqlSearchService.normalizedTypes()` -> empty list ->
                `types.isEmpty() || types.contains(...)` in SearchMapper.xml), so
                an empty filter means "all types" and is said that way instead
                of looking like a search that can return nothing. */}
            <p className="field__hint" data-testid="types-filter-hint">
              {selectedTypes.length === 0
                ? '未勾选任何类型：搜索全部类型（请求不带 types）'
                : `只搜索已勾选的 ${selectedTypes.length} 种类型`}
            </p>
            {selectedTypes.length > 0 && (
              <Button type="button" variant="ghost" onClick={() => commitTypes([])}>
                全部类型
              </Button>
            )}
          </fieldset>
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
              const rType = (r.type ?? 'UNKNOWN').trim().toUpperCase();
              const href =
                spaceId === null ? null : searchResultHref(spaceId, r);
              return (
                <li key={r.id ?? `r-${idx}`}>
                  {href ? (
                    <Link to={href} className="nav-link">{r.title ?? `#${r.id}`}</Link>
                  ) : (
                    <span>{r.title ?? `#${r.id}`}</span>
                  )}
                  <span className="muted"> · {searchTypeLabel(rType)}</span>
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
