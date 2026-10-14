/**
 * SearchPage — URL as the single source of truth (W12) + result-type filter
 * and contract deep-links (A7.1 / A7.2 / A7.3, W13).
 *
 * The filter and the link assertions are driven from `SEARCH_RESULT_TYPES`, not
 * from a hand-written list, so the day the contract grows a seventh result type
 * these tests fail until the page offers it and gives it a destination.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { SearchPage } from './SearchPage';
import {
  SEARCH_RESULT_TYPES,
  searchTypeLabel,
  type SearchResultType,
} from './searchContract';
import { ApiClientProvider } from '../../lib/api-context';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../../test/test-utils';

const ROUTE = '/spaces/:spaceId/search';

function pageWith(content: unknown[], totalPages = 1, totalElements = content.length) {
  return {
    data: { content, page: 0, size: 20, totalElements, totalPages },
    response: { status: 200 },
  };
}

const twoHits = [
  { type: 'SOURCE', id: 1, sourceId: 1, title: 'Sample Source', snippet: '...' },
  { type: 'KNOWLEDGE_POINT', id: 2, knowledgePointId: 10, title: 'Bayes theorem', snippet: '...' },
];

type SearchMock = ReturnType<typeof vi.fn>;

function lastArgs(search: SearchMock) {
  const call = search.mock.calls[search.mock.calls.length - 1];
  return call?.[1] as { q: string; types?: string; page: number; size: number };
}

function box(): HTMLInputElement {
  return screen.getByLabelText('关键词') as HTMLInputElement;
}

/* ─────────────── existing coverage ─────────────── */

describe('SearchPage', () => {
  it('renders the search form when no query is entered', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => pageWith([])),
      }),
      initialEntries: ['/spaces/7/search'],
      routePath: ROUTE,
    });

    expect(await screen.findByLabelText('关键词')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '搜索' })).toBeInTheDocument();
  });

  it('renders search results and deep-links to entities', async () => {
    const user = userEvent.setup();
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => pageWith(twoHits)),
      }),
      initialEntries: ['/spaces/7/search'],
      routePath: ROUTE,
    });

    await user.type(await screen.findByLabelText('关键词'), 'bayes');
    await user.click(screen.getByRole('button', { name: '搜索' }));

    expect(await screen.findByText('Sample Source')).toBeInTheDocument();
    expect(screen.getByText('Bayes theorem')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Sample Source/ }).getAttribute('href')).toBe('/spaces/7/sources/1');
    expect(screen.getByRole('link', { name: /Bayes theorem/ }).getAttribute('href')).toBe('/spaces/7/knowledge/10');
  });
});

/* ─────────────── W12: URL replay / back-forward ─────────────── */

/**
 * A harness that shares ONE MemoryRouter with history controls and a location
 * probe, so browser back/forward and the resulting URL are observable — the
 * exact thing `useState(rawQuery)` used to lose.
 */
function renderSearchWithHistory(api: ApiClient, initialEntries: string[]) {
  function Controls() {
    const navigate = useNavigate();
    const location = useLocation();
    return (
      <div>
        <button type="button" onClick={() => navigate(-1)}>history-back</button>
        <button type="button" onClick={() => navigate(1)}>history-forward</button>
        <button type="button" onClick={() => navigate('/spaces/7/search?q=deep')}>
          external-deep-link
        </button>
        {/* Decoded snapshot: `types=A,B` is serialized as `A%2CB`. */}
        <p data-testid="location-params">
          {JSON.stringify(
            Object.fromEntries(new URLSearchParams(location.search).entries())
          )}
        </p>
      </div>
    );
  }

  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <ApiClientProvider client={api}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path={ROUTE} element={<SearchPage />} />
          </Routes>
          <Controls />
        </MemoryRouter>
      </ApiClientProvider>
    </QueryClientProvider>
  );
}

/** The live URL query params, decoded. */
function urlParams(): Record<string, string> {
  return JSON.parse(
    screen.getByTestId('location-params').textContent ?? '{}'
  ) as Record<string, string>;
}

