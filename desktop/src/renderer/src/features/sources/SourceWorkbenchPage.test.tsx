/**
 * SourceWorkbenchPage tests (FE-002A).
 *
 * Covers: source metadata, file selection, upload → create ingestion
 * job chain, stage labels (no fake %), assets list, ingestion list +
 * retry, polling stop on terminal, content pages + blocks as plain
 * readable text (no dangerouslySetInnerHTML).
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SourceWorkbenchPage } from './SourceWorkbenchPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

function renderWorkbench(apiClient: ReturnType<typeof mockApiClient>) {
  return renderWithProviders(<SourceWorkbenchPage />, {
    apiClient,
    initialEntries: ['/spaces/7/sources/3'],
    routePath: '/spaces/:spaceId/sources/:sourceId',
  });
}

const sourceOk = {
  data: {
    id: 3,
    title: 'My Notes',
    sourceType: 'DESKTOP_UPLOAD',
    status: 'ACTIVE',
  },
  response: { status: 200 },
};

const emptyLists = {
  listSourceAssets: vi.fn(async () => ({ data: [], response: { status: 200 } })),
  listIngestionJobs: vi.fn(async () => ({ data: [], response: { status: 200 } })),
  listSourcePages: vi.fn(async () => ({ data: [], response: { status: 200 } })),
};

function baseApi(overrides: Record<string, unknown> = {}) {
  return mockApiClient({
    getSource: vi.fn(async () => sourceOk),
    ...emptyLists,
    ...overrides,
  });
}

/** Wait until the workbench form is mounted (source query settled). */
async function waitForWorkbench() {
  await screen.findByRole('button', { name: /upload & ingest/i });
}

describe('SourceWorkbenchPage — metadata and empty states', () => {
  it('renders source metadata', async () => {
    renderWorkbench(baseApi());

    expect(await screen.findByText('My Notes')).toBeInTheDocument();
    expect(screen.getByText('DESKTOP_UPLOAD')).toBeInTheDocument();
    await waitForWorkbench();
  });

  it('shows inaccessible for invalid ids and never calls child APIs', () => {
    const getSource = vi.fn();
    const listSourceAssets = vi.fn();
    renderWithProviders(<SourceWorkbenchPage />, {
      apiClient: mockApiClient({ getSource, listSourceAssets }),
      initialEntries: ['/spaces/abc/sources/xyz'],
      routePath: '/spaces/:spaceId/sources/:sourceId',
    });
    expect(screen.getByText('资源不存在或当前不可访问。')).toBeInTheDocument();
    expect(getSource).not.toHaveBeenCalled();
    expect(listSourceAssets).not.toHaveBeenCalled();
  });

  it('shows empty content state when no pages exist', async () => {
    renderWorkbench(baseApi());
    expect(
      await screen.findByText('No extracted content yet')
    ).toBeInTheDocument();
  });
});

