/**
 * SourceWorkbenchPage tests (FE-002B Day 2 architecture).
 *
 * Content section requires: latest asset + SUCCEEDED job + pages
 * with matching sourceAssetId. Tests mock the full chain.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SourceWorkbenchPage } from './SourceWorkbenchPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const sourceOk = {
  data: {
    id: 3,
    title: 'My Notes',
    sourceType: 'DESKTOP_UPLOAD',
    status: 'ACTIVE',
  },
  response: { status: 200 },
};

/** Full mock: asset 90 + SUCCEEDED job 55 + pages with sourceAssetId=90. */
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
          status: 'SUCCEEDED',
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
  await screen.findByRole('button', { name: /upload & ingest/i });
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
            { id: 56, assetId: 91, status: 'SUCCEEDED', stage: 'PUBLISHED' },
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
      screen.getByRole('button', { name: 'Previous' })
    ).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next' })).toBeEnabled();
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
          data: [{ id: 56, assetId: 91, status: 'SUCCEEDED', stage: 'PUBLISHED' }],
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
    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByTestId('page-indicator')).toHaveTextContent('Page 2 of 2');
  });
});

describe('SourceWorkbenchPage — stale content prevention', () => {
  it('shows processing note when latest job is RUNNING', async () => {
    renderWorkbench(
      contentReadyApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'SUCCEEDED', stage: 'PUBLISHED' },
            { id: 56, assetId: 91, status: 'RUNNING', stage: 'IMPORTING' },
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
          data: [{ id: 57, assetId: 92, status: 'SUCCEEDED', stage: 'PUBLISHED' }],
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
          data: [{ id: 58, assetId: 93, status: 'SUCCEEDED', stage: 'PUBLISHED' }],
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
              status: 'SUCCEEDED',
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
    // FAILED job has Retry button.
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('disables upload submit until file selected', async () => {
    renderWorkbench(contentReadyApi());
    await waitForWorkbench();
    expect(
      screen.getByRole('button', { name: /upload & ingest/i })
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
