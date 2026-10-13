/**
 * Search contract vocabulary + deep-link mapping (A7.1 / A7.2 / A7.3, W13).
 *
 * These assertions are pinned to
 * `packages/api-client/src/generated/openapi.json`: `SearchResult.type` is its
 * own enum, the `types` query parameter accepts exactly that comma-separated
 * pattern, and `SearchResult` carries the id columns the routes below read.
 * If the enum drifts, this test fails before the UI can send a 400 — and it
 * fails for the NEW type too, because every table here is keyed by
 * `SearchResultType` and coverage of `SEARCH_RESULT_TYPES` is asserted.
 *
 * The per-type rows are shaped the way `SearchMapper.xml` shapes them (see the
 * header of `searchContract.ts` for the line numbers): the entity's own id in
 * `id`, plus only the id columns that branch actually selects. `WRONG_QUESTION`
 * and `NOTE` are the two places where a careless client 404s — the first puts
 * the wrong_question ROW id in `id`, the second puts the note id in BOTH slots.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  SEARCH_RESULT_TYPES,
  parseSearchPage,
  parseSearchTypes,
  searchResultHref,
  searchTypeLabel,
  type SearchHit,
  type SearchResultType,
} from './searchContract';

const SPACE = 7;

const CONTRACT_PATH = join(
  process.cwd(),
  '..',
  'packages',
  'api-client',
  'src',
  'generated',
  'openapi.json'
);

interface OpenApiDoc {
  paths: Record<
    string,
    {
      get?: {
        parameters?: Array<{
          name: string;
          in?: string;
          required?: boolean;
          schema?: { pattern?: string; example?: string };
        }>;
      };
    }
  >;
  components: {
    schemas: Record<
      string,
      { properties?: Record<string, { type?: string; enum?: string[] }> }
    >;
  };
}

const hasContract = existsSync(CONTRACT_PATH);
const contract = hasContract
  ? (JSON.parse(readFileSync(CONTRACT_PATH, 'utf8')) as OpenApiDoc)
  : null;

const SEARCH_PATH = '/api/v1/spaces/{spaceId}/search';

/**
 * A hit with only the fields the contract declares for `SearchResult`.
 * `type` is widened so a test can pass a value the enum does not carry yet
 * (`FUTURE_TYPE`) — that is what a backend newer than this build sends.
 */
function hit(
  partial: Omit<Partial<SearchHit>, 'type'> & { type?: string }
): SearchHit {
  return partial as SearchHit;
}

/* ─────────────── the list is the contract's ─────────────── */

describe('SEARCH_RESULT_TYPES', () => {
  it('offers NOTE, because the contract returns it', () => {
    expect(SEARCH_RESULT_TYPES).toContain('NOTE');
  });

  it('is frozen, so no page can append a type of its own', () => {
    expect(Object.isFrozen(SEARCH_RESULT_TYPES)).toBe(true);
  });

  // Skipped when the generated contract is not next to this checkout, the way
  // `lib/question-type-contract.test.ts` handles it.
  describe.skipIf(!hasContract)('derived from openapi.json', () => {
    it('matches the openapi SearchResult.type enum exactly', () => {
      const enumValues =
        contract?.components.schemas.SearchResult?.properties?.type?.enum ?? [];
      expect(enumValues.length).toBeGreaterThan(0);
      expect([...SEARCH_RESULT_TYPES].sort()).toEqual([...enumValues].sort());
    });

    it('is what the `types` parameter pattern accepts, and nothing else', () => {
      const pattern = contract?.paths[SEARCH_PATH]?.get?.parameters?.find(
        (param) => param.name === 'types' && param.in === 'query'
      )?.schema?.pattern;
      expect(pattern).toBeTruthy();
      // `^(SOURCE|CONTENT_BLOCK|...)(,(SOURCE|...))*$` -> every name it names.
      const accepted = [
        ...new Set((pattern ?? '').match(/[A-Z][A-Z_]*/g) ?? []),
      ];
      expect(accepted.sort()).toEqual([...SEARCH_RESULT_TYPES].sort());
    });

    it('only names result types whose id columns the contract declares', () => {
      const properties =
        contract?.components.schemas.SearchResult?.properties ?? {};
      // Every id `searchResultHref` reads has to be a contract field.
      for (const field of [
        'id',
        'sourceId',
        'sourcePageId',
        'knowledgePointId',
        'questionId',
        'noteId',
      ]) {
        expect(properties[field]).toBeDefined();
      }
      // `NOTE` is openable because the hit carries `noteId`.
      expect(properties.noteId?.type).toBe('integer');
    });
  });
});

/* ─────────────── ?types= / ?page= parsing ─────────────── */