describe('SearchPage — ?q= is replayed by the URL (W12)', () => {
  it('follows ?q= when the user goes back', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=alpha',
      '/spaces/7/search?q=beta',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    expect(box()).toHaveValue('beta');
    expect(lastArgs(search).q).toBe('beta');

    await user.click(screen.getByRole('button', { name: 'history-back' }));

    // The box, the request and the URL must all move together.
    expect(box()).toHaveValue('alpha');
    await waitFor(() => {
      expect(lastArgs(search).q).toBe('alpha');
    });
    expect(urlParams().q).toBe('alpha');
  });

  it('follows ?q= when the user goes forward again', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=alpha',
      '/spaces/7/search?q=beta',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    await user.click(screen.getByRole('button', { name: 'history-back' }));
    await waitFor(() => expect(lastArgs(search).q).toBe('alpha'));
    expect(box()).toHaveValue('alpha');

    await user.click(screen.getByRole('button', { name: 'history-forward' }));
    expect(box()).toHaveValue('beta');
    await waitFor(() => expect(lastArgs(search).q).toBe('beta'));
  });

  it('adopts a ?q= that arrives from somewhere else entirely', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=alpha',
      '/spaces/7/search?q=beta',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    // Another page navigating here is the same class of bug as back/forward.
    await user.click(screen.getByRole('button', { name: 'external-deep-link' }));

    expect(box()).toHaveValue('deep');
    await waitFor(() => expect(lastArgs(search).q).toBe('deep'));
  });

  it('starts from ?q= on a refresh / deep link', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search }),
      initialEntries: ['/spaces/7/search?q=gamma'],
      routePath: ROUTE,
    });

    await screen.findByText('Sample Source');
    expect(box()).toHaveValue('gamma');
    expect(lastArgs(search)).toEqual(
      expect.objectContaining({ q: 'gamma', page: 0 })
    );
  });

  it('keeps unsubmitted typing when ?q= has not changed', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search }),
      initialEntries: ['/spaces/7/search?q=alpha'],
      routePath: ROUTE,
    });
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    await user.clear(box());
    await user.type(box(), 'draft text');

    // The draft is not the committed search: the box keeps the user's text and
    // no request is made for it.
    expect(box()).toHaveValue('draft text');
    expect(lastArgs(search).q).toBe('alpha');
  });

  it('keeps page and filter in the URL so back returns to the same result slice', async () => {
    const search = vi.fn(async () => pageWith(twoHits, 3, 60));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=alpha&types=SOURCE&page=1',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    expect(screen.getByText('第 2 / 3 页')).toBeInTheDocument();
    expect(lastArgs(search)).toEqual(
      expect.objectContaining({ q: 'alpha', types: 'SOURCE', page: 1 })
    );

    await user.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => expect(lastArgs(search).page).toBe(2));
    expect(urlParams()).toEqual({ q: 'alpha', types: 'SOURCE', page: '2' });

    await user.click(screen.getByRole('button', { name: 'history-back' }));
    await waitFor(() => expect(lastArgs(search).page).toBe(1));
    expect(box()).toHaveValue('alpha');
    expect(
      screen.getByRole('checkbox', { name: '学习来源' })
    ).toBeChecked();
    expect(urlParams()).toEqual({ q: 'alpha', types: 'SOURCE', page: '1' });
  });
});

/* ─────────────── A7.2 / W13: result-type filter UI ─────────────── */

