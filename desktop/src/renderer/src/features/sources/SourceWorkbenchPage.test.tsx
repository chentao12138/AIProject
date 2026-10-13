/**
 * SourceWorkbenchPage tests (FE-002B Day 2 architecture).
 *
 * Content section requires: latest asset + PUBLISHED job + pages
 * with matching sourceAssetId. Tests mock the full chain.
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider } from '@tanstack/react-query';
import {
  MemoryRouter,
  Route,
  Routes,
  useLocation,
  useNavigate,
} from 'react-router-dom';
import type { ApiClient } from '@aistudy/api-client';
import { SourceWorkbenchPage } from './SourceWorkbenchPage';
import { ApiClientProvider } from '../../lib/api-context';
import {
  createTestQueryClient,
  mockApiClient,
  renderWithProviders,
} from '../../test/test-utils';

const sourceOk = {
  data: {
    id: 3,
    title: 'My Notes',
    sourceType: 'DESKTOP_UPLOAD',
    status: 'ACTIVE',
  },
  response: { status: 200 },
};

/** Full mock: asset 90 + PUBLISHED job 55 + pages with sourceAssetId=90. */
function contentReadyApi(
  overrides: Record<string, unknown> = {}
) {
  return mockApiClient({
    getSource: vi.fn(async () => sourceOk),
    listSourceAssets: vi.fn(async () => ({
      data: [
        {
          id: 90,
          originalName: 'notes.md',
          mimeType: 'text/markdown',
          sizeBytes: 100,
          createdAt: '2026-09-01T10:00:00Z',
        },
      ],
      response: { status: 200 },
    })),
    listIngestionJobs: vi.fn(async () => ({
      data: [
        {
          id: 55,
          assetId: 90,
          status: 'PUBLISHED',
          stage: 'PUBLISHED',
          createdAt: '2026-09-01T10:00:01Z',
        },
      ],
      response: { status: 200 },
    })),
    listSourcePages: vi.fn(async () => ({
      data: [
        {
          id: 20,
          sourceAssetId: 90,
          pageOrder: 0,
          pageType: 'BODY',
          extractedText: 'hello world',
        },
      ],
      response: { status: 200 },
    })),
    listContentBlocks: vi.fn(async () => ({
      data: [
        {
          id: 31,
          blockType: 'PARAGRAPH',
          sortOrder: 0,
          normalizedText: 'hello world',
        },
      ],
      response: { status: 200 },
    })),
    ...overrides,
  });
}

function renderWorkbench(apiClient: ReturnType<typeof mockApiClient>) {
  return renderWithProviders(<SourceWorkbenchPage />, {
    apiClient,
    initialEntries: ['/spaces/7/sources/3'],
    routePath: '/spaces/:spaceId/sources/:sourceId',
  });
}

async function waitForWorkbench() {
  await screen.findByRole('button', { name: /上传并解析/i });
}

describe('SourceWorkbenchPage — business summary', () => {
  it('shows summary line with media type and content state', async () => {
    renderWorkbench(contentReadyApi());
    expect(await screen.findByTestId('summary-line')).toHaveTextContent(
      /Markdown/
    );
    expect(screen.getByTestId('content-state')).toHaveTextContent('内容就绪');
  });

  it('shows no-asset state when empty', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('summary-line')).toHaveTextContent(
      '尚未上传文件'
    );
  });
});

describe('SourceWorkbenchPage — PDF reader', () => {
  it('shows Page X of N and Prev/Next', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            {
              id: 91,
              originalName: 'doc.pdf',
              mimeType: 'application/pdf',
              createdAt: '2026-09-01T10:00:00Z',
            },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 56, assetId: 91, status: 'PUBLISHED', stage: 'REVIEW' },
          ],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 60, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p0' },
            { id: 61, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p1' },
            { id: 62, sourceAssetId: 91, pageOrder: 2, pageType: 'BODY', extractedText: 'p2' },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            { id: 70, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'p0 text' },
          ],
          response: { status: 200 },
        })),
      })
    );

    expect(await screen.findByTestId('page-indicator')).toHaveTextContent(
      'Page 1 of 3'
    );
    expect(
      screen.getByRole('button', { name: '上一页' })
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: '下一页' })).toBeEnabled();
  });

  it('navigates pages with Next', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 91, originalName: 'doc.pdf', createdAt: '2026-09-01T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [{ id: 56, assetId: 91, status: 'PUBLISHED', stage: 'REVIEW' }],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 60, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p0' },
            { id: 61, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p1' },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [{ id: 70, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'text' }],
          response: { status: 200 },
        })),
      })
    );

    await screen.findByTestId('page-indicator');
    await user.click(screen.getByRole('button', { name: '下一页' }));
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
  });
});

