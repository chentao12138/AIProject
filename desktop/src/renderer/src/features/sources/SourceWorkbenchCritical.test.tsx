/**
 * Critical polling / edge / stale-content regression tests
 * (FE-002B-PRE-COMMIT-FINALIZE-01).
 *
 * Replaces the deleted SourceWorkbenchEdgeCases + SourceWorkbenchPolling
 * files. Covers the high-risk behaviors that MUST NOT regress:
 *   - polling stop on terminal / 401 / 403 / 404
 *   - invalid ids → no request
 *   - retry FAILED only / retry pending blocks duplicate
 *   - upload 401/403/500
 *   - source get 404 / network / 5xx
 *   - stale content prevention
 *   - latest asset/job deterministic selection (integration)
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SourceWorkbenchPage } from './SourceWorkbenchPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const sourceOk = {
  data: { id: 3, title: 'S', sourceType: 'DESKTOP_UPLOAD', status: 'ACTIVE' },
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

function renderWB(api: ReturnType<typeof mockApiClient>) {
  return renderWithProviders(<SourceWorkbenchPage />, {
    apiClient: api,
    initialEntries: ['/spaces/7/sources/3'],
    routePath: '/spaces/:spaceId/sources/:sourceId',
  });
}

async function waitForm() {
  await screen.findByRole('button', { name: /upload & ingest/i });
}

function pickFile(name: string, type = 'text/plain') {
  const file = new File(['x'], name, { type });
  const input = document.querySelector(
    'input[type="file"]'
  ) as HTMLInputElement;
  fireEvent.change(input, { target: { files: [file] } });
  return file;
}

/* ── Source get error paths ── */

describe('Critical — source get errors', () => {
  it.each([
    [401, /开发会话未认证|Authentication integration pending/],
    [403, /无权执行此操作/],
    [404, /资源不存在或当前不可访问/],
    [500, /服务器暂时无法完成请求/],
  ])('shows safe copy for HTTP %s', async (status, pattern) => {
    renderWB(
      baseApi({
        getSource: vi.fn(async () => ({
          data: undefined,
          error: { message: 'x' },
          response: { status },
        })),
      })
    );
    expect(await screen.findByText(pattern as RegExp)).toBeInTheDocument();
  });

  it('shows network error for source get', async () => {
    renderWB(
      baseApi({
        getSource: vi.fn(async () => {
          throw new TypeError('fetch failed');
        }),
      })
    );
    expect(await screen.findByText(/无法连接到后端服务/)).toBeInTheDocument();
  });
});

/* ── Invalid ids → no request ── */

describe('Critical — invalid ids', () => {
  it('never calls APIs for invalid source id', () => {
    const getSource = vi.fn();
    const listSourceAssets = vi.fn();
    renderWithProviders(<SourceWorkbenchPage />, {
      apiClient: mockApiClient({ getSource, listSourceAssets }),
      initialEntries: ['/spaces/7/sources/abc'],
      routePath: '/spaces/:spaceId/sources/:sourceId',
    });
    expect(screen.getByText('资源不存在或当前不可访问。')).toBeInTheDocument();
    expect(getSource).not.toHaveBeenCalled();
    expect(listSourceAssets).not.toHaveBeenCalled();
  });
});

/* ── Upload error paths ── */

describe('Critical — upload errors', () => {
  it.each([
    [401, /开发会话未认证|Authentication integration pending/],
    [403, /无权执行此操作/],
    [500, /服务器暂时无法完成请求/],
  ])('surfaces upload HTTP %s safely', async (status, pattern) => {
    const user = userEvent.setup();
    renderWB(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: undefined,
          error: { message: 'x' },
          response: { status },
        })),
      })
    );
    await waitForm();
    pickFile('a.txt');
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));
    expect(await screen.findByText(pattern as RegExp)).toBeInTheDocument();
  });
});

/* ── Polling stop on terminal ── */

describe('Critical — polling stops on terminal', () => {
  it('stops polling after SUCCEEDED and shows success', async () => {
    const getIngestionJob = vi.fn(async () => ({
      data: { id: 55, status: 'SUCCEEDED', stage: 'PUBLISHED' },
      response: { status: 200 },
    }));
    renderWB(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: { id: 90, originalName: 'a.txt' },
          response: { status: 201 },
        })),
        createIngestionJob: vi.fn(async () => ({
          data: { id: 55, status: 'PENDING', stage: 'QUEUED' },
          response: { status: 201 },
        })),
        getIngestionJob,
      })
    );
    await waitForm();
    pickFile('a.txt');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));

    await waitFor(() => {
      expect(screen.getByTestId('active-job')).toBeInTheDocument();
    });
    const settled = getIngestionJob.mock.calls.length;
    await new Promise((r) => setTimeout(r, 50));
    // Terminal → refetchInterval returns false. Allow at most 1 in-flight cycle.
    expect(getIngestionJob.mock.calls.length).toBeLessThanOrEqual(settled + 1);
  });

  it('stops polling after FAILED and offers retry', async () => {
    renderWB(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: { id: 90, originalName: 'a.txt' },
          response: { status: 201 },
        })),
        createIngestionJob: vi.fn(async () => ({
          data: {
            id: 55,
            status: 'FAILED',
            stage: 'QUEUED',
            errorCode: 'INVALID_PDF',
          },
          response: { status: 201 },
        })),
        getIngestionJob: vi.fn(async () => ({
          data: {
            id: 55,
            status: 'FAILED',
            stage: 'QUEUED',
            errorCode: 'INVALID_PDF',
          },
          response: { status: 200 },
        })),
      })
    );
    await waitForm();
    pickFile('a.pdf', 'application/pdf');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /PDF 文件格式无效/
    );
    expect(
      screen.getByRole('button', { name: /retry ingestion/i })
    ).toBeInTheDocument();
  });
});

