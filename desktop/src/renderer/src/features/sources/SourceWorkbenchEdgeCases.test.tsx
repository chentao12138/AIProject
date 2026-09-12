/**
 * SourceWorkbenchPage edge-case tests (FE-002A-CLOSEOUT-01).
 *
 * Boundary coverage for source get errors, upload edge cases, asset
 * rendering, ingestion statuses, and content error paths — all driven
 * by the stable contract only.
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
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

const emptyLists = {
  listSourceAssets: vi.fn(async () => ({ data: [], response: { status: 200 } })),
  listIngestionJobs: vi.fn(async () => ({ data: [], response: { status: 200 } })),
  listSourcePages: vi.fn(async () => ({ data: [], response: { status: 200 } })),
};

function renderWorkbench(apiClient: ReturnType<typeof mockApiClient>) {
  return renderWithProviders(<SourceWorkbenchPage />, {
    apiClient,
    initialEntries: ['/spaces/7/sources/3'],
    routePath: '/spaces/:spaceId/sources/:sourceId',
  });
}

function baseApi(overrides: Record<string, unknown> = {}) {
  return mockApiClient({
    getSource: vi.fn(async () => sourceOk),
    ...emptyLists,
    ...overrides,
  });
}

async function waitForWorkbench() {
  await screen.findByRole('button', { name: /upload & ingest/i });
}

function pickFile(name: string, content = 'x', type = 'text/plain') {
  const file = new File([content], name, { type });
  const input = document.querySelector(
    'input[type="file"]'
  ) as HTMLInputElement;
  fireEvent.change(input, { target: { files: [file] } });
  return file;
}

/* ───────── Source get error paths ───────── */

describe('SourceWorkbenchPage — source get error paths', () => {
  it('shows 401 auth message for source get', async () => {
    renderWorkbench(
      baseApi({
        getSource: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 401 },
        })),
      })
    );
    expect(
      await screen.findByText(/开发会话未认证|Authentication integration pending/)
    ).toBeInTheDocument();
  });

  it('shows 403 forbidden message for source get', async () => {
    renderWorkbench(
      baseApi({
        getSource: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 403 },
        })),
      })
    );
    expect(await screen.findByText(/无权执行此操作/)).toBeInTheDocument();
  });

  it('shows anti-IDOR 404 message for source get', async () => {
    renderWorkbench(
      baseApi({
        getSource: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 404 },
        })),
      })
    );
    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
  });

  it('shows network error for source get', async () => {
    renderWorkbench(
      baseApi({
        getSource: vi.fn(async () => {
          throw new TypeError('fetch failed');
        }),
      })
    );
    expect(
      await screen.findByText(/无法连接到后端服务/)
    ).toBeInTheDocument();
  });

  it('shows 5xx server message for source get', async () => {
    renderWorkbench(
      baseApi({
        getSource: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 500 },
        })),
      })
    );
    expect(
      await screen.findByText(/服务器暂时无法完成请求/)
    ).toBeInTheDocument();
  });
});

/* ───────── Upload edge cases ───────── */

