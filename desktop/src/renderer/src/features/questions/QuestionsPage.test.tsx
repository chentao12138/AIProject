/**
 * QuestionsPage — list rendering and filter behavior (A4.2, W08).
 *
 * The URL is the only holder of filter state, so the assertions below read
 * both sides of it: what the request received and what the address-bar query
 * became. A probe component shares the MemoryRouter with the page to make the
 * live query params observable, the way SearchPage does it.
 */

import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { QuestionsPage } from './QuestionsPage';
import { ApiClientProvider } from '../../lib/api-context';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../../test/test-utils';
import { QUESTIONS_PAGE_SIZE } from '../../lib/format';

/** GET /questions now answers with a paged envelope, not an array. */
function page<T>(items: T[]) {
  return {
    items,
    page: 0,
    pageSize: QUESTIONS_PAGE_SIZE,
    totalItems: items.length,
    totalPages: 1,
  };
}

const ROUTE = '/spaces/:spaceId/questions';

function knowledgePointsApi(overrides: Parameters<typeof mockApiClient>[0] = {}) {
  return mockApiClient({
    listKnowledgePoints: vi.fn(async () => ({
      // Still a bare array in the contract — only /questions is paged.
      data: [
        { id: 3, title: '牛顿第二定律' },
        { id: 5, title: '能量守恒' },
        { id: undefined, title: '缺 id 的知识点' },
      ],
      response: { status: 200 },
    })),
    ...overrides,
  });
}

function renderWithUrl(api: ApiClient, initialEntries: string[]) {
  function LocationProbe() {
    const location = useLocation();
    return (
      <p data-testid="location-params">
        {JSON.stringify(
          Object.fromEntries(new URLSearchParams(location.search).entries())
        )}
      </p>
    );
  }

  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <ApiClientProvider client={api}>
        <MemoryRouter initialEntries={initialEntries}>
          <Routes>
            <Route path={ROUTE} element={<QuestionsPage />} />
          </Routes>
          <LocationProbe />
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

describe('QuestionsPage', () => {
  it('renders an empty state when there are no questions', async () => {
    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({
        listQuestions: vi.fn(async () => ({
          data: page([]),
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/questions'],
      routePath: '/spaces/:spaceId/questions',
    });

    expect(await screen.findByText('还没有题目')).toBeInTheDocument();
  });

  it('renders a question list and links to detail', async () => {
    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({
        listQuestions: vi.fn(async () => ({
          data: page([
            { id: 1, questionType: 'SINGLE_CHOICE', stem: 'What is 1+1?', status: 'PUBLISHED', difficulty: 'EASY' },
            { id: 2, questionType: 'BOOLEAN', stem: 'The sky is blue.', status: 'DRAFT', difficulty: 'EASY' },
          ]),
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/questions'],
      routePath: '/spaces/:spaceId/questions',
    });

    expect(await screen.findByText('What is 1+1?')).toBeInTheDocument();
    expect(screen.getByText('The sky is blue.')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '查看' })).toHaveLength(2);
  });

  it('filters by status and question type via URL search params', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: page([]),
      response: { status: 200 },
    }));

    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({ listQuestions }),
      initialEntries: ['/spaces/7/questions?status=PUBLISHED&questionType=SINGLE_CHOICE'],
      routePath: '/spaces/:spaceId/questions',
    });

    await waitFor(() => {
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'PUBLISHED',
        questionType: 'SINGLE_CHOICE',
        knowledgePointId: undefined,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      });
    });

    await user.click(await screen.findByRole('button', { name: /状态/ }));
    await user.click(screen.getByRole('option', { name: '草稿' }));
    await waitFor(() => {
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'DRAFT',
        questionType: 'SINGLE_CHOICE',
        knowledgePointId: undefined,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      });
    });
  });
});