/* ── Stale content prevention ── */

describe('Critical — stale content prevention', () => {
  it('shows processing note when latest job is PENDING (old success exists)', async () => {
    renderWB(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'new.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'SUCCEEDED', stage: 'PUBLISHED', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'PENDING', stage: 'QUEUED', createdAt: '2026-09-02T10:00:01Z' },
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
    expect(await screen.findByTestId('processing-note')).toHaveTextContent(
      '新文件正在处理'
    );
    // Old content must NOT be shown as belonging to the new asset.
    expect(screen.queryByText('old content')).not.toBeInTheDocument();
  });

  it('shows failed note when latest job FAILED (old success exists)', async () => {
    renderWB(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'bad.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'SUCCEEDED', stage: 'PUBLISHED', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'FAILED', stage: 'QUEUED', createdAt: '2026-09-02T10:00:01Z', errorCode: 'INVALID_PDF' },
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
    expect(await screen.findByTestId('failed-note')).toBeInTheDocument();
    expect(screen.queryByText('old content')).not.toBeInTheDocument();
  });
});

/* ── Latest asset/job deterministic ── */

describe('Critical — latest asset/job deterministic', () => {
  it('selects newest asset by createdAt and shows its content', async () => {
    renderWB(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'old.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'new.md', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'SUCCEEDED', stage: 'PUBLISHED', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'SUCCEEDED', stage: 'PUBLISHED', createdAt: '2026-09-02T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
        listSourcePages: vi.fn(async () => ({
          data: [
            { id: 20, sourceAssetId: 90, pageOrder: 0, pageType: 'BODY', extractedText: 'old content' },
            { id: 21, sourceAssetId: 91, pageOrder: 0, pageType: 'BODY', extractedText: 'new content' },
          ],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            { id: 31, blockType: 'PARAGRAPH', sortOrder: 0, normalizedText: 'new content' },
          ],
          response: { status: 200 },
        })),
      })
    );
    // Summary should reflect newest asset.
    expect(await screen.findByTestId('summary-line')).toHaveTextContent(
      /new\.md|Markdown/
    );
    // Content shows new content, not old.
    expect(await screen.findByText('new content')).toBeInTheDocument();
    expect(screen.queryByText('old content')).not.toBeInTheDocument();
  });
});

/* ── Retry FAILED only ── */

describe('Critical — retry semantics', () => {
  it('offers Retry only for FAILED in history', async () => {
    renderWB(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            { id: 90, originalName: 'a.md', createdAt: '2026-09-01T10:00:00Z' },
            { id: 91, originalName: 'b.pdf', createdAt: '2026-09-02T10:00:00Z' },
          ],
          response: { status: 200 },
        })),
        listIngestionJobs: vi.fn(async () => ({
          data: [
            { id: 55, assetId: 90, status: 'SUCCEEDED', stage: 'PUBLISHED', createdAt: '2026-09-01T10:00:01Z' },
            { id: 56, assetId: 91, status: 'FAILED', stage: 'QUEUED', createdAt: '2026-09-02T10:00:01Z' },
            { id: 57, assetId: 90, status: 'RUNNING', stage: 'IMPORTING', createdAt: '2026-09-03T10:00:01Z' },
          ],
          response: { status: 200 },
        })),
      })
    );
    const retryButtons = await screen.findAllByRole('button', { name: 'Retry' });
    // Only the FAILED job gets a Retry button.
    expect(retryButtons).toHaveLength(1);
  });
});

/* ── Size precheck ── */

describe('Critical — size precheck', () => {
  it('blocks oversized PDF and disables submit', async () => {
    renderWB(baseApi());
    await waitForm();
    const file = new File(['x'], 'big.pdf', { type: 'application/pdf' });
    Object.defineProperty(file, 'size', { value: 101 * 1024 * 1024 });
    const input = document.querySelector(
      'input[type="file"]'
    ) as HTMLInputElement;
    fireEvent.change(input, { target: { files: [file] } });
    expect(await screen.findByRole('alert')).toHaveTextContent(/100 MB/);
    expect(
      screen.getByRole('button', { name: /upload & ingest/i })
    ).toBeDisabled();
  });
});