describe('parseSearchTypes', () => {
  it('keeps contract values in contract order', () => {
    expect(parseSearchTypes('NOTE,QUESTION,SOURCE')).toEqual([
      'SOURCE',
      'QUESTION',
      'NOTE',
    ]);
  });

  it('accepts every contract value, including NOTE', () => {
    for (const type of SEARCH_RESULT_TYPES) {
      expect(parseSearchTypes(type)).toEqual([type]);
    }
  });

  it('drops values the server would reject instead of forwarding them', () => {
    // `types=FUTURE_TYPE` matches nothing in the pattern -> 400 for the request.
    expect(parseSearchTypes('BOGUS,SOURCE,FUTURE_TYPE')).toEqual(['SOURCE']);
    expect(parseSearchTypes('FUTURE_TYPE')).toEqual([]);
  });

  it('collapses duplicates and tolerates spacing and case', () => {
    expect(parseSearchTypes(' note , NOTE ,note')).toEqual(['NOTE']);
    expect(parseSearchTypes('SOURCE,SOURCE')).toEqual(['SOURCE']);
  });

  it('returns [] for a missing or empty param — the server’s “all types”', () => {
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

/* ─────────────── labels ─────────────── */

describe('searchTypeLabel', () => {
  it('labels every contract type in Chinese, never the raw enum value', () => {
    for (const type of SEARCH_RESULT_TYPES) {
      const label = searchTypeLabel(type);
      expect(label).toBeTruthy();
      expect(label).not.toBe(type);
      expect(label).not.toMatch(/[A-Z_]/);
    }
  });

  it('labels WRONG_QUESTION apart from QUESTION and NOTE as a note', () => {
    expect(searchTypeLabel('WRONG_QUESTION')).toBe('错题');
    expect(searchTypeLabel('QUESTION')).toBe('题目');
    expect(searchTypeLabel('NOTE')).toBe('笔记');
  });

  it('no longer carries the pending-contract copy — NOTE is in the contract', () => {
    for (const type of SEARCH_RESULT_TYPES) {
      expect(searchTypeLabel(type)).not.toMatch(/C01|等待|合同/);
    }
  });

  it('falls back for unknown types without crashing', () => {
    expect(searchTypeLabel('')).toBe('—');
    expect(searchTypeLabel(undefined)).toBe('—');
    expect(searchTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });
});

/* ─────────────── every result type opens the right entity ─────────────── */

interface RouteCase {
  /** The row as `SearchMapper.xml` builds it for this branch. */
  row: SearchHit;
  /** The route this app actually has for that entity. */
  opens: string;
  /**
   * Ids the row carries that must never reach the link: either they address a
   * different entity (`wq.id` addresses the wrong_question row) or the reader
   * does not take them as a parameter (`sourceAssetId`).
   */
  neverLink: number[];
}

/**
 * One case per contract result type — `Record<SearchResultType, …>` means the
 * compiler already refuses a type with no row, and the coverage assertions
 * below refuse one that a test author added without a row.
 */
const ROUTE_CASES: Record<SearchResultType, RouteCase> = {
  SOURCE: {
    row: hit({ type: 'SOURCE', id: 5, sourceId: 5, title: 'A source' }),
    opens: '/spaces/7/sources/5',
    neverLink: [],
  },
  CONTENT_BLOCK: {
    row: hit({
      type: 'CONTENT_BLOCK',
      id: 31,
      sourceId: 5,
      sourceAssetId: 77,
      sourcePageId: 20,
      pageNumber: 3,
      title: 'A block',
    }),
    opens: '/spaces/7/sources/5?page=20&block=31',
    neverLink: [77],
  },
  KNOWLEDGE_POINT: {
    row: hit({ type: 'KNOWLEDGE_POINT', id: 10, knowledgePointId: 10 }),
    opens: '/spaces/7/knowledge/10',
    neverLink: [],
  },
  QUESTION: {
    row: hit({ type: 'QUESTION', id: 12, questionId: 12 }),
    opens: '/spaces/7/questions/12',
    neverLink: [],
  },
  WRONG_QUESTION: {
    // `id` is the wrong_question row (SearchMapper.xml:267); the question is
    // only reachable through `questionId` (:285).
    row: hit({ type: 'WRONG_QUESTION', id: 44, questionId: 12 }),
    opens: '/spaces/7/questions/12',
    neverLink: [44],
  },
  NOTE: {
    // Both slots carry the note id (:312 entity_id, :331 note_id).
    row: hit({ type: 'NOTE', id: 9, noteId: 9 }),
    opens: '/spaces/7/notes/9',
    neverLink: [],
  },
};

/** A row of the same type with the id its route needs missing entirely. */
const NOT_NAVIGABLE: Record<SearchResultType, SearchHit> = {
  SOURCE: hit({ type: 'SOURCE', id: 5, title: 'no sourceId' }),
  CONTENT_BLOCK: hit({
    type: 'CONTENT_BLOCK',
    id: 31,
    sourcePageId: 20,
    title: 'no sourceId',
  }),
  KNOWLEDGE_POINT: hit({ type: 'KNOWLEDGE_POINT', id: 10, title: 'no kp id' }),
  QUESTION: hit({ type: 'QUESTION', id: 12, title: 'no questionId' }),
  WRONG_QUESTION: hit({ type: 'WRONG_QUESTION', id: 44, title: 'no questionId' }),
  NOTE: hit({ type: 'NOTE', title: 'no noteId and no id' }),
};

describe('searchResultHref — one row per contract result type', () => {
  it('covers every contract type in both tables', () => {
    const covered = [...SEARCH_RESULT_TYPES].sort();
    expect(Object.keys(ROUTE_CASES).sort()).toEqual(covered);
    expect(Object.keys(NOT_NAVIGABLE).sort()).toEqual(covered);
  });

  for (const type of SEARCH_RESULT_TYPES) {
    const testCase = ROUTE_CASES[type];

    it(`${type} opens ${testCase.opens}`, () => {
      const href = searchResultHref(SPACE, testCase.row);
      expect(href).toBe(testCase.opens);
      for (const id of testCase.neverLink) {
        expect(href ?? '').not.toContain(String(id));
      }
    });

    it(`${type} with no routable id is not navigable`, () => {
      expect(searchResultHref(SPACE, NOT_NAVIGABLE[type])).toBeNull();
    });
  }

  it('CONTENT_BLOCK deep-links the page id, never the document page number', () => {
    const href = searchResultHref(SPACE, ROUTE_CASES.CONTENT_BLOCK.row);
    expect(href).toContain('page=20');
    expect(href).not.toContain('page=3');
  });

  it('CONTENT_BLOCK without a page id still opens the Source, minus the deep link', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'CONTENT_BLOCK', id: 31, sourceId: 5 })
      )
    ).toBe('/spaces/7/sources/5');
  });

  it('KNOWLEDGE_POINT and QUESTION use their entity id fields, not the hit id', () => {
    expect(
      searchResultHref(
        SPACE,
        hit({ type: 'KNOWLEDGE_POINT', id: 99, knowledgePointId: 10 })
      )
    ).toBe('/spaces/7/knowledge/10');
    expect(
      searchResultHref(SPACE, hit({ type: 'QUESTION', id: 99, questionId: 12 }))
    ).toBe('/spaces/7/questions/12');
  });
});

