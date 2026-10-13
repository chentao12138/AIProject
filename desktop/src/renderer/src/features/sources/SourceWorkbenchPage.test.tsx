/**
 * SourceWorkbenchPage tests (FE-002B Day 2 architecture).
 *
 * Content section requires: latest asset + PUBLISHED job + pages
 * with matching sourceAssetId. Tests mock the full chain.
 */

import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
function renderWorkbenchAt(
  api: ApiClient,
  entry: string,
  gotoTargets: string[] = []
) {
  function Controls() {
    const navigate = useNavigate();
    const location = useLocation();
    return (
      <div>
        <button type="button" onClick={() => navigate(-1)}>history-back</button>
        <button type="button" onClick={() => navigate(1)}>history-forward</button>
        {gotoTargets.map((target, index) => (
          <button key={target} type="button" onClick={() => navigate(target)}>
            goto-{index}
          </button>
        ))}
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
  const pdfApi = (
    pageData: unknown[],
    overrides: Record<string, unknown> = {}
  ) =>
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
      ...overrides,
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

  it('clears the stale page and block once the newest asset provably has no pages', async () => {
    // Asset 91 is the newest and is still importing, so no page of it can be
    // selected. `?page=20&block=31` belongs to asset 90 and must not stay in
    // the URL as though it described the file on screen (W15 residue).
    renderWorkbenchAt(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'a.pdf', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'b.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'IMPORTING', stage: 'IMPORT', createdAt: '2026-09-02T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'a page' },
          ],
          response: { status: 200 },
        })),
      }),
      '/spaces/7/sources/3?page=20&block=31'
    );

    await screen.findByTestId('processing-note');
    await waitFor(() => {
      expect(urlParams()).toEqual({});
    });
    expect(highlightedBlocks()).toBe(0);
    // Asset 90's page is not rendered either, so the URL and the screen now
    // describe the same (empty) page set.
    expect(screen.queryByText('a page')).not.toBeInTheDocument();
  });

  it('clears the stale page when the newest asset has no job of its own yet', async () => {
    renderWorkbenchAt(
      contentReadyApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'a.pdf', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'b.pdf', createdAt: '2026-09-02T10:00:00Z' },
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
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'a page' },
          ],
          response: { status: 200 },
        })),
      }),
      '/spaces/7/sources/3?page=20&block=31'
    );

    await screen.findByTestId('not-started-note');
    await waitFor(() => {
      expect(urlParams()).toEqual({});
    });
    expect(highlightedBlocks()).toBe(0);
  });

  it('leaves a deep link alone while the page list is still loading', async () => {
    // The other half of the rule above: an empty page set only proves
    // "no page of this asset" once the lists have arrived. Rewriting the URL
    // earlier would delete a perfectly valid `?page=&block=` before the reader
    // ever got to honor it.
    let releasePages: () => void = () => {};
    const pagesGate = new Promise<void>((resolve) => {
      releasePages = resolve;
    });

    renderWorkbenchAt(
      mockApiClient({
        getSource: vi.fn(async () => sourceOk),
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
        listSourcePages: vi.fn(async () => {
          await pagesGate;
          return {
            data: [
              { id: 21, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p1' },
              { id: 22, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p2' },
            ],
            response: { status: 200 },
          };
        }),
        listContentBlocks: vi.fn(async () => ({
          data: [
            { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'block 31' },
            { id: 32, blockType: 'PARAGRAPH', sortOrder: 1, normalizedText: 'block 32' },
          ],
          response: { status: 200 },
        })),
      }),
      '/spaces/7/sources/3?page=22&block=32'
    );

    // Assets and jobs have landed, the page list has not: the screen cannot
    // show a reader yet, and the URL keeps the deep link untouched.
    await screen.findByText('处理成功，但没有生成内容页面。');
    expect(urlParams()).toEqual({ page: '22', block: '32' });

    await act(async () => {
      releasePages();
    });

    await screen.findByTestId('page-indicator');
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
    expect(urlParams()).toEqual({ page: '22', block: '32' });
    await waitFor(() => {
      expect(highlightedBlocks()).toBe(1);
    });
  });

  it('honours a refreshed URL instead of falling back to the first page', async () => {
    // Refresh/reload re-enters at the URL as it stands, so a URL naming a
    // later page must select that page — not pages[0] with the state
    // disagreeing (the exact W15 claim).
    const listContentBlocks = vi.fn(
      async (
        _spaceId: number,
        _sourceId: number,
        _pageId?: number
      ): Promise<{ data: unknown[]; response: { status: number } }> => ({
        data: [
          { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'x' },
        ],
        response: { status: 200 },
      })
    );

    renderWorkbenchAt(
      pdfApi(
        [
          { id: 20, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p0' },
          { id: 21, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p1' },
        ],
        { listContentBlocks }
      ),
      '/spaces/7/sources/3?page=21'
    );

    await screen.findByTestId('page-indicator');
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
    expect(
      screen.getByRole('button', { name: 'Page 2' })
    ).toHaveAttribute('aria-current', 'page');
    expect(
      screen.getByRole('button', { name: 'Page 1' })
    ).not.toHaveAttribute('aria-current');
    // Nothing was rewritten: the URL is already consistent with the selection.
    expect(urlParams()).toEqual({ page: '21' });
    // And the content area really is page 21's, not the first page's.
    await waitFor(() => {
      expect(listContentBlocks).toHaveBeenCalledWith(7, 3, 21);
    });
    const requestedPages = listContentBlocks.mock.calls.map(
      (call) => call[2]
    );
    expect(requestedPages).not.toContain(20);
  });

  it('re-resolves a deep link that arrives for a different source', async () => {
    // A knowledge-point provenance link can name a page of another source.
    // Selection, URL and highlight all have to follow the source on screen.
    const fixture = (sourceId: number) =>
      sourceId === 4
        ? {
            assets: [
              { id: 95, originalName: 'other.pdf', createdAt: '2026-09-03T10:00:00Z' },
            ],
            jobs: [
              { id: 59, assetId: 95, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-03T10:00:01Z' },
            ],
            pages: [
              { id: 30, sourceAssetId: 95, pageOrder: 0, pageType: 'BODY', extractedText: 'source 4 page' },
            ],
            blocks: [
              { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'other block' },
            ],
          }
        : {
            assets: [
              { id: 90, originalName: 'this.pdf', createdAt: '2026-09-01T10:00:00Z' },
            ],
            jobs: [
              { id: 55, assetId: 90, status: 'PUBLISHED', stage: 'REVIEW', createdAt: '2026-09-01T10:00:01Z' },
            ],
            pages: [
              { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'this page' },
              { id: 21, sourceAssetId: 90, pageOrder: 1, pageType: 'BODY', extractedText: 'this page 2' },
            ],
            blocks: [
              { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'block 31' },
            ],
          };

    const user = userEvent.setup();
    const listContentBlocks = vi.fn(
      async (_spaceId: number, sourceId: number, _pageId?: number) => ({
        data: fixture(sourceId).blocks,
        response: { status: 200 },
      })
    );

    renderWorkbenchAt(
      mockApiClient({
        getSource: vi.fn(async (_spaceId: number, sourceId: number) => ({
          data: {
            id: sourceId,
            title: `来源 ${sourceId}`,
            sourceType: 'DESKTOP_UPLOAD',
            status: 'ACTIVE',
          },
          response: { status: 200 },
        })),
        listSourceAssets: vi.fn(
          async (_spaceId: number, sourceId: number) => ({
            data: fixture(sourceId).assets,
            response: { status: 200 },
          })
        ),
        listIngestionJobs: vi.fn(
          async (_spaceId: number, sourceId: number) => ({
            data: fixture(sourceId).jobs,
            response: { status: 200 },
          })
        ),
        listSourcePages: vi.fn(
          async (_spaceId: number, sourceId: number) => ({
            data: fixture(sourceId).pages,
            response: { status: 200 },
          })
        ),
        listContentBlocks,
      }),
      '/spaces/7/sources/3?page=21',
      ['/spaces/7/sources/4?page=21&block=31']
    );

    // Source 3: page 21 is its own, so it is honoured.
    await screen.findByTestId('page-indicator');
    expect(urlParams()).toEqual({ page: '21' });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');

    // Source 4 owns page 30 only, and its block happens to share id 31 with
    // source 3's block — the highlight must not follow the id across sources.
    await user.click(screen.getByRole('button', { name: 'goto-0' }));

    await waitFor(() => {
      expect(urlParams()).toEqual({ page: '30' });
    });
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 1 of 1');
    expect(
      screen.getByRole('button', { name: 'Page 1' })
    ).toHaveAttribute('aria-current', 'page');
    expect(highlightedBlocks()).toBe(0);
    // The blocks on screen are the ones read for source 4's page.
    expect(screen.getByText('other block')).toBeInTheDocument();
    expect(screen.queryByText('block 31')).not.toBeInTheDocument();

    const requested = listContentBlocks.mock.calls.map(
      (call) => `${call[1]}:${call[2]}` as string
    );
    expect(requested).toContain('4:30');
    expect(requested).not.toContain('4:21');
  });
});

/* ─────────────── W16: cross-module shortcuts from the entity on screen ─────────────── */

describe('SourceWorkbenchPage — note / ask-AI entry points (W16)', () => {
  /** Asset 91 PUBLISHED with two pages; page blocks carry one id-less row. */
  const readerApi = (overrides: Record<string, unknown> = {}) =>
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
        data: [
          { id: 20, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'p0' },
          { id: 21, sourceAssetId: 91, pageOrder: 1, pageType: 'BODY', extractedText: 'p1' },
        ],
        response: { status: 200 },
      })),
      listContentBlocks: vi.fn(async () => ({
        data: [
          { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'block 31' },
          { id: 32, blockType: 'PARAGRAPH', sortOrder: 1, normalizedText: 'block 32' },
          // A row the server sent without an id: no note link may invent one.
          { blockType: 'PARAGRAPH', sortOrder: 2, normalizedText: 'idless block' },
        ],
        response: { status: 200 },
      })),
      ...overrides,
    });

  async function renderReader(api = readerApi()) {
    renderWorkbench(api);
    await screen.findByTestId('page-indicator');
    return api;
  }

  it('starts a note on this source and opens the AI workspace from the header', async () => {
    await renderReader();

    // The source owns exactly one context id — its own — so that is the only
    // key the prefill carries.
    expect(screen.getByTestId('source-note-link')).toHaveAttribute(
      'href',
      '/spaces/7/notes?sourceId=3'
    );
    // C03: the AI page accepts nothing but its conversation param, so this is
    // a bare route — no context id, no invented query key.
    expect(screen.getByTestId('source-ai-link')).toHaveAttribute(
      'href',
      '/spaces/7/ai'
    );
    // …and the copy says so instead of implying an attachment.
    expect(screen.getByTestId('ai-entry-note')).toHaveTextContent(
      '不会自动附带'
    );
  });

  it('prefills a note with the page on screen and follows page changes', async () => {
    const user = userEvent.setup();
    await renderReader();

    expect(screen.getByTestId('page-note-link')).toHaveAttribute(
      'href',
      '/spaces/7/notes?sourceId=3&sourcePageId=20'
    );

    await user.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => {
      expect(screen.getByTestId('page-indicator')).toHaveTextContent(
        'Page 2 of 2'
      );
    });
    // The prefill tracks the selection (W15 owns the selection): a note can
    // never start pointed at a page the reader has left.
    expect(screen.getByTestId('page-note-link')).toHaveAttribute(
      'href',
      '/spaces/7/notes?sourceId=3&sourcePageId=21'
    );
  });

  it('prefills a note with the content block, its page and its source', async () => {
    await renderReader();

    const links = screen.getAllByTestId('block-note-link');
    // Only the two rows the server gave ids for; the id-less one gets no link.
    expect(links).toHaveLength(2);
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/spaces/7/notes?sourceId=3&sourcePageId=20&contentBlockId=31',
      '/spaces/7/notes?sourceId=3&sourcePageId=20&contentBlockId=32',
    ]);
    expect(screen.getByText('idless block')).toBeInTheDocument();
  });

  it('carries the current page into the block prefill, not the first page', async () => {
    const user = userEvent.setup();
    await renderReader();

    await user.click(screen.getByRole('button', { name: 'Page 2' }));
    await waitFor(() => {
      expect(screen.getByTestId('page-indicator')).toHaveTextContent(
        'Page 2 of 2'
      );
    });

    const links = screen.getAllByTestId('block-note-link');
    expect(links[0]).toHaveAttribute(
      'href',
      '/spaces/7/notes?sourceId=3&sourcePageId=21&contentBlockId=31'
    );
  });
});
