/**
 * Search result contract vocabulary + deep links (A7.1 / A7.2 / A7.3, W13).
 *
 * Split out of `SearchPage.tsx` so the page component stays a component-only
 * module (Fast Refresh) and so the contract mapping is unit-testable on its
 * own. Everything here is pinned to
 * `packages/api-client/src/generated/openapi.json` — no invented fields.
 *
 * WHICH ID OPENS WHAT (read off the server, not guessed):
 * `server/src/main/resources/mapper/SearchMapper.xml` builds one UNION branch
 * per type, and `search/dto/SearchResult.from()` copies `entity_id` into the
 * contract's `id`. The columns each branch actually fills are the only ones a
 * link may be built from:
 *
 *   SOURCE           `s.id AS entity_id` (:86)   + `s.id AS source_id` (:99)
 *   CONTENT_BLOCK    `cb.id AS entity_id` (:127) + source_id (:142) /
 *                    source_asset_id (:143) / source_page_id (:144) /
 *                    page_number (:145)
 *   KNOWLEDGE_POINT  `kp.id AS entity_id` (:175) + `kp.id AS knowledge_point_id` (:198)
 *   QUESTION         `q.id AS entity_id` (:225)  + `q.id AS question_id` (:243)
 *   WRONG_QUESTION   `wq.id AS entity_id` (:267) + `wq.question_id AS question_id` (:285)
 *   NOTE             `n.id AS entity_id` (:312)  + `n.id AS note_id` (:331)
 *
 * WRONG_QUESTION is the trap this mapping exists to avoid: its `id` is the
 * wrong_question ROW id, which no route in this app reads, so it opens its
 * question through `questionId` — never through `id`.
 *
 * The line numbers above are the search branch on `main` (the backend the
 * generated contract was recorded from); the checked-out `server/` copy in this
 * worktree predates the NOTE branch and has no `note_id` column at all, which
 * is exactly why the NOTE route also accepts the equivalent `id`.
 */

import { isPositiveId } from '../../lib/ids';
import { resultTypeLabel } from '../../lib/format';
import type { SearchPageResult } from '../../lib/types';

/** One search hit, typed off the generated contract instead of hand-copied. */
export type SearchHit = NonNullable<SearchPageResult['content']>[number];

/** Alias the page reads better at its call sites. */
export type SearchResult = SearchHit;

/**
 * `SearchResult.type` exactly as the generated contract declares it — the
 * union codegen emitted from the openapi enum. This module derives its lists
 * from this type, so a name the server does not have cannot be written here
 * and a name the server adds cannot be silently ignored.
 */
type ContractResultType = NonNullable<SearchHit['type']>;

/**
 * Display copy for every contract result type. Keyed by the generated union,
 * which makes it the single source of the filter vocabulary: the compiler
 * rejects a missing entry (so a new contract type cannot be offered without
 * Chinese copy) and an extra one (so a name the server would answer 400 for
 * cannot be listed).
 */
const SEARCH_TYPE_LABELS: Record<ContractResultType, string> = {
  SOURCE: '学习来源',
  CONTENT_BLOCK: '正文片段',
  KNOWLEDGE_POINT: '知识点',
  QUESTION: '题目',
  WRONG_QUESTION: '错题',
  NOTE: '笔记',
};

/**
 * The contract's result types, in the order the enum lists them (the label
 * record above is written in that order), derived from
 * {@link SEARCH_TYPE_LABELS} rather than copied out of openapi.json — the spec
 * itself is 169 KB and never enters the renderer bundle.
 * `searchContract.test.ts` pins this list against `SearchResult.type.enum` and
 * the `types` parameter pattern read straight off the generated JSON, so drift
 * fails a test as well as the compiler. The filter UI, the URL, the cache key
 * and the request all read this one list.
 */
export const SEARCH_RESULT_TYPES: readonly ContractResultType[] = Object.freeze(
  Object.keys(SEARCH_TYPE_LABELS) as ContractResultType[]
);

export type SearchResultType = (typeof SEARCH_RESULT_TYPES)[number];

/** The contract type behind a raw payload/URL token, or `undefined`. */
function contractTypeOf(token: string): SearchResultType | undefined {
  return SEARCH_RESULT_TYPES.find((type) => type === token);
}

export function searchTypeLabel(type?: string | null): string {
  if (!type) {
    return '—';
  }
  const key = type.trim().toUpperCase();
  const known = contractTypeOf(key);
  // A type newer than this build still gets the shared fallback copy instead
  // of an empty chip — and, further down, no link.
  return known ? SEARCH_TYPE_LABELS[known] : resultTypeLabel(key);
}

/**
 * Parse `?types=` down to the contract enum: unknown values are dropped
 * rather than forwarded (one bad value makes the server reject the whole
 * request), duplicates collapse, and the result is ordered by the contract so
 * the URL, the cache key and the request always carry one identical string.
 *
 * An empty result means "no filter", which is what the endpoint does with an
 * ABSENT `types`: `search/service/MySqlSearchService.java` (:100-101 in this
 * checkout, :157-159 on main) turns null / blank into an empty list, and every
 * UNION branch in `resources/mapper/SearchMapper.xml` is guarded by
 * `types == null || types.isEmpty() || types.contains(...)` (:20/47/77/115/143
 * /173 here, :83/124/172/222/264/309 on main) — so an empty filter runs every
 * branch, i.e. all types. The client must therefore send nothing rather than a
 * made-up default.
 */