describe('SourceWorkbenchPage — stale content prevention', () => {
  it('shows processing note when the latest job is still working', async () => {
    renderWorkbench(
      contentReadyApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW' },
            { id: 56, assetId: 91, status: 'IMPORTING', stage: 'IMPORT' },
          ],
          response: { status: 200 },
        })),
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'new.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('processing-note')).toHaveTextContent(
      '新文件正在处理'
    );
  });

  it('shows failed note when latest job FAILED', async () => {
    renderWorkbench(
      contentReadyApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 56, assetId: 91, status: 'FAILED', stage: 'QUEUED', errorCode: 'INVALID_PDF' },
          ],
          response: { status: 200 },
        })),
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 91, originalName: 'bad.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('failed-note')).toBeInTheDocument();
  });
});

describe('SourceWorkbenchPage — image no-OCR', () => {
  it('shows image success note for IMAGE pages', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 92, originalName: 'pic.png', mimeType: 'image/png', createdAt: '2026-09-01T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [{ id: 57, assetId: 92, status: 'PUBLISHED', stage: 'REVIEW' }],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 70, sourceAssetId: 92, pageOrder: 0, pageType: 'IMAGE', extractedText: null },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('image-success-note')).toHaveTextContent(
      '图片已成功处理'
    );
  });
});

describe('SourceWorkbenchPage — no-text PDF', () => {
  it('shows PDF no-text note for empty BODY pages', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 93, originalName: 'scan.pdf', createdAt: '2026-09-01T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [{ id: 58, assetId: 93, status: 'PUBLISHED', stage: 'REVIEW' }],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 80, sourceAssetId: 93, pageOrder: 0, pageType: 'BODY', extractedText: '' },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('pdf-no-text-note')).toHaveTextContent(
      'PDF 已成功处理'
    );
  });
});

describe('SourceWorkbenchPage — upload and ingestion history', () => {
  it('renders ingestion history with latest badge and retry for FAILED', async () => {
    renderWorkbench(
      contentReadyApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            {
              id: 55,
              assetId: 90,
              status: 'PUBLISHED',
              stage: 'PUBLISHED',
              createdAt: '2026-09-01T10:00:01Z',
            },
            {
              id: 54,
              assetId: 89,
              status: 'FAILED',
              stage: 'QUEUED',
              createdAt: '2026-09-01T09:00:00Z',
              errorCode: 'INVALID_PDF',
            },
          ],
          response: { status: 200 },
        })),
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'ok.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 89, originalName: 'bad.pdf', createdAt: '2026-09-01T09:00:00Z' },
          ],
          response: { status: 200 },
        })),
      })
    );

    const rows = await screen.findAllByTestId('history-row');
    expect(rows.length).toBe(2);
    // Latest job has the badge.
    expect(rows[0].getAttribute('data-latest')).toBe('true');
    // FAILED job has 重试 button.
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('disables upload submit until file selected', async () => {
    renderWorkbench(contentReadyApi());
    await waitForWorkbench();
    expect(
      screen.getByRole('button', { name: /上传并解析/i })
    ).toBeDisabled();
  });
});

describe('SourceWorkbenchPage — invalid ids', () => {
  it('shows inaccessible for invalid source id', () => {
    const getSource = vi.fn();
    renderWithProviders(<SourceWorkbenchPage />, {
      apiClient: mockApiClient({ getSource }),
      initialEntries: ['/spaces/7/sources/abc'],
      routePath: '/spaces/:spaceId/sources/:sourceId',
    });
    expect(screen.getByText('资源不存在或当前不可访问。')).toBeInTheDocument();
    expect(getSource).not.toHaveBeenCalled();
  });
});

/* ─────────────── W14: a job never describes another asset ─────────────── */