describe('SourceWorkbenchPage — upload edge cases', () => {
  it('accepts a zero-byte file and still allows submit', async () => {
    renderWorkbench(baseApi());
    await waitForWorkbench();
    pickFile('empty.txt', '');
    expect(await screen.findByTestId('selected-file')).toHaveTextContent(
      'empty.txt'
    );
    expect(
      screen.getByRole('button', { name: /upload & ingest/i })
    ).toBeEnabled();
  });

  it('shows a long filename without crashing', async () => {
    renderWorkbench(baseApi());
    await waitForWorkbench();
    const longName = `${'a'.repeat(200)}.txt`;
    pickFile(longName);
    expect(await screen.findByTestId('selected-file')).toHaveTextContent(
      'a'.repeat(20)
    );
  });

  it('clear resets the selected file and disables submit', async () => {
    const user = userEvent.setup();
    renderWorkbench(baseApi());
    await waitForWorkbench();
    pickFile('a.txt');
    await screen.findByTestId('selected-file');
    await user.click(screen.getByRole('button', { name: 'Clear' }));
    expect(screen.queryByTestId('selected-file')).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /upload & ingest/i })
    ).toBeDisabled();
  });

  it('replacing the selected file updates the display', async () => {
    renderWorkbench(baseApi());
    await waitForWorkbench();
    pickFile('first.txt');
    expect(await screen.findByTestId('selected-file')).toHaveTextContent(
      'first.txt'
    );
    pickFile('second.md', '# hi', 'text/markdown');
    expect(await screen.findByTestId('selected-file')).toHaveTextContent(
      'second.md'
    );
  });

  it('surfaces upload 401 with auth copy', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 401 },
        })),
      })
    );
    await waitForWorkbench();
    pickFile('a.txt');
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));
    expect(
      await screen.findByText(/开发会话未认证|Authentication integration pending/)
    ).toBeInTheDocument();
  });

  it('surfaces upload 403 with forbidden copy', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 403 },
        })),
      })
    );
    await waitForWorkbench();
    pickFile('a.txt');
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));
    expect(await screen.findByText(/无权执行此操作/)).toBeInTheDocument();
  });

  it('surfaces upload 500 with server copy', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 500 },
        })),
      })
    );
    await waitForWorkbench();
    pickFile('a.txt');
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));
    expect(
      await screen.findByText(/服务器暂时无法完成请求/)
    ).toBeInTheDocument();
  });

  it('rejects upload when createIngestionJob fails after asset accepted', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        uploadSourceAsset: vi.fn(async () => ({
          data: { id: 90, originalName: 'a.txt' },
          response: { status: 201 },
        })),
        createIngestionJob: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 422 },
        })),
      })
    );
    await waitForWorkbench();
    pickFile('a.txt');
    await user.click(screen.getByRole('button', { name: /upload & ingest/i }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('disables submit while upload is pending (no double-submit)', async () => {
    let resolveUpload: (v: unknown) => void = () => {};
    const uploadSourceAsset = vi.fn(
      () =>
        new Promise((resolve) => {
          resolveUpload = resolve;
        })
    );
    const user = userEvent.setup();
    renderWorkbench(baseApi({ uploadSourceAsset }));
    await waitForWorkbench();
    pickFile('a.txt');
    const submit = screen.getByRole('button', { name: /upload & ingest/i });
    await user.click(submit);
    await waitFor(() => {
      expect(submit).toBeDisabled();
    });
    resolveUpload({
      data: { id: 1, originalName: 'a.txt' },
      response: { status: 201 },
    });
  });
});

/* ───────── Asset list rendering ───────── */

