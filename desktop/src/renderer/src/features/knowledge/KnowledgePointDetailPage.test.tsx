/**
 * K1.9–K1.11 — KnowledgePoint detail + publish flow.
 *
 * - DRAFT detail shows a Publish action
 * - PUBLISHED detail shows NO active publish CTA
 * - publish mutation calls publishKnowledgePoint(spaceId, pointId)
 * - A2.1 (G06): provenance rows deep-link into the reader with the ids the
 *   server sends, and degrade to text when the ids they need are absent.
 * - A3.2 (G07): the AI entry carries this point's id as message context.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { RouteObject } from 'react-router-dom';
import { posix } from 'node:path';
import { KnowledgePointDetailPage } from './KnowledgePointDetailPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';
import { NetworkUnavailableError } from '../../lib/api-error';
import { router } from '../../app/router';

/**
 * Does a produced href name a route the app actually registers? Re-implements
 * the check `app/route-contract.test.ts` runs on literal targets, because a
 * deep link built at runtime (id + optional query) is invisible to that scan.
 */
function collectRoutes(routes: readonly RouteObject[], parent = ''): string[] {
  const out: string[] = [];
  for (const route of routes) {
    const raw = (route.path ?? '').replace(/\\/g, '/');
    if (raw.startsWith('*')) {
      continue;
    }
    const full = posix.join('/', parent, raw);
    out.push(full);
    out.push(...collectRoutes(route.children ?? [], full));
  }
  return out;
}

const REGISTERED_ROUTES = collectRoutes(router.routes);