describe('SearchPage — result type filter (A7.2 / W13)', () => {
  it('offers exactly the contract enum, in contract order, labelled', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search: vi.fn(async () => pageWith([])) }),
      initialEntries: ['/spaces/7/search'],
      routePath: ROUTE,
    });

    const boxes = await screen.findAllByRole('checkbox');
    expect(boxes.map((b) => b.getAttribute('id'))).toEqual(
      SEARCH_RESULT_TYPES.map((type) => `s-type-${type}`)
    );
    // Every contract type is selectable by its Chinese label, and none of them
    // comes pre-checked: an unfiltered search is the server's default, not a
    // client-side one.
    for (const type of SEARCH_RESULT_TYPES) {
      const checkbox = screen.getByRole('checkbox', {
        name: searchTypeLabel(type),
      });
      expect(checkbox).toBeInTheDocument();
      expect(checkbox).not.toBeChecked();
    }
  });

  it('NOTE is offered now that the contract returns it', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    const noteBox = screen.getByRole('checkbox', { name: '笔记' });
    expect(noteBox).toBeInTheDocument();

    await user.click(noteBox);

    await waitFor(() => expect(lastArgs(search).types).toBe('NOTE'));
    expect(urlParams()).toEqual({ q: 'bayes', types: 'NOTE' });
  });

  it('writes the selection to the URL and sends the contract value', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    await user.click(screen.getByRole('checkbox', { name: '错题' }));

    await waitFor(() => expect(lastArgs(search).types).toBe('WRONG_QUESTION'));
    expect(urlParams()).toEqual({ q: 'bayes', types: 'WRONG_QUESTION' });
    expect(search).toHaveBeenLastCalledWith(
      7,
      expect.objectContaining({ q: 'bayes', types: 'WRONG_QUESTION', page: 0 })
    );
  });

  it('combines several types in contract order and resets the page', async () => {
    const search = vi.fn(async () => pageWith(twoHits, 3, 60));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes&page=2',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    await user.click(screen.getByRole('checkbox', { name: '错题' }));
    expect(lastArgs(search).page).toBe(0);
    expect(urlParams()).toEqual({ q: 'bayes', types: 'WRONG_QUESTION' });

    await user.click(screen.getByRole('checkbox', { name: '题目' }));
    await user.click(screen.getByRole('checkbox', { name: '笔记' }));

    // Click order was 错题/题目/笔记; the URL carries contract order, and the
    // page reset is part of the same write every time the filter changes.
    await waitFor(() =>
      expect(lastArgs(search).types).toBe('QUESTION,WRONG_QUESTION,NOTE')
    );
    expect(lastArgs(search).page).toBe(0);
    expect(urlParams()).toEqual({
      q: 'bayes',
      types: 'QUESTION,WRONG_QUESTION,NOTE',
    });
  });

  it('clears the filter again from the same control', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes&types=SOURCE',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    expect(screen.getByRole('checkbox', { name: '学习来源' })).toBeChecked();

    await user.click(screen.getByRole('checkbox', { name: '学习来源' }));

    await waitFor(() => expect(lastArgs(search).types).toBeUndefined());
    expect(urlParams()).toEqual({ q: 'bayes' });
  });

  /**
   * The endpoint's own default, not a client-invented one: an absent `types`
   * makes `MySqlSearchService.normalizedTypes()` return an empty list and
   * every SearchMapper branch runs, i.e. all types. So "nothing checked" must
   * send NOTHING, and must say so.
   */
  it('sends no types at all when nothing is selected, and says “all types”', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes',
    ]);

    await screen.findByText('Sample Source');
    expect(lastArgs(search).types).toBeUndefined();
    expect(urlParams()).toEqual({ q: 'bayes' });
    expect(screen.getByTestId('types-filter-hint')).toHaveTextContent('全部类型');
    // Nothing to clear, so no clear control pretending to exist.
    expect(
      screen.queryByRole('button', { name: '全部类型' })
    ).not.toBeInTheDocument();
  });

  it('“全部类型” restores the unfiltered search and the first page', async () => {
    const search = vi.fn(async () => pageWith(twoHits, 3, 60));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes&types=NOTE&page=2',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    expect(screen.getByRole('checkbox', { name: '笔记' })).toBeChecked();

    await user.click(screen.getByRole('button', { name: '全部类型' }));

    await waitFor(() => expect(lastArgs(search).types).toBeUndefined());
    expect(lastArgs(search).page).toBe(0);
    expect(urlParams()).toEqual({ q: 'bayes' });
    expect(screen.getByRole('checkbox', { name: '笔记' })).not.toBeChecked();
  });

  it('drops a types value the server would reject and keeps the rest', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search }),
      initialEntries: ['/spaces/7/search?q=bayes&types=FUTURE_TYPE,SOURCE'],
      routePath: ROUTE,
    });

    await screen.findByText('Sample Source');
    expect(screen.getByRole('checkbox', { name: '学习来源' })).toBeChecked();
    expect(lastArgs(search)).toEqual(
      expect.objectContaining({ q: 'bayes', types: 'SOURCE' })
    );
  });

  it('submits a search that keeps the current type filter', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderSearchWithHistory(mockApiClient({ search }), [
      '/spaces/7/search?q=bayes&types=QUESTION',
    ]);
    const user = userEvent.setup();

    await screen.findByText('Sample Source');
    await user.clear(box());
    await user.type(box(), 'euler');
    await user.click(screen.getByRole('button', { name: '搜索' }));

    await waitFor(() => expect(lastArgs(search).q).toBe('euler'));
    expect(lastArgs(search).types).toBe('QUESTION');
    expect(box()).toHaveValue('euler');
  });
});

/* ─────────────── A7.1 / A7.3: contract deep links from the list ─────────────── */

interface NavigationCase {
  type: SearchResultType;
  title: string;
  /** The row as the server builds it for that branch. */
  row: Record<string, unknown>;
  /** The route the app has for the entity its own id fields identify. */
  href: string;
}