describe('SourceWorkbenchPage — per-asset job state (W14)', () => {
  it('shows 尚未开始解析 when only the older asset has a SUCCEEDED job', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', mimeType: 'text/markdown', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'new.md', mimeType: 'text/markdown', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'old content' },
          ],
          response: { status: 200 },
        })),
      })
    );

    // The newest asset (91) is on screen, and it has no job of its own.
    expect(await screen.findByTestId('not-started-note')).toHaveTextContent('尚未开始解析');
    expect(screen.getByTestId('no-job-for-asset')).toHaveTextContent('尚未开始解析');
    expect(screen.getByTestId('content-state')).toHaveTextContent('尚未开始解析');
    expect(screen.getByTestId('summary-line')).toHaveTextContent('尚未开始解析');
    expect(screen.getByText('new.md')).toBeInTheDocument();
    // Asset A's SUCCEEDED state and content are never attributed to asset B.
    expect(screen.queryByText('old content')).not.toBeInTheDocument();
    expect(screen.queryByTestId('page-indicator')).not.toBeInTheDocument();
    expect(screen.queryByTestId('failed-note')).not.toBeInTheDocument();
    // The history table still shows the real, separately owned job.
    expect(screen.getByTestId('history-row')).toHaveTextContent('old.md');
  });

  it('does not show the older asset FAILED as the newest asset failure', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'bad.pdf', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'fresh.md', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 54, assetId: 90, status: 'FAILED', stage: 'QUEUED', errorCode: 'INVALID_PDF', createdAt: '2026-09-01T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
      })
    );

    expect(await screen.findByTestId('not-started-note')).toBeInTheDocument();
    expect(screen.queryByTestId('failed-note')).not.toBeInTheDocument();
    expect(screen.getByTestId('summary-line')).toHaveTextContent('尚未开始解析');
  });

  it('keeps attributing an unlinked job while the source has one asset', async () => {
    // Backend rows created before `IngestionJobResponse.assetId` was filled:
    // the owner is provable when there is exactly one asset, and dropping the
    // job here would freeze a finished upload on "not started".
    renderWorkbench(
      contentReadyApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: null, status: 'FAILED', stage: 'QUEUED', errorCode: 'INVALID_PDF', createdAt: '2026-09-01T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
      })
    );

    expect(await screen.findByTestId('failed-note')).toBeInTheDocument();
    expect(screen.getByTestId('legacy-job-attribution')).toBeInTheDocument();
  });

  it('never attributes an unlinked job once the source has two assets', async () => {
    renderWorkbench(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'new.md', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: null, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
      })
    );

    expect(await screen.findByTestId('not-started-note')).toBeInTheDocument();
    expect(screen.queryByTestId('legacy-job-attribution')).not.toBeInTheDocument();
    expect(screen.queryByTestId('page-indicator')).not.toBeInTheDocument();
  });
});

/* ─────────────── W15: page selection lives in the URL ─────────────── */