export function parseSearchTypes(
  raw: string | null | undefined
): SearchResultType[] {
  if (!raw) {
    return [];
  }
  const picked = new Set<SearchResultType>();
  for (const part of raw.split(',')) {
    const key = contractTypeOf(part.trim().toUpperCase());
    if (key) {
      picked.add(key);
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

/** The first candidate that is a real entity id, or `null`. */
function firstPositiveId(...candidates: Array<number | undefined | null>): number | null {
  for (const candidate of candidates) {
    if (isPositiveId(candidate)) {
      return candidate;
    }
  }
  return null;
}

/**
 * The workbench reader. `?page=` is the source PAGE ROW id the reader selects
 * on (`SourceWorkbenchPage.tsx` reads `page` and `block` off the URL) and
 * `?block=` is the content block to highlight — the same convention Knowledge
 * provenance already uses. Note that `pageNumber` is a 1-based DOCUMENT page
 * number, not that id, so it is never used to build a link, and
 * `sourceAssetId` has no reader parameter at all.
 */
function readerHref(
  spaceId: number,
  sourceId: number,
  deepLink?: { pageId?: number; blockId?: number }
): string {
  const pageId = deepLink?.pageId;
  const blockId = deepLink?.blockId;
  const params = new URLSearchParams();
  if (isPositiveId(pageId)) {
    params.set('page', String(pageId));
    // A block without a page to look in is meaningless to the reader.
    if (isPositiveId(blockId)) {
      params.set('block', String(blockId));
    }
  }
  const query = params.toString();
  return `/spaces/${spaceId}/sources/${sourceId}${query ? `?${query}` : ''}`;
}

/**
 * One route per contract result type, keyed by the generated union so the
 * compiler forces an entry for every type the server can return (a type with
 * no entry here is a build error, not a silently unopenable result — W13 /
 * A7.1). A builder returns `null` whenever the ids this row type actually
 * carries are missing: not-navigable beats a plausible-but-wrong destination.
 */
const SEARCH_RESULT_ROUTES: Record<
  SearchResultType,
  (spaceId: number, hit: SearchHit) => string | null
> = {
  SOURCE: (spaceId, hit) =>
    // `sourceId` is the Source's own id for these rows (SearchMapper.xml:99).
    isPositiveId(hit.sourceId) ? readerHref(spaceId, hit.sourceId) : null,

  CONTENT_BLOCK: (spaceId, hit) => {
    if (!isPositiveId(hit.sourceId)) {
      return null;
    }
    // Without a page id there is nothing to deep-link; the block id alone
    // cannot select a page. Still a valid Source, so it opens at the top.
    if (!isPositiveId(hit.sourcePageId)) {
      return readerHref(spaceId, hit.sourceId);
    }
    // `id` IS the content block for these rows (SearchMapper.xml:127).
    return readerHref(spaceId, hit.sourceId, {
      pageId: hit.sourcePageId,
      blockId: hit.id,
    });
  },

  KNOWLEDGE_POINT: (spaceId, hit) =>
    isPositiveId(hit.knowledgePointId)
      ? `/spaces/${spaceId}/knowledge/${hit.knowledgePointId}`
      : null,

  QUESTION: (spaceId, hit) =>
    isPositiveId(hit.questionId)
      ? `/spaces/${spaceId}/questions/${hit.questionId}`
      : null,

  WRONG_QUESTION: (spaceId, hit) =>
    // `id` here is the wrong_question row (SearchMapper.xml:267) and the app
    // has no wrong-question route (and none may be invented), so the hit opens
    // the question it was recorded against via `questionId`.
    isPositiveId(hit.questionId)
      ? `/spaces/${spaceId}/questions/${hit.questionId}`
      : null,

  NOTE: (spaceId, hit) => {
    // `noteId` is the field the contract added for this ("A NOTE hit carries
    // noteId so the client can open it directly" — the `/search` operation
    // description; `SearchResult.java:31` on main), and the server fills it
    // with the same `n.id` it puts in `entity_id` (SearchMapper.xml:312 / :331,
    // copied by `SearchResult.from()` at :95 and :105). So `noteId` wins and
    // `id` is its documented equivalent — the fallback keeps a NOTE hit
    // openable against a backend that has not shipped the column yet, and it
    // is not a guess: for NOTE rows `id` IS the note id.
    const noteId = firstPositiveId(hit.noteId, hit.id);
    return noteId === null ? null : `/spaces/${spaceId}/notes/${noteId}`;
  },
};

/**
 * Where a hit opens (A7.3 / W13), built only from the fields `SearchResult`
 * actually carries: {type,id,title,snippet,score,sourceId,sourceAssetId,
 * sourcePageId,pageNumber,knowledgePointId,questionId,noteId}. Nothing is
 * linked from a field the payload did not send, and no route is used that the
 * app does not have.
 */
export function searchResultHref(
  spaceId: number,
  hit: SearchHit
): string | null {
  const type = contractTypeOf((hit.type ?? '').trim().toUpperCase());
  if (!type) {
    // A type this build does not know, or a row with no type at all.
    return null;
  }
  return SEARCH_RESULT_ROUTES[type](spaceId, hit);
}