describe('QuestionsPage — knowledge point filter (W08)', () => {
  it('offers the space’s real points and puts the choice on the URL', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: page([]),
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions']
    );

    const filter = await screen.findByRole('button', { name: /知识点/ });
    await user.click(filter);
    expect(
      await screen.findByRole('option', { name: '牛顿第二定律' })
    ).toBeInTheDocument();
    // A point without a valid id cannot be a query param.
    expect(
      screen.queryByRole('option', { name: '缺 id 的知识点' })
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole('option', { name: '牛顿第二定律' }));

    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: undefined,
        questionType: undefined,
        knowledgePointId: 3,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      })
    );
    expect(urlParams()).toEqual({ knowledgePointId: '3' });
    expect(screen.getByRole('button', { name: /知识点/ })).toHaveTextContent('牛顿第二定律');
  });

  it('replays a knowledgePointId that arrived in the URL', async () => {
    const listQuestions = vi.fn(async () => ({
      data: page([]),
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions?knowledgePointId=5']
    );

    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: undefined,
        questionType: undefined,
        knowledgePointId: 5,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      })
    );
    expect(await screen.findByRole('button', { name: /能量守恒/ })).toBeInTheDocument();
  });

  it('drops a malformed knowledgePointId instead of asking for NaN', async () => {
    const listQuestions = vi.fn(
      async (_spaceId: number, _query: Record<string, unknown>) => ({
        data: page([]),
        response: { status: 200 },
      })
    );
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions?knowledgePointId=abc']
    );

    await waitFor(() => expect(listQuestions).toHaveBeenCalled());
    expect(listQuestions.mock.calls[0][1]).toMatchObject({
      knowledgePointId: undefined,
      page: 0,
      size: QUESTIONS_PAGE_SIZE,
    });
    expect(screen.getByRole('button', { name: /知识点/ })).toHaveTextContent('全部');
  });

  it('clears every filter from the URL, not just the visible one', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: page([]),
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions?status=DRAFT&knowledgePointId=3']
    );

    await user.click(await screen.findByRole('button', { name: '清除筛选' }));

    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: undefined,
        questionType: undefined,
        knowledgePointId: undefined,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      })
    );
    expect(urlParams()).toEqual({});
    expect(
      screen.queryByRole('button', { name: '清除筛选' })
    ).not.toBeInTheDocument();
  });
});

describe('QuestionsPage — the server pages the list (C-Paging closed)', () => {
  const rows = (count: number, offset = 0) =>
    Array.from({ length: count }, (_, i) => ({
      id: offset + i + 1,
      questionType: 'SINGLE_CHOICE',
      stem: `题目 ${offset + i + 1}`,
      status: 'PUBLISHED',
    }));

  it('asks for one page and drives the pager from the server totals', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(
      async (_id: number, q: { page?: number; size?: number }) => ({
        data: {
          items: rows(q?.size ?? 20, (q?.page ?? 0) * (q?.size ?? 20)),
          page: q?.page ?? 0,
          pageSize: q?.size ?? 20,
          totalItems: 55,
          totalPages: 3,
        },
        response: { status: 200 },
      })
    );
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions']
    );

    expect(await screen.findByText('题目 20')).toBeInTheDocument();
    expect(await screen.findByTestId('questions-total')).toHaveTextContent(
      '第 1 / 3 页 · 共 55 条'
    );
    expect(listQuestions.mock.calls[0][1]).toMatchObject({
      page: 0,
      size: QUESTIONS_PAGE_SIZE,
    });
    expect(screen.getByTestId('questions-prev-page')).toBeDisabled();

    await user.click(screen.getByTestId('questions-next-page'));

    await waitFor(() =>
      expect(
        listQuestions.mock.calls.some((call) => call[1]?.page === 1)
      ).toBe(true)
    );
    // The page lives in the URL, so reload and back/forward return to it.
    expect(await screen.findByTestId('questions-total')).toHaveTextContent(
      '第 2 / 3 页'
    );
  });

  it('sends only the parameters the endpoint declares', async () => {
    const listQuestions = vi.fn(
      async (_spaceId: number, _query: Record<string, unknown>) => ({
        data: page([]),
        response: { status: 200 },
      })
    );
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions']
    );

    await waitFor(() => expect(listQuestions).toHaveBeenCalled());
    expect(Object.keys(listQuestions.mock.calls[0][1]).sort()).toEqual([
      'knowledgePointId',
      'page',
      'questionType',
      'size',
      'status',
    ]);
  });
});

