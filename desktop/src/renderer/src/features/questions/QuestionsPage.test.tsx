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

const ROUTE = '/spaces/:spaceId/questions';

function knowledgePointsApi(overrides: Parameters<typeof mockApiClient>[0] = {}) {
  return mockApiClient({
    listKnowledgePoints: vi.fn(async () => ({
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
          data: [],
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
          data: [
            { id: 1, questionType: 'SINGLE_CHOICE', stem: 'What is 1+1?', status: 'PUBLISHED', difficulty: 'EASY' },
            { id: 2, questionType: 'BOOLEAN', stem: 'The sky is blue.', status: 'DRAFT', difficulty: 'EASY' },
          ],
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
      data: [],
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
      });
    });

    await user.selectOptions(await screen.findByLabelText('状态'), 'DRAFT');
    await waitFor(() => {
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: 'DRAFT',
        questionType: 'SINGLE_CHOICE',
        knowledgePointId: undefined,
      });
    });
  });
});

describe('QuestionsPage — knowledge point filter (W08)', () => {
  it('offers the space’s real points and puts the choice on the URL', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: [],
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions']
    );

    const filter = await screen.findByLabelText('知识点');
    expect(
      await screen.findByRole('option', { name: '牛顿第二定律' })
    ).toBeInTheDocument();
    // A point without a valid id cannot be a query param.
    expect(
      screen.queryByRole('option', { name: '缺 id 的知识点' })
    ).not.toBeInTheDocument();

    await user.selectOptions(filter, '3');

    await waitFor(() =>
      expect(listQuestions).toHaveBeenCalledWith(7, {
        status: undefined,
        questionType: undefined,
        knowledgePointId: 3,
      })
    );
    expect(urlParams()).toEqual({ knowledgePointId: '3' });
    expect(screen.getByLabelText('知识点')).toHaveValue('3');
  });

  it('replays a knowledgePointId that arrived in the URL', async () => {
    const listQuestions = vi.fn(async () => ({
      data: [],
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
      })
    );
    expect(await screen.findByLabelText('知识点')).toHaveValue('5');
  });

  it('drops a malformed knowledgePointId instead of asking for NaN', async () => {
    const listQuestions = vi.fn(
      async (_spaceId: number, _query: Record<string, unknown>) => ({
        data: [],
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
    });
    expect(screen.getByLabelText('知识点')).toHaveValue('');
  });

  it('clears every filter from the URL, not just the visible one', async () => {
    const user = userEvent.setup();
    const listQuestions = vi.fn(async () => ({
      data: [],
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
      })
    );
    expect(urlParams()).toEqual({});
    expect(
      screen.queryByRole('button', { name: '清除筛选' })
    ).not.toBeInTheDocument();
  });
});

describe('QuestionsPage — no paging without a contract for it (C-Paging)', () => {
  it('states the gap and never fakes a pager', async () => {
    const listQuestions = vi.fn(async () => ({
      data: Array.from({ length: 40 }, (_, i) => ({
        id: i + 1,
        questionType: 'SINGLE_CHOICE',
        stem: `题目 ${i + 1}`,
        status: 'PUBLISHED',
      })),
      response: { status: 200 },
    }));
    renderWithUrl(
      knowledgePointsApi({ listQuestions }),
      ['/spaces/7/questions']
    );

    expect(await screen.findByText('题目 40')).toBeInTheDocument();
    expect(
      await screen.findByTestId('paging-gap-notice')
    ).toHaveTextContent('page');
    expect(screen.queryByRole('button', { name: /下一页|上一页|第 \d+ 页/ }))
      .not.toBeInTheDocument();
    // One request for the whole (unpaged) result set — no slice-per-page loop.
    expect(listQuestions).toHaveBeenCalledTimes(1);
  });

  it('sends only the three filters the endpoint declares', async () => {
    const listQuestions = vi.fn(
      async (_spaceId: number, _query: Record<string, unknown>) => ({
        data: [],
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
      'questionType',
      'status',
    ]);
  });
});

describe('QuestionsPage — knowledge point list failure', () => {
  it('still lists questions and keeps the filter usable', async () => {
    const listQuestions = vi.fn(async () => ({
      data: [{ id: 1, questionType: 'SINGLE_CHOICE', stem: '题目', status: 'DRAFT' }],
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
