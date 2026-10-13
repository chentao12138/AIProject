/**
 * Search contract vocabulary + deep-link mapping (W13).
 *
 * These assertions are pinned to
 * `packages/api-client/src/generated/openapi.json`: `SearchResult.type` is
 * ['SOURCE','CONTENT_BLOCK','KNOWLEDGE_POINT','QUESTION','WRONG_QUESTION'] and
 * the `types` query parameter accepts exactly that comma-separated pattern.
 * If the enum drifts, this test fails before the UI can send a 400.
 */

import { describe, expect, it } from 'vitest';
import {
  SEARCH_RESULT_TYPES,
  parseSearchPage,
  parseSearchTypes,
  searchResultHref,
  searchTypeLabel,
  type SearchHit,
} from './searchContract';

const SPACE = 7;

/**
 * A hit with only the fields the contract declares for `SearchResult`.
 * `type` is widened so a test can pass a value the enum does not carry yet
 * (`NOTE`) — that is exactly what a future backend would deliver at runtime.
 */
function hit(
  partial: Omit<Partial<SearchHit>, 'type'> & { type?: string }
): SearchHit {
  return partial as SearchHit;
}

describe('SEARCH_RESULT_TYPES', () => {
  it('matches the openapi SearchResult.type enum exactly', () => {
    expect([...SEARCH_RESULT_TYPES].sort()).toEqual(
      [
        'CONTENT_BLOCK',
        'KNOWLEDGE_POINT',
        'QUESTION',
        'SOURCE',
        'WRONG_QUESTION',
      ].sort()
    );
  });

  it('does not offer NOTE — the type is not in the contract yet (C01)', () => {
    expect(SEARCH_RESULT_TYPES).not.toContain('NOTE');
  });
});

describe('parseSearchTypes', () => {
  it('keeps contract values in contract order', () => {
    expect(parseSearchTypes('QUESTION,SOURCE')).toEqual(['SOURCE', 'QUESTION']);
  });

  it('drops values the server would reject instead of forwarding them', () => {
    // `types=NOTE` alone matches nothing in the pattern → 400 for the request.
    expect(parseSearchTypes('BOGUS,SOURCE,NOTE')).toEqual(['SOURCE']);
    expect(parseSearchTypes('NOTE')).toEqual([]);
  });

  it('collapses duplicates and tolerates spacing and case', () => {
    expect(parseSearchTypes(' source , SOURCE ,source')).toEqual(['SOURCE']);
  });

  it('returns [] for a missing or empty param', () => {
    expect(parseSearchTypes(null)).toEqual([]);
    expect(parseSearchTypes('')).toEqual([]);
    expect(parseSearchTypes(',,')).toEqual([]);
  });
});

describe('parseSearchPage', () => {
  it('parses a 0-based page index', () => {
    expect(parseSearchPage('2')).toBe(2);
    expect(parseSearchPage('0')).toBe(0);
  });

  it('falls back to the first page for signed, float, garbage or huge values', () => {
    expect(parseSearchPage('-1')).toBe(0);
    expect(parseSearchPage('1.5')).toBe(0);
    expect(parseSearchPage('abc')).toBe(0);
    expect(parseSearchPage('9'.repeat(20))).toBe(0);
    expect(parseSearchPage(null)).toBe(0);
  });
});

describe('searchTypeLabel', () => {
  it('labels every contract type in Chinese', () => {
    for (const type of SEARCH_RESULT_TYPES) {
      expect(searchTypeLabel(type)).toBeTruthy();
      expect(searchTypeLabel(type)).not.toBe(type);
    }
  });

  it('labels WRONG_QUESTION apart from QUESTION', () => {
    expect(searchTypeLabel('WRONG_QUESTION')).toBe('错题');
    expect(searchTypeLabel('QUESTION')).toBe('题目');
  });

  it('marks NOTE as pending contract copy instead of pretending it exists', () => {
    expect(searchTypeLabel('NOTE')).toContain('笔记');
    expect(searchTypeLabel('NOTE')).toContain('C01');
  });

  it('falls back for unknown types without crashing', () => {
    expect(searchTypeLabel('')).toBe('—');
    expect(searchTypeLabel(undefined)).toBe('—');
    expect(searchTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

describe('searchResultHref', () => {
  it('SOURCE opens the Source workbench', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'SOURCE', sourceId: 5, id: 5 }))
    ).toBe('/spaces/7/sources/5');
  });

  it('SOURCE without sourceId has no link', () => {
    expect(searchResultHref(SPACE, hit({ type: 'SOURCE', id: 5 }))).toBeNull();
    expect(
      searchResultHref(SPACE, hit({ type: 'SOURCE', sourceId: 0 }))
    ).toBeNull();
  });

  it('CONTENT_BLOCK deep-links the page and highlights the block', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'CONTENT_BLOCK', id: 31, sourceId: 5, sourcePageId: 20 })
      )
    ).toBe('/spaces/7/sources/5?page=20&block=31');
  });

  it('CONTENT_BLOCK without a page id still opens the Source, minus the deep link', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'CONTENT_BLOCK', id: 31, sourceId: 5 }))
    ).toBe('/spaces/7/sources/5');
  });

  it('CONTENT_BLOCK without a sourceId has no link, even with a page id', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'CONTENT_BLOCK', id: 31, sourcePageId: 20 })
      )
    ).toBeNull();
  });

  it('KNOWLEDGE_POINT uses knowledgePointId, not the hit id', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'KNOWLEDGE_POINT', id: 99, knowledgePointId: 10 })
      )
    ).toBe('/spaces/7/knowledge/10');
    expect(searchResultHref(SPACE, hit({ type: 'KNOWLEDGE_POINT', id: 99 }))).toBeNull();
  });

  it('QUESTION uses questionId and never the hit id', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'QUESTION', id: 99, questionId: 12 }))
    ).toBe('/spaces/7/questions/12');
    expect(searchResultHref(SPACE, hit({ type: 'QUESTION', id: 99 }))).toBeNull();
  });

  it('WRONG_QUESTION opens its question detail — the app has no wrong-question route', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'WRONG_QUESTION', id: 99, questionId: 12 })
      )
    ).toBe('/spaces/7/questions/12');
    expect(
      searchResultHref(SPACE, hit({ type: 'WRONG_QUESTION', id: 99 }))
    ).toBeNull();
  });

  it('NOTE and unknown types get no route, because none exists yet', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'NOTE', id: 3 }))
    ).toBeNull();
    expect(
      searchResultHref(SPACE, hit({ type: 'FUTURE_TYPE', id: 3 }))
    ).toBeNull();
    expect(searchResultHref(SPACE, hit({ id: 3 }))).toBeNull();
  });

  it('never builds a link out of a non-integer or missing space scope', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'SOURCE', sourceId: 1.5 }))
    ).toBeNull();
  });
});
