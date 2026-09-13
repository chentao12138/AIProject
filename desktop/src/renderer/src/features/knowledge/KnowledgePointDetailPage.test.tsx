/**
 * K1.9–K1.11 — KnowledgePoint detail + publish flow.
 *
 * - DRAFT detail shows a Publish action
 * - PUBLISHED detail shows NO active publish CTA
 * - publish mutation calls publishKnowledgePoint(spaceId, pointId)
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { KnowledgePointDetailPage } from './KnowledgePointDetailPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

describe('KnowledgePointDetailPage', () => {
  it('shows Publish for a DRAFT point (K1.9)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Draft note')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Publish' })).toBeInTheDocument();
  });

  it('shows no active publish CTA for a PUBLISHED point (K1.10)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 6,
          spaceId: 3,
          title: 'Published note',
          content: 'body',
          status: 'PUBLISHED',
          originType: 'USER_CURATED',
          publishedAt: '2026-09-02T08:00:00Z',
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/6'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Published note')).toBeInTheDocument();
    // badge appears in the page header and in the Status row
    expect(screen.getAllByText('PUBLISHED').length).toBeGreaterThan(0);
    expect(
      screen.queryByRole('button', { name: 'Publish' })
    ).not.toBeInTheDocument();
  });

  it('publish mutation calls publishKnowledgePoint(spaceId, pointId) (K1.11)', async () => {
    const user = userEvent.setup();
    const publishKnowledgePoint = vi.fn(async () => ({
      data: {
        id: 5,
        spaceId: 3,
        title: 'Draft note',
        content: 'body',
        status: 'PUBLISHED',
        originType: 'USER_CURATED',
        publishedAt: '2026-09-02T08:00:00Z',
      },
      response: { status: 200 },
    }));

    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
      publishKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    await user.click(await screen.findByRole('button', { name: 'Publish' }));

    await waitFor(() => {
      expect(publishKnowledgePoint).toHaveBeenCalledWith(3, 5);
    });
  });

  it('shows — when categoryId is null (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 8,
          spaceId: 3,
          title: 'No category',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: null,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/8'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('No category')).toBeInTheDocument();
    const row = screen.getByText('Category').closest('div');
    expect(row).toBeTruthy();
    expect(row!.textContent).toBe('Category—');
    expect(screen.queryByText(/#null|#undefined|#NaN/)).not.toBeInTheDocument();
  });

  it('shows the resolved category name when the id matches (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [{ id: 7, name: 'Algebra' }],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 9,
          spaceId: 3,
          title: 'Categorized',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: 7,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/9'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Categorized')).toBeInTheDocument();
    expect(await screen.findByText('Algebra')).toBeInTheDocument();
  });

  it('falls back to #<id> when the category id is missing from the list (final polish)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 10,
          spaceId: 3,
          title: 'Orphan category',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
          categoryId: 7,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/10'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Orphan category')).toBeInTheDocument();
    expect(await screen.findByText('#7')).toBeInTheDocument();
  });

  it('never calls the API for an invalid point id (PHASE 16)', async () => {
    const getKnowledgePoint = vi.fn(async () => ({
      data: undefined,
      error: { message: 'x' },
      response: { status: 404 },
    }));
    const apiClient = mockApiClient({ getKnowledgePoint });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/not-a-number'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
    expect(getKnowledgePoint).not.toHaveBeenCalled();
  });

  it('shows a loading state while the detail query is pending (PHASE 16)', async () => {
    let resolveGet: (value: unknown) => void = () => undefined;
    const getKnowledgePoint = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveGet = resolve;
        })
    );
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('加载知识点…')).toBeInTheDocument();

    resolveGet({
      data: { id: 5, spaceId: 3, title: 'Loaded', content: 'x', status: 'DRAFT' },
      response: { status: 200 },
    });
    expect(await screen.findByText('Loaded')).toBeInTheDocument();
  });

  it.each([
    [401, /当前开发会话未认证或令牌已失效/],
    [403, /当前会话无权执行此操作/],
    [404, /资源不存在或当前不可访问/],
    [500, /服务器暂时无法完成请求/],
  ])('shows the %s error state on detail (PHASE 16)', async (status, message) => {
    const apiClient = mockApiClient({
      getKnowledgePoint: vi.fn(async () => ({
        data: undefined,
        error: { message: 'err' },
        response: { status },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText(message)).toBeInTheDocument();
  });

  it('renders a network error state on detail (PHASE 16)', async () => {
    const apiClient = mockApiClient({
      getKnowledgePoint: vi.fn(async () => {
        throw new TypeError('fetch failed');
      }),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(
      await screen.findByText('无法连接到后端服务。请确认服务已启动后重试。')
    ).toBeInTheDocument();
  });

  it('shows a dash for a missing publishedAt (PHASE 16)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Published, no timestamp',
          content: 'x',
          status: 'PUBLISHED',
          originType: 'USER_CURATED',
          publishedAt: undefined,
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Published, no timestamp')).toBeInTheDocument();
    expect(screen.getByText(/Published at —/)).toBeInTheDocument();
  });

  it('preserves readable content whitespace (PHASE 16)', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Whitespace',
          content: 'line one\n\nline two',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    const body = await screen.findByText(/line one\n\nline two/, {
      normalizer: (str) => str,
    });
    expect(body).toBeInTheDocument();
  });

  it('disables Publish while pending and blocks duplicate clicks (PHASE 16)', async () => {
    const user = userEvent.setup();
    let resolvePublish: (value: unknown) => void = () => undefined;
    const publishKnowledgePoint = vi.fn(
      () =>
        new Promise((resolve) => {
          resolvePublish = resolve;
        })
    );
    const getKnowledgePoint = vi
      .fn()
      .mockResolvedValueOnce({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'PUBLISHED',
          originType: 'USER_CURATED',
          publishedAt: '2026-09-02T08:00:00Z',
        },
        response: { status: 200 },
      });
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint,
      publishKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    await user.click(await screen.findByRole('button', { name: 'Publish' }));

    const publishing = await screen.findByRole('button', {
      name: 'Publishing…',
    });
    expect(publishing).toBeDisabled();
    await user.click(publishing);
    expect(publishKnowledgePoint).toHaveBeenCalledTimes(1);

    resolvePublish({
      data: { id: 5, spaceId: 3, title: 'Draft note', content: 'body', status: 'PUBLISHED' },
      response: { status: 200 },
    });
    await waitFor(() => {
      expect(
        screen.queryByRole('button', { name: /Publish/ })
      ).not.toBeInTheDocument();
    });
  });

  it('shows a publish error visibly (PHASE 16)', async () => {
    const user = userEvent.setup();
    const publishKnowledgePoint = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
      publishKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    await user.click(await screen.findByRole('button', { name: 'Publish' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    // CTA stays available for a retry
    expect(screen.getByRole('button', { name: 'Publish' })).toBeInTheDocument();
  });

  it('refetches the detail after publish and updates to PUBLISHED (PHASE 16)', async () => {
    const user = userEvent.setup();
    const getKnowledgePoint = vi
      .fn()
      .mockResolvedValueOnce({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })
      .mockResolvedValue({
        data: {
          id: 5,
          spaceId: 3,
          title: 'Draft note',
          content: 'body',
          status: 'PUBLISHED',
          originType: 'USER_CURATED',
          publishedAt: '2026-09-02T08:00:00Z',
        },
        response: { status: 200 },
      });
    const publishKnowledgePoint = vi.fn(async () => ({
      data: { id: 5, spaceId: 3, title: 'Draft note', content: 'body', status: 'PUBLISHED' },
      response: { status: 200 },
    }));
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint,
      publishKnowledgePoint,
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    await user.click(await screen.findByRole('button', { name: 'Publish' }));

    // invalidate -> detail refetched
    await waitFor(() => {
      expect(getKnowledgePoint).toHaveBeenCalledTimes(2);
    });
    expect(
      await screen.findByText(/Published at 2026/)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: 'Publish' })
    ).not.toBeInTheDocument();
    expect(screen.getAllByText('PUBLISHED').length).toBeGreaterThan(0);
  });
});

describe('KnowledgePointDetailPage — provenance (FE-002A PHASE 19)', () => {
  it('shows linked content blocks when the contract exposes them', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: {
          id: 5,
          title: 'Point',
          content: 'body',
          status: 'DRAFT',
          originType: 'USER_CURATED',
        },
        response: { status: 200 },
      })),
      listKnowledgePointSources: vi.fn(async () => ({
        data: [
          {
            id: 91,
            contentBlockId: 44,
            relationType: 'PRIMARY',
          },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText(/Content block #44/)).toBeInTheDocument();
    expect(screen.getByText(/PRIMARY/)).toBeInTheDocument();
  });

  it('shows empty provenance note when no links exist', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: { id: 5, title: 'Point', content: 'body', status: 'DRAFT' },
        response: { status: 200 },
      })),
      listKnowledgePointSources: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(
      await screen.findByText('No linked source content yet.')
    ).toBeInTheDocument();
  });

  it('degrades provenance to a muted note on error without blocking detail', async () => {
    const apiClient = mockApiClient({
      listKnowledgeCategories: vi.fn(async () => ({
        data: [],
        response: { status: 200 },
      })),
      getKnowledgePoint: vi.fn(async () => ({
        data: { id: 5, title: 'Point', content: 'body', status: 'DRAFT' },
        response: { status: 200 },
      })),
      listKnowledgePointSources: vi.fn(async () => ({
        data: undefined,
        error: { message: 'boom' },
        response: { status: 500 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByText('Point')).toBeInTheDocument();
    expect(await screen.findByText('Provenance unavailable.')).toBeInTheDocument();
  });
});