/** The workbench inside one MemoryRouter with history controls + URL probe. */
function renderWorkbenchAt(api: ApiClient, entry: string) {
  function Controls() {
    const navigate = useNavigate();
    const location = useLocation();
    return (
      <div>
        <button type="button" onClick={() => navigate(-1)}>history-back</button>
        <button type="button" onClick={() => navigate(1)}>history-forward</button>
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
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route
              path="/spaces/:spaceId/sources/:sourceId"
              element={<SourceWorkbenchPage />}
            />
          </Routes>
          <Controls />
        </MemoryRouter>
      </ApiClientProvider>
    </QueryClientProvider>
  );
}

function urlParams(): Record<string, string> {
  return JSON.parse(
    screen.getByTestId('location-params').textContent ?? '{}'
  ) as Record<string, string>;
}

function highlightedBlocks(): number {
  return document.querySelectorAll('.content-block--highlighted').length;
}

describe('SourceWorkbenchPage — page state resets with the asset (W15)', () => {
  const pdfApi = (pageData: unknown[]) =>
    contentReadyApi({
      listSourceAssets: vi.fn(async () => ({
        data: [
          { id: 91, originalName: 'doc.pdf', createdAt: '2026-09-01T10:00:00Z' },
        ],
        response: { status: 200 },
      })),
      listIngestionJobs: vi.fn(async () => ({
        data: [
          { id: 56, assetId: 91, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
        ],
        response: { status: 200 },
      })),
      listSourcePages: vi.fn(async () => ({
        data: pageData,
        response: { status: 200 },
      })),
      listContentBlocks: vi.fn(async () => ({
        data: [
          { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'block 31' },
          { id: 32, blockType: 'PARAGRAPH', sortOrder: 1, normalizedText: 'block 32' },
        ],
        response: { status: 200 },
      })),
    });

  it('resets the stale ?page= and its ?block= when they belong to another asset', async () => {
    // page 20 is asset 90's; the newest asset is 91 with page 21 only.
    renderWorkbenchAt(
      pdfApi([
        { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'old asset page' },
        { id: 21, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'new asset page' },
      ]),
      '/spaces/7/sources/3?page=20&block=31'
    );

    await screen.findByTestId('page-indicator');
    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '21' });
    });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 1 of 1');
    expect(
      screen.getByRole('button', { name: 'Page 1' })
    ).toHaveAttribute('aria-current', 'page');
    // The highlight followed the page out of asset 90, even though block 31
    // also exists under the new page.
    expect(highlightedBlocks()).toBe(0);
    expect(screen.queryByText('old asset page')).not.toBeInTheDocument();
  });

  it('keeps a valid deep link and its highlight untouched', async () => {
    renderWorkbenchAt(
      pdfApi([
        { id: 21, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'new asset page' },
        { id: 22, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'second' },
      ]),
      '/spaces/7/sources/3?page=22&block=32'
    );

    await screen.findByTestId('page-indicator');
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
    expect(urlParams()).toEqual({ page: '22', block: '32' });
    await waitFor(() => {
      expect(highlightedBlocks()).toBe(1);
    });
  });

  it('selects through the URL, so browser back returns to the previous page', async () => {
    const user = userEvent.setup();
    renderWorkbenchAt(
      pdfApi([
        { id: 60, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p0' },
        { id: 61, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p1' },
      ]),
      '/spaces/7/sources/3'
    );

    // A page-less URL normalizes to the first page without entering history.
    await screen.findByTestId('page-indicator');
    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '60' });
    });

    await user.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '61' });
    });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
    expect(
      screen.getByRole('button', { name: 'Page 2' })
    ).toHaveAttribute('aria-current', 'page');

    await user.click(screen.getByRole('button', { name: 'history-back' }));
    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '60' });
    });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 1 of 2');
    expect(
      screen.getByRole('button', { name: 'Page 1' })
    ).toHaveAttribute('aria-current', 'page');
  });

  it('drops a stale page selection and URL after a new upload', async () => {
    const user = userEvent.setup();
    let phase = 1;
    const assets = () =>
      phase === 1
        ? [
            { id: 90, originalName: 'a.pdf', createdAt: '2026-09-01T10:00:00Z' },
          ]
        : [
            { id: 90, originalName: 'a.pdf', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'b.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ];
    const pages = () =>
      phase === 1
        ? [
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'a page 1' },
            { id: 21, sourceAssetId: 90, pageOrder: 1, pageType: 'BODY', extractedText: 'a page 2' },
          ]
        : [
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'a page 1' },
            { id: 21, sourceAssetId: 90, pageOrder: 1, pageType: 'BODY', extractedText: 'a page 2' },
            { id: 22, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'b page 1' },
          ];
    const jobs = () =>
      phase === 1
        ? [
            { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
          ]
        : [
            { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-02T10:00:01Z' },
          ];

    renderWorkbenchAt(
      mockApiClient({
        getSource: vi.fn(async () => sourceOk),
        listSourceAssets: vi.fn(async () => ({ data: assets(), response: { status: 200 } })),
        listSourcePages: vi.fn(async () => ({ data: pages(), response: { status: 200 } })),
        listIngestionJobs: vi.fn(async () => ({ data: jobs(), response: { status: 200 } })),
        listContentBlocks: vi.fn(async () => ({ data: [], response: { status: 200 } })),
        uploadSourceAsset: vi.fn(async () => {
          phase = 2;
          return {
            data: { id: 91, originalName: 'b.pdf', createdAt: '2026-09-02T10:00:00Z' },
            response: { status: 201 },
          };
        }),
        createIngestionJob: vi.fn(async () => ({
          data: { id: 56, assetId: 91, status: 'QUEUED', stage: 'IMPORT' },
          response: { status: 201 },
        })),
        getIngestionJob: vi.fn(async () => ({
          data: { id: 56, assetId: 91, status: 'PUBLISHED', stage: 'REVIEW' },
          response: { status: 200 },
        })),
      }),
      '/spaces/7/sources/3'
    );

    // Select asset A's second page.
    await screen.findByTestId('page-indicator');
    await user.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '21' });
    });
    expect(screen.getByText('a page 2')).toBeInTheDocument();

    // Upload asset B: A's page 21 is not part of B.
    const file = new File(['x'], 'b.pdf', { type: 'application/pdf' });
    fireEvent.change(document.querySelector('input[type="file"]') as HTMLInputElement, {
      target: { files: [file] },
    });
    await user.click(screen.getByRole('button', { name: /上传并解析/i }));

    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '22' });
    });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 1 of 1');
    expect(
      screen.getByRole('button', { name: 'Page 1' })
    ).toHaveAttribute('aria-current', 'page');
    expect(screen.queryByText('a page 2')).not.toBeInTheDocument();
    expect(screen.getByText('b page 1')).toBeInTheDocument();
  });
});