function provenanceRouteResolves(target: string): boolean {
  const path = target.split('?')[0];
  const segments = path.split('/').filter(Boolean);
  return REGISTERED_ROUTES.some((pattern) => {
    const parts = pattern.split('/').filter(Boolean);
    return (
      parts.length === segments.length &&
      parts.every(
        (segment, i) =>
          segment.startsWith(':') ||
          segments[i].startsWith(':') ||
          segment === segments[i]
      )
    );
  });
}

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
    expect(screen.getByRole('button', { name: '发布' })).toBeInTheDocument();
  });

  it('links to the question list pre-filtered by this knowledge point (W16)', async () => {
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
        },
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    const link = await screen.findByRole('link', {
      name: '在题库中查看相关题目',
    });
    // The id must arrive as the query key QuestionsPage actually reads, so the
    // user never has to type a knowledge point id by hand.
    expect(link).toHaveAttribute(
      'href',
      '/spaces/3/questions?knowledgePointId=5'
    );

    const practiceLink = screen.getByRole('link', {
      name: '就此知识点开始练习',
    });
    expect(practiceLink).toHaveAttribute(
      'href',
      '/spaces/3/practice?knowledgePointId=5'
    );
  });

  it('links the point to a prefilled note and to the AI workspace (W16)', async () => {
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
        },
        response: { status: 200 },
      })),
      listKnowledgePointSources: vi.fn(async () => ({
        data: [
          {
            id: 70,
            spaceId: 3,
            knowledgePointId: 5,
            contentBlockId: 31,
            sourceId: 3,
            sourcePageId: 20,
            relationType: 'DERIVED_FROM',
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

    // `knowledgePointId` is the only context id a knowledge point itself owns:
    // the source/page/block trio belongs to the provenance rows, and the Notes
    // list page's own listing keys (`page`, `size`, `keyword`) are not reused
    // for a prefill.
    expect(await screen.findByTestId('point-note-link')).toHaveAttribute(
      'href',
      '/spaces/3/notes?knowledgePointId=5'
    );
    // A3.2 / G07: the point's own id now travels into the AI request body (the
    // contract's SendMessageRequest carries it), so the link names it and the
    // copy says it is attached instead of denying it.
    expect(screen.getByTestId('point-ai-link')).toHaveAttribute(
      'href',
      '/spaces/3/ai?attachKnowledgePointId=5'
    );
    expect(screen.getByTestId('ai-entry-note')).toHaveTextContent(
      '把这个知识点附带为上下文'
    );

    // A2.1 / G06: the provenance row deep-links into the reader with the ids
    // the server sends on this row — `page` is the source PAGE ROW id (20),
    // not a document page number, and `block` rides along only with a page.
    expect(
      screen.getByRole('link', { name: '正文片段 #31' })
    ).toHaveAttribute('href', '/spaces/3/sources/3?page=20&block=31');
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
    expect(screen.getAllByText('已发布').length).toBeGreaterThan(0);
    expect(
      screen.queryByRole('button', { name: '发布' })
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

    await user.click(await screen.findByRole('button', { name: '发布' }));

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
    const row = screen.getByText('分类').closest('div');
    expect(row).toBeTruthy();
    expect(row!.textContent).toBe('分类—');
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
    [401, /登录状态已失效，请重新登录/],
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
        throw new NetworkUnavailableError();
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
    expect(screen.getByText(/发布时间：—/)).toBeInTheDocument();
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

    await user.click(await screen.findByRole('button', { name: '发布' }));

    const publishing = await screen.findByRole('button', {
      name: '发布中…',
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
      // The related-notes section is a second read on this page; stub it so the
      // assertion below stays about the publish error alone.
      listNotes: vi.fn(async () => ({
        data: { items: [], page: 0, pageSize: 20, totalItems: 0, totalPages: 0 },
        response: { status: 200 },
      })),
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

    await user.click(await screen.findByRole('button', { name: '发布' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('服务器暂时无法完成请求，请稍后重试。');
    // CTA stays available for a retry
    expect(screen.getByRole('button', { name: '发布' })).toBeInTheDocument();
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

    await user.click(await screen.findByRole('button', { name: '发布' }));

    // invalidate -> detail refetched
    await waitFor(() => {
      expect(getKnowledgePoint).toHaveBeenCalledTimes(2);
    });
    expect(
      await screen.findByText(/发布时间：2026/)
    ).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: '发布' })
    ).not.toBeInTheDocument();
    expect(screen.getAllByText('已发布').length).toBeGreaterThan(0);
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

    expect(await screen.findByText(/正文片段 #44/)).toBeInTheDocument();
    expect(screen.getByText(/PRIMARY/)).toBeInTheDocument();
    // A2.1 / G06: this row names a block but no source, so the reader has no
    // route to open — text, not a link to `/sources/undefined`.
    expect(
      screen.queryByRole('link', { name: '正文片段 #44' })
    ).not.toBeInTheDocument();
    expect(screen.queryByTestId('provenance-link')).not.toBeInTheDocument();
  });

  it('links a provenance row that names only its source, with no query at all', async () => {
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
        data: [{ id: 92, sourceId: 3, relationType: 'PRIMARY' }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    // A source-level provenance row is still a working deep link: the reader
    // opens that source. Nothing is invented, so there is no page/block param.
    expect(await screen.findByRole('link', { name: '学习来源 #3' })).toHaveAttribute(
      'href',
      '/spaces/3/sources/3'
    );
  });

  it('never emits block without a page, even when the block id is real', async () => {
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
        // The block has no page row in the response: `?block=` alone would be a
        // highlight the reader has no page to apply it on.
        data: [{ id: 93, sourceId: 3, contentBlockId: 44 }],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    expect(await screen.findByRole('link', { name: '正文片段 #44' })).toHaveAttribute(
      'href',
      '/spaces/3/sources/3'
    );
  });

  it('refuses to link a provenance row whose source id is not a positive id', async () => {
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
        data: [
          { id: 94, sourceId: 0, sourcePageId: 20, contentBlockId: 44 },
          { id: 95, sourceId: -7, contentBlockId: 45 },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    const rows = await screen.findAllByTestId('provenance-row');
    // Both rows render, and neither is a link: `/sources/0` would be a 404
    // dressed up as a deep link.
    expect(rows).toHaveLength(2);
    expect(screen.queryAllByTestId('provenance-link')).toEqual([]);
  });

  it('builds every provenance deep link onto a route the app registers', async () => {
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
        data: [
          { id: 96, sourceId: 3, sourcePageId: 20, contentBlockId: 31 },
          { id: 97, sourceId: 3 },
        ],
        response: { status: 200 },
      })),
    });

    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });

    const links = await screen.findAllByTestId('provenance-link');
    expect(links).toHaveLength(2);
    // The same check `app/route-contract.test.ts` runs, applied to the hrefs
    // this page can produce: a reader route that does not exist is a broken
    // audit item, not a page.
    for (const link of links) {
      expect(
        provenanceRouteResolves(link.getAttribute('href') ?? '')
      ).toBe(true);
    }
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
      await screen.findByText('尚未关联任何来源内容。')
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
    expect(await screen.findByText('来源追溯暂不可用。')).toBeInTheDocument();
  });
});

describe('KnowledgePointDetailPage — related notes (C-Notes-reverse)', () => {
  const page = (items: unknown[]) => ({
    data: { items, page: 0, pageSize: 20, totalItems: items.length, totalPages: 1 },
    response: { status: 200 },
  });

  function renderWithNotes(listNotes: ReturnType<typeof vi.fn>) {
    const apiClient = mockApiClient({
      listNotes,
      getKnowledgePoint: vi.fn(async () => ({
        data: { id: 5, spaceId: 3, title: 'Point', content: 'body', status: 'DRAFT' },
        response: { status: 200 },
      })),
      listKnowledgeCategories: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    });
    renderWithProviders(<KnowledgePointDetailPage />, {
      apiClient,
      initialEntries: ['/spaces/3/knowledge/5'],
      routePath: '/spaces/:spaceId/knowledge/:knowledgePointId',
    });
    return apiClient;
  }

  it('asks the notes list for this point and links each returned note', async () => {
    const listNotes = vi.fn(async () =>
      page([
        { id: 41, spaceId: 3, title: '公式整理', contentFormat: 'PLAIN_TEXT', updatedAt: '2026-10-02T08:00:00Z' },
        { id: 42, spaceId: 3, title: '旧笔记', contentFormat: 'PLAIN_TEXT', updatedAt: '2026-10-03T08:00:00Z', archivedAt: '2026-10-04T08:00:00Z' },
      ])
    );
    renderWithNotes(listNotes);

    // The reverse lookup is a filter on the notes list — the client asks for
    // this point, it does not slice some other list down.
    await waitFor(() =>
      expect(listNotes).toHaveBeenCalledWith(3, {
        knowledgePointId: 5,
        page: 0,
        size: 20,
      })
    );
    const link = await screen.findByRole('link', { name: '公式整理' });
    expect(link).toHaveAttribute('href', '/spaces/3/notes/41');
    expect(screen.getByText(/已归档/)).toBeInTheDocument();
  });

  it('says there is nothing related when the server returns an empty page', async () => {
    renderWithNotes(vi.fn(async () => page([])));
    expect(await screen.findByTestId('related-notes-empty')).toBeInTheDocument();
    expect(screen.queryAllByTestId('related-note')).toHaveLength(0);
  });

  // A read that failed is not "no notes" — that would tell the reader nothing is
  // related when the server only failed to answer.
  it('keeps a failed related-notes read distinct from an empty one', async () => {
    renderWithNotes(
      vi.fn(async () => ({ error: { code: 'INTERNAL_ERROR' }, response: { status: 500 } }))
    );
    expect(await screen.findByTestId('related-notes-error')).toBeInTheDocument();
    expect(screen.queryByTestId('related-notes-empty')).not.toBeInTheDocument();
  });
});