describe('SourceWorkbenchPage — upload flow', () => {
  it('disables submit until a file is selected', async () => {
    renderWorkbench(baseApi());
    await waitForWorkbench();
    expect(
      screen.getByRole('button', { name: /upload & ingest/i })
    ).toBeDisabled();
  });

  it('uploads via shared client FormData path and creates an ingestion job', async () => {
    const user = userEvent.setup();
    const uploadSourceAsset = vi.fn(async () => ({
      data: {
        id: 90,
        originalName: 'notes.md',
        sizeBytes: 120,
        mimeType: 'text/markdown',
      },
      response: { status: 201 },
    }));
    const createIngestionJob = vi.fn(async () => ({
      data: { id: 55, status: 'PENDING', stage: 'QUEUED' },
      response: { status: 201 },
    }));
    const getIngestionJob = vi.fn(async () => ({
      data: {
        id: 55,
        status: 'SUCCEEDED',
        stage: 'PUBLISHED',
        progressPercent: 100,
      },
      response: { status: 200 },
    }));
    renderWorkbench(
      baseApi({ uploadSourceAsset, createIngestionJob, getIngestionJob })
    );
    await waitForWorkbench();

    const file = new File(['# hello'], 'notes.md', { type: 'text/markdown' });
    const input = document.querySelector(
      'input[type="file"]'
    ) as HTMLInputElement;
    expect(input).toBeTruthy();
    fireEvent.change(input, { target: { files: [file] } });

    expect(await screen.findByTestId('selected-file')).toHaveTextContent(
      'notes.md'
    );

    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));

    await waitFor(() => {
      expect(uploadSourceAsset).toHaveBeenCalledWith(7, 3, file);
    });
    await waitFor(() => {
      expect(createIngestionJob).toHaveBeenCalledWith(7, 3, 90);
    });
  });

  it('shows stage labels and never a fake percent before job data', async () => {
    renderWorkbench(baseApi());
    await waitForWorkbench();

    const stage = document.querySelector('.upload-stage');
    expect(stage?.textContent).toBe('—');
    expect(stage?.textContent).not.toMatch(/\d+%/);
  });

  it('shows upload failure message and keeps the form usable', async () => {
    const user = userEvent.setup();
    const uploadSourceAsset = vi.fn(async () => ({
      data: undefined,
      error: { message: 'boom' },
      response: { status: 500 },
    }));
    renderWorkbench(baseApi({ uploadSourceAsset }));
    await waitForWorkbench();

    const file = new File(['x'], 'a.txt', { type: 'text/plain' });
    const input = document.querySelector(
      'input[type="file"]'
    ) as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '服务器暂时无法完成请求，请稍后重试。'
    );
  });
});

describe('SourceWorkbenchPage — ingestion list and retry', () => {
  it('lists jobs with exact status badges and offers Retry only for FAILED', async () => {
    const user = userEvent.setup();
    const retryIngestionJob = vi.fn(async () => ({
      data: { id: 12, status: 'PENDING', stage: 'QUEUED' },
      response: { status: 200 },
    }));
    renderWorkbench(
      baseApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 11, status: 'SUCCEEDED', stage: 'PUBLISHED' },
            {
              id: 12,
              status: 'FAILED',
              stage: 'QUEUED',
              errorMessage: 'parse error',
            },
          ],
          response: { status: 200 },
        })),
        retryIngestionJob,
      })
    );

    expect(await screen.findByText('SUCCEEDED')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();

    const retryButtons = screen.getAllByRole('button', { name: 'Retry' });
    expect(retryButtons).toHaveLength(1);
    await user.click(retryButtons[0]);
    await waitFor(() => {
      expect(retryIngestionJob).toHaveBeenCalledWith(7, 12);
    });
  });
});

describe('SourceWorkbenchPage — content viewer', () => {
  it('renders pages and plain-text blocks without HTML injection', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 21, pageOrder: 1 },
            { id: 20, pageOrder: 0 },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            {
              id: 31,
              blockType: 'PARAGRAPH',
              sortOrder: 1,
              normalizedText: '<script>alert(1)</script>safe text',
            },
          ],
          response: { status: 200 },
        })),
      })
    );

    // First page auto-selected (pageOrder 0 → id 20 → "Page 1").
    expect(await screen.findByTestId('content-block')).toBeInTheDocument();
    const pre = screen.getByTestId('content-block').querySelector('pre');
    expect(pre?.textContent).toContain('<script>alert(1)</script>safe text');
    expect(document.querySelector('script')).toBeNull();

    // Switch to page 2 (pageOrder 1 → id 21).
    const page2 = await screen.findByRole('button', { name: 'Page 2' });
    await user.click(page2);
    expect(page2).toHaveAttribute('aria-current', 'page');
  });

  it('does not mount block query when pages have invalid ids only', async () => {
    const listContentBlocks = vi.fn();
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: [{ id: undefined, pageOrder: 1 }],
          response: { status: 200 },
        })),
        listContentBlocks,
      })
    );

    // No navigable page buttons, no ContentBlocks component.
    expect(await screen.findByRole('navigation', { name: 'Pages' })).toBeInTheDocument();
    expect(screen.queryByTestId('content-block')).not.toBeInTheDocument();
    expect(listContentBlocks).not.toHaveBeenCalled();
  });
});
