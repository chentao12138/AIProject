/**
 * SearchPage — URL as the single source of truth (W12) + result-type filter
 * and contract deep-links (W13).
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { SearchPage } from './SearchPage';
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

/* ─────────────── W13: result-type filter UI ─────────────── */

describe('SearchPage — result type filter (W13)', () => {
  it('offers exactly the contract enum and nothing else', async () => {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search: vi.fn(async () => pageWith([])) }),
      initialEntries: ['/spaces/7/search'],
      routePath: ROUTE,
    });

    const boxes = await screen.findAllByRole('checkbox');
    expect(boxes.map((b) => b.getAttribute('id'))).toEqual([
      's-type-SOURCE',
      's-type-CONTENT_BLOCK',
      's-type-KNOWLEDGE_POINT',
      's-type-QUESTION',
      's-type-WRONG_QUESTION',
    ]);
    // NOTE is requested from the backend (C01) but is not in the contract, so
    // it is not offered and can never be sent.
    expect(
      screen.queryByRole('checkbox', { name: /笔记/ })
    ).not.toBeInTheDocument();
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
    await user.click(screen.getByRole('checkbox', { name: '题目' }));
    await user.click(screen.getByRole('checkbox', { name: '错题' }));

    await waitFor(() => expect(lastArgs(search).types).toBe('QUESTION,WRONG_QUESTION'));
    expect(lastArgs(search).page).toBe(0);
    // Contract order in the URL, and the page reset is part of the same write.
    expect(urlParams()).toEqual({ q: 'bayes', types: 'QUESTION,WRONG_QUESTION' });
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

  it('drops a types value the server would reject and keeps the rest', async () => {
    const search = vi.fn(async () => pageWith(twoHits));
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search }),
      initialEntries: ['/spaces/7/search?q=bayes&types=NOTE,SOURCE'],
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

/* ─────────────── W13: contract deep links from the list ─────────────── */

describe('SearchPage — every contract result type opens the right place', () => {
  const hits = [
    { type: 'SOURCE', id: 1, sourceId: 5, title: 'A source' },
    {
      type: 'CONTENT_BLOCK',
      id: 31,
      sourceId: 5,
      sourcePageId: 20,
      pageNumber: 3,
      title: 'A block',
    },
    { type: 'KNOWLEDGE_POINT', id: 2, knowledgePointId: 10, title: 'A point' },
    { type: 'QUESTION', id: 3, questionId: 12, title: 'A question' },
    { type: 'WRONG_QUESTION', id: 4, questionId: 13, title: 'A wrong question' },
    { type: 'NOTE', id: 5, title: 'A note' },
  ];

  async function renderHits() {
    renderWithProviders(<SearchPage />, {
      apiClient: mockApiClient({ search: vi.fn(async () => pageWith(hits)) }),
      initialEntries: ['/spaces/7/search?q=anything'],
      routePath: ROUTE,
    });
    await screen.findByText('A source');
  }

  it('links CONTENT_BLOCK to the Source workbench with page + block', async () => {
    await renderHits();
    expect(screen.getByRole('link', { name: 'A block' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5?page=20&block=31'
    );
  });

  it('links WRONG_QUESTION to its question, since no wrong-question route exists', async () => {
    await renderHits();
    expect(screen.getByRole('link', { name: 'A wrong question' })).toHaveAttribute(
      'href',
      '/spaces/7/questions/13'
    );
    expect(screen.getByText('错题')).toBeInTheDocument();
  });

  it('links QUESTION, KNOWLEDGE_POINT and SOURCE by their entity ids', async () => {
    await renderHits();
    expect(screen.getByRole('link', { name: 'A question' })).toHaveAttribute(
      'href',
      '/spaces/7/questions/12'
    );
    expect(screen.getByRole('link', { name: 'A point' })).toHaveAttribute(
      'href',
      '/spaces/7/knowledge/10'
    );
    expect(screen.getByRole('link', { name: 'A source' })).toHaveAttribute(
      'href',
      '/spaces/7/sources/5'
    );
  });

  it('renders a NOTE hit as plain text with pending-contract copy, never a route', async () => {
    await renderHits();
    const note = screen.getByText('A note');
    expect(note.tagName).toBe('SPAN');
    expect(screen.getByText(/笔记（等待后端合同 C01）/)).toBeInTheDocument();
    expect(document.querySelector('a[href*="notes"]')).toBeNull();
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
});