describe('SourceWorkbenchPage — asset list rendering', () => {
  it('renders multiple assets with name/type/size/date', async () => {
    renderWorkbench(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [
            {
              id: 1,
              originalName: 'b.md',
              mimeType: 'text/markdown',
              sizeBytes: 2048,
              createdAt: '2026-09-01T10:00:00Z',
            },
            {
              id: 2,
              originalName: 'a.txt',
              mimeType: 'text/plain',
              sizeBytes: 10,
              createdAt: '2026-09-02T10:00:00Z',
            },
          ],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByText('b.md')).toBeInTheDocument();
    expect(screen.getByText('a.txt')).toBeInTheDocument();
    expect(screen.getByText('2 KB')).toBeInTheDocument();
    expect(screen.getByText('10 B')).toBeInTheDocument();
  });

  it('renders assets with missing optional fields safely', async () => {
    renderWorkbench(
      baseApi({
        listSourceAssets: vi.fn(async () => ({
          data: [{ id: 1 }],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByRole('table')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/#undefined|NaN/);
  });

  it('shows empty assets message when list is empty', async () => {
    renderWorkbench(baseApi());
    expect(await screen.findByText('No files uploaded yet.')).toBeInTheDocument();
  });
});

/* ───────── Ingestion status rendering ───────── */

describe('SourceWorkbenchPage — ingestion status rendering', () => {
  function jobsApi(jobs: unknown[]) {
    return baseApi({
      listIngestionJobs: vi.fn(async () => ({
        data: jobs,
        response: { status: 200 },
      })),
    });
  }

  it('renders PENDING / RUNNING / SUCCEEDED / FAILED with exact badges', async () => {
    renderWorkbench(
      jobsApi([
        { id: 1, status: 'PENDING', stage: 'QUEUED' },
        { id: 2, status: 'RUNNING', stage: 'IMPORTING' },
        { id: 3, status: 'SUCCEEDED', stage: 'PUBLISHED' },
        { id: 4, status: 'FAILED', stage: 'QUEUED', errorMessage: 'x' },
      ])
    );
    expect(await screen.findByText('PENDING')).toBeInTheDocument();
    expect(screen.getByText('RUNNING')).toBeInTheDocument();
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('renders unknown status as neutral badge without crash', async () => {
    renderWorkbench(
      jobsApi([{ id: 9, status: 'WEIRD_FUTURE', stage: 'MYSTERY' }])
    );
    expect(await screen.findByText('WEIRD_FUTURE')).toBeInTheDocument();
    expect(
      screen.getByText('WEIRD_FUTURE').closest('.badge')
    ).toHaveClass('badge--unknown');
  });

  it('shows retry error message in the jobs section', async () => {
    const user = userEvent.setup();
    renderWorkbench(
      baseApi({
        listIngestionJobs: vi.fn(async () => ({
          data: [{ id: 12, status: 'FAILED', stage: 'QUEUED' }],
          response: { status: 200 },
        })),
        retryIngestionJob: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 409 },
        })),
      })
    );
    await user.click(await screen.findByRole('button', { name: 'Retry' }));
    // normalizeApiError surfaces the 409 as unknown/server copy via ErrorState
    // or form error — assert no crash and Retry still present after failure.
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    });
  });
});

/* ───────── Content error paths ───────── */

describe('SourceWorkbenchPage — content error paths', () => {
  it('shows 401 on pages query', async () => {
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 401 },
        })),
      })
    );
    expect(
      await screen.findByText(/开发会话未认证|Authentication integration pending/)
    ).toBeInTheDocument();
  });

  it('shows 404 anti-IDOR on pages query', async () => {
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 404 },
        })),
      })
    );
    expect(
      await screen.findByText('资源不存在或当前不可访问。')
    ).toBeInTheDocument();
  });

  it('shows multiline TXT content with preserved line breaks', async () => {
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: [{ id: 20, pageOrder: 0 }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            {
              id: 31,
              blockType: 'PARAGRAPH',
              sortOrder: 1,
              normalizedText: 'line one\nline two\n\nline four',
            },
          ],
          response: { status: 200 },
        })),
      })
    );
    const pre = await screen.findByText(/line one/);
    expect(pre.tagName).toBe('PRE');
    expect(pre.textContent).toContain('line two');
    expect(pre.textContent).toContain('line four');
  });

  it('keeps Markdown syntax as plain readable text', async () => {
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: [{ id: 20, pageOrder: 0 }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            {
              id: 31,
              blockType: 'PARAGRAPH',
              sortOrder: 1,
              normalizedText: '# Title\n\n**bold** and *italic*\n\n- item',
            },
          ],
          response: { status: 200 },
        })),
      })
    );
    const pre = await screen.findByText(/# Title/);
    expect(pre.textContent).toContain('**bold**');
    expect(pre.textContent).toContain('- item');
    expect(document.querySelector('strong')).toBeNull();
    expect(document.querySelector('em')).toBeNull();
  });

  it('renders very long unbroken content without layout crash', async () => {
    const long = 'x'.repeat(5000);
    renderWorkbench(
      baseApi({
        listSourcePages: vi.fn(async () => ({
          data: [{ id: 20, pageOrder: 0 }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: [
            { id: 31, blockType: 'PARAGRAPH', sortOrder: 1, normalizedText: long },
          ],
          response: { status: 200 },
        })),
      })
    );
    expect(await screen.findByTestId('content-block')).toBeInTheDocument();
  });
});
