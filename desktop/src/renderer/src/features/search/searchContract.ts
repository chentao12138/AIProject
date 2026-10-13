/**
 * Search result contract vocabulary + deep links (A7.1 / A7.3, W13).
 *
 * Split out of `SearchPage.tsx` so the page component stays a component-only
 * module (Fast Refresh) and so the contract mapping is unit-testable on its
 * own. Everything here is pinned to
 * `packages/api-client/src/generated/openapi.json` — no invented fields.
 */

import { isPositiveId } from '../../lib/ids';
import { resultTypeLabel } from '../../lib/format';
import type { SearchPageResult } from '../../lib/types';

/** One search hit, typed off the generated contract instead of hand-copied. */
export type SearchHit = NonNullable<SearchPageResult['content']>[number];

/** Alias the page reads better at its call sites. */
export type SearchResult = SearchHit;

/**
 * `GET /search` -> `SearchResult.type`, verbatim from the contract enum, and
 * the same list the `types` query parameter pattern accepts
 * (`^(SOURCE|CONTENT_BLOCK|KNOWLEDGE_POINT|QUESTION|WRONG_QUESTION)(,...)*$`).
 * The filter UI, the URL and the request all read this one list, so a value
 * outside the pattern — which the server answers with 400 — can never be sent.
 */
export const SEARCH_RESULT_TYPES = [
  'SOURCE',
  'CONTENT_BLOCK',
  'KNOWLEDGE_POINT',
  'QUESTION',
  'WRONG_QUESTION',
] as const;

export type SearchResultType = (typeof SEARCH_RESULT_TYPES)[number];

const SEARCH_TYPE_LABELS: Record<SearchResultType, string> = {
  SOURCE: '学习来源',
  CONTENT_BLOCK: '正文片段',
  KNOWLEDGE_POINT: '知识点',
  QUESTION: '题目',
  WRONG_QUESTION: '错题',
};

/**
 * `NOTE` is NOT in the contract enum yet — it is the C01 / C-Search ask in
 * BACKEND-CONTRACT-REQUESTS.md. Until the backend ships it, it is offered in
 * neither the filter nor the request, and a hit of that type would only get a
 * label plus no link: the app has no notes route, so none is invented.
 */
const RESERVED_TYPE_LABELS: Record<string, string> = {
  NOTE: '笔记（等待后端合同 C01）',
};

export function searchTypeLabel(type?: string | null): string {
  if (!type) {
    return '—';
  }
  const key = type.trim().toUpperCase();
  return (
    SEARCH_TYPE_LABELS[key as SearchResultType] ??
    RESERVED_TYPE_LABELS[key] ??
    resultTypeLabel(key)
  );
}

/**
 * Parse `?types=` down to the contract enum: unknown values are dropped
 * rather than forwarded (one bad value makes the server reject the whole
 * request), duplicates collapse, and the result is ordered by the contract so
 * the URL, the cache key and the request always carry one identical string.
 */
export function parseSearchTypes(
  raw: string | null | undefined
): SearchResultType[] {
  if (!raw) {
    return [];
  }
  const picked = new Set<SearchResultType>();
  for (const part of raw.split(',')) {
    const key = part.trim().toUpperCase();
    if ((SEARCH_RESULT_TYPES as readonly string[]).includes(key)) {
      picked.add(key as SearchResultType);
    }
  }
  return SEARCH_RESULT_TYPES.filter((type) => picked.has(type));
}

/** `?page=` -> a 0-based page index. Garbage, negatives and 0 all mean 0. */
export function parseSearchPage(raw: string | null | undefined): number {
  const trimmed = (raw ?? '').trim();
  if (!/^\d+$/.test(trimmed)) {
    return 0;
  }
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) ? parsed : 0;
}

/**
 * Where a hit opens (A7.3 / W13), built only from the fields `SearchResult`
 * actually carries: {type,id,title,snippet,score,sourceId,sourceAssetId,
 * sourcePageId,pageNumber,knowledgePointId,questionId}. Nothing is linked
 * from a field the payload did not send, and no route is used that the app
 * does not have.
 */
export function searchResultHref(
  spaceId: number,
  hit: SearchHit
): string | null {
  switch ((hit.type ?? '').trim().toUpperCase()) {
    case 'SOURCE':
      return isPositiveId(hit.sourceId)
        ? `/spaces/${spaceId}/sources/${hit.sourceId}`
        : null;
    case 'CONTENT_BLOCK': {
      if (!isPositiveId(hit.sourceId)) {
        return null;
      }
      // The workbench reader reads `?page=<sourcePageId>&block=<contentBlockId>`
      // — the same convention Knowledge provenance already uses. When the
      // payload omits the page id there is nothing to deep-link: `pageNumber`
      // is a 1-based document page number, not the `SourcePageResponse.id` the
      // reader selects on, so guessing one from the other is not possible.
      if (!isPositiveId(hit.sourcePageId)) {
        // A valid Source, just without a page or block target.
        return `/spaces/${spaceId}/sources/${hit.sourceId}`;
      }
      const params = new URLSearchParams({ page: String(hit.sourcePageId) });
      if (isPositiveId(hit.id)) {
        params.set('block', String(hit.id));
      }
      return `/spaces/${spaceId}/sources/${hit.sourceId}?${params.toString()}`;
    }
    case 'KNOWLEDGE_POINT':
      return isPositiveId(hit.knowledgePointId)
        ? `/spaces/${spaceId}/knowledge/${hit.knowledgePointId}`
        : null;
    case 'QUESTION':
    case 'WRONG_QUESTION':
      // The app has no wrong-question route (and none may be invented), so a
      // wrong-question hit opens its question via `questionId`.
      return isPositiveId(hit.questionId)
        ? `/spaces/${spaceId}/questions/${hit.questionId}`
        : null;
    default:
      // NOTE and any type the contract grows later: reserved, not faked.
      return null;
  }
}