describe('QuestionsPage — knowledge point list failure', () => {
  it('still lists questions and keeps the filter usable', async () => {
    const listQuestions = vi.fn(async () => ({
      data: page([{ id: 1, questionType: 'SINGLE_CHOICE', stem: '题目', status: 'DRAFT' }]),
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({
        listQuestions,
        listKnowledgePoints: vi.fn(async () => ({
          error: { message: 'boom' },
          response: { status: 500 },
        })),
      }),
      ['/spaces/7/questions']
    );

    expect(await screen.findByText('题目')).toBeInTheDocument();
    expect(
      await screen.findByText(/知识点列表加载失败/)
    ).toBeInTheDocument();
  });
});

describe('QuestionsPage — archived questions are reachable, not vanished (G09)', () => {
  it('offers 已归档 as a status filter and asks the server for it', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: page([]),
      response: { status: 200 },
    }));
    renderWithUrl(knowledgePointsApi({ listQuestions }), ['/spaces/7/questions']);

    // `archive` writes status=ARCHIVED and nothing else, and
    // selectBySpaceOwnerFilters only filters on the status it is given, so an
    // archived question is findable — asking for it is the only honest way to
    // list it.
    const filter = await screen.findByRole('button', { name: /状态/ });
    await user.click(filter);
    expect(screen.getByRole('option', { name: '已归档' })).toBeInTheDocument();

    await user.click(screen.getByRole('option', { name: '已归档' }));
    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'ARCHIVED',
        questionType: undefined,
        knowledgePointId: undefined,
        page: 0,
        size: QUESTIONS_PAGE_SIZE,
      })
    );
    expect(urlParams()).toEqual({ status: 'ARCHIVED' });
  });

  it('links a live row to the edit form and routes an archived row to the detail page', async () => {
    renderWithProviders(<QuestionsPage />, {
      apiClient: mockApiClient({
        listQuestions: vi.fn(async () => ({
          data: page([
            { id: 1, questionType: 'SINGLE_CHOICE', stem: '可编辑', status: 'DRAFT' },
            { id: 2, questionType: 'SINGLE_CHOICE', stem: '已归档', status: 'ARCHIVED' },
          ]),
          response: { status: 200 },
        })),
      }),
      initialEntries: ['/spaces/7/questions'],
      routePath: '/spaces/:spaceId/questions',
    });

    // PUT refuses an ARCHIVED question (QuestionService.java:124 -> 404), so an
    // archived row must not offer a form that cannot save.
    expect(await screen.findByTestId('question-edit-1')).toHaveAttribute(
      'href',
      '/spaces/7/questions/1/edit'
    );
    expect(screen.queryByTestId('question-edit-2')).not.toBeInTheDocument();
    expect(screen.getByTestId('question-archived-hint-2')).toHaveTextContent(
      '恢复请到详情页'
    );
  });
});

describe('QuestionsPage — a page the server cannot supply (R05)', () => {
  // Measured on the installed build: `?page=99` of a 2-page list rendered
  // 第 100 / 2 页 over an empty table, because the server answers that page with
  // no items and nothing corrected the address. The URL is the pager's only
  // state, so the page has to heal it once the real page count is known.
  function twoPages() {
    return vi.fn(async () => ({
      data: {
        items: [
          { id: 21, questionType: 'SHORT_ANSWER', stem: '第二十二题', status: 'DRAFT', difficulty: 'EASY' },
        ],
        page: 1,
        pageSize: QUESTIONS_PAGE_SIZE,
        totalItems: 21,
        totalPages: 2,
      },
      response: { status: 200 },
    }));
  }

  it('rewrites an out-of-range page to the last page that exists', async () => {
    const listQuestions = twoPages();
    renderWithUrl(mockApiClient({ listQuestions }), ['/spaces/7/questions?page=99']);

    // The impossible page really was requested first — the correction is
    // reactive to the server's own totalPages, not a refusal to ask.
    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(
        7,
        expect.objectContaining({ page: 99, size: QUESTIONS_PAGE_SIZE })
      )
    );
    await waitFor(() => expect(urlParams()).toMatchObject({ page: '1' }));
    await waitFor(() =>
      expect(screen.getByTestId('questions-total').textContent).toContain('第 2 / 2 页')
    );
    expect(await screen.findByText('第二十二题')).toBeInTheDocument();
  });

  it('leaves an in-range page exactly where it is', async () => {
    const listQuestions = twoPages();
    renderWithUrl(mockApiClient({ listQuestions }), ['/spaces/7/questions?page=1']);

    expect(await screen.findByText('第二十二题')).toBeInTheDocument();
    expect(urlParams()).toMatchObject({ page: '1' });
    await waitFor(() =>
      expect(screen.getByTestId('questions-total').textContent).toContain('第 2 / 2 页')
    );
  });
});