/* ─────────────── NOTE: which slot the server fills ─────────────── */

describe('searchResultHref — NOTE hits', () => {
  it('links the note detail route through the noteId the contract sends', () => {
    expect(searchResultHref(SPACE, hit({ type: 'NOTE', noteId: 8 }))).toBe(
      '/spaces/7/notes/8'
    );
  });

  it('prefers noteId when the two slots disagree — `id` is not the note route', () => {
    // If a future row ever splits them, the field the DTO added for this
    // (`noteId`, "so the client can open it directly") owns the link.
    expect(
      searchResultHref(SPACE, hit({ type: 'NOTE', id: 44, noteId: 8 }))
    ).toBe('/spaces/7/notes/8');
  });

  it('falls back to `id` for a backend that has not shipped note_id yet', () => {
    // Not a guess: the NOTE branch puts `n.id` in BOTH entity_id and note_id,
    // and the checked-out server still selects only entity_id.
    expect(searchResultHref(SPACE, hit({ type: 'NOTE', id: 9 }))).toBe(
      '/spaces/7/notes/9'
    );
    // A junk `noteId` cannot poison the equivalent slot.
    expect(
      searchResultHref(SPACE, hit({ type: 'NOTE', id: 9, noteId: 0 }))
    ).toBe('/spaces/7/notes/9');
  });

  it('does not invent a note route for a row with neither id', () => {
    expect(searchResultHref(SPACE, hit({ type: 'NOTE', sourceId: 5 }))).toBeNull();
  });
});

describe('searchResultHref — anything outside the contract', () => {
  it('gives a type this build does not know no route', () => {
    expect(searchResultHref(SPACE, hit({ type: 'FUTURE_TYPE', id: 3 }))).toBeNull();
    expect(searchResultHref(SPACE, hit({ id: 3 }))).toBeNull();
    expect(searchResultHref(SPACE, hit({ type: '', id: 3 }))).toBeNull();
  });

  it('never builds a link out of a non-integer id', () => {
    expect(
      searchResultHref(SPACE, hit({ type: 'SOURCE', sourceId: 1.5 }))
    ).toBeNull();
    expect(
      searchResultHref(SPACE, hit({ type: 'NOTE', noteId: 1.5, id: 0 }))
    ).toBeNull();
  });
});