const NAVIGATION_CASES: NavigationCase[] = [
  {
    type: 'SOURCE',
    title: 'A source',
    row: { id: 5, sourceId: 5 },
    href: '/spaces/7/sources/5',
  },
  {
    type: 'CONTENT_BLOCK',
    title: 'A block',
    // `id` is the content block; the reader takes it as `?block=` and the page
    // row as `?page=`. `pageNumber` (3) and `sourceAssetId` (77) are not
    // reader parameters.
    row: { id: 31, sourceId: 5, sourceAssetId: 77, sourcePageId: 20, pageNumber: 3 },
    href: '/spaces/7/sources/5?page=20&block=31',
  },
  {
    type: 'KNOWLEDGE_POINT',
    title: 'A point',
    row: { id: 10, knowledgePointId: 10 },
    href: '/spaces/7/knowledge/10',
  },
  {
    type: 'QUESTION',
    title: 'A question',
    row: { id: 12, questionId: 12 },
    href: '/spaces/7/questions/12',
  },
  {
    type: 'WRONG_QUESTION',
    title: 'A wrong question',
    // `id` 44 is the wrong_question ROW; only `questionId` reaches a route.
    row: { id: 44, questionId: 13 },
    href: '/spaces/7/questions/13',
  },
  {
    type: 'NOTE',
    title: 'A note',
    // `noteId` owns the note route; the differing `id` must not be used.
    row: { id: 99, noteId: 8 },
    href: '/spaces/7/notes/8',
  },
];

describe('SearchPage — every contract result type opens the right place', () => {
  const hits = NAVIGATION_CASES.map((testCase) => ({
    ...testCase.row,
    type: testCase.type,
    title: testCase.title,
  }));

  it('has a fixture for every contract result type, and no others', () => {
    expect(NAVIGATION_CASES.map((c) => c.type).sort()).toEqual(
      [...SEARCH_RESULT_TYPES].sort()
    );
  });

  async function renderHits() {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search: vi.fn(async () => pageWith(hits)) }),
      initialEntries: ['/spaces/7/search?q=anything'],
      routePath: ROUTE,
    });
    await screen.findByText('A source');
  }

  for (const testCase of NAVIGATION_CASES) {
    it(`${testCase.type} links to ${testCase.href}`, async () => {
      await renderHits();
      expect(
        screen.getByRole('link', { name: testCase.title })
      ).toHaveAttribute('href', testCase.href);
    });
  }

  it('labels every hit with its contract type in Chinese', async () => {
    await renderHits();
    for (const testCase of NAVIGATION_CASES) {
      expect(
        screen.getByText(`· ${searchTypeLabel(testCase.type)}`)
      ).toBeInTheDocument();
    }
  });

  it('opens a NOTE hit through noteId, never through the other slot', async () => {
    await renderHits();
    const link = screen.getByRole('link', { name: 'A note' });
    expect(link).toHaveAttribute('href', '/spaces/7/notes/8');
    expect(link.getAttribute('href')).not.toContain('99');
  });

  it('links WRONG_QUESTION to its question, since no wrong-question route exists', async () => {
    await renderHits();
    expect(screen.getByRole('link', { name: 'A wrong question' })).toHaveAttribute(
      'href',
      '/spaces/7/questions/13'
    );
    expect(screen.getByText('错题')).toBeInTheDocument();
  });

  it('renders a hit with no navigable id as plain text', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => pageWith([{ type: 'WRONG_QUESTION', id: 9, title: 'No questionId' }])),
      }),
      initialEntries: ['/spaces/7/search?q=x'],
      routePath: ROUTE,
    });

    await screen.findByText('No questionId');
    expect(screen.getByText('No questionId').tagName).toBe('SPAN');
  });

  it('renders a NOTE hit with neither noteId nor id as plain text', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => pageWith([{ type: 'NOTE', title: 'No note ids' }])),
      }),
      initialEntries: ['/spaces/7/search?q=x'],
      routePath: ROUTE,
    });

    await screen.findByText('No note ids');
    expect(screen.getByText('No note ids').tagName).toBe('SPAN');
    expect(document.querySelector('a[href*="/notes/"]')).toBeNull();
  });

  it('renders a type this build does not know as plain text', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({
        search: vi.fn(async () => pageWith([{ type: 'FUTURE_TYPE', id: 7, title: 'Unknown type' }])),
      }),
      initialEntries: ['/spaces/7/search?q=x'],
      routePath: ROUTE,
    });

    await screen.findByText('Unknown type');
    expect(screen.getByText('Unknown type').tagName).toBe('SPAN');
  });
});
