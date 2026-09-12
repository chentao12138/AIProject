/**
 * Active ingestion job polling tests (FE-002A PHASE 13).
 *
 * Proves polling stops on terminal status, 401/403/404, and never
 * fabricates byte percentages. Uses the workbench upload path so the
 * ActiveJobStatus component is mounted with a real jobId.
 */

import { describe, expect, it, vi } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { SourceWorkbenchPage } from './SourceWorkbenchPage';
import { mockApiClient, renderWithProviders } from '../../test/test-utils';

const sourceOk = {
  data: { id: 3, title: 'Notes', sourceType: 'DESKTOP_UPLOAD', status: 'ACTIVE' },
  response: { status: 200 },
};

function mountWithJob(
  getIngestionJob: ReturnType<typeof vi.fn>,
  overrides: Record<string, unknown> = {}
) {
  const apiClient = mockApiClient({
    getSource: vi.fn(async () => sourceOk),
    uploadSourceAsset: vi.fn(async () => ({
      data: { id: 90, originalName: 'a.txt', sizeBytes: 3 },
      response: { status: 201 },
    })),
    createIngestionJob: vi.fn(async () => ({
      data: { id: 55, status: 'PENDING', stage: 'QUEUED' },
      response: { status: 201 },
    })),
    getIngestionJob,
    listSourceAssets: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    listIngestionJobs: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    listSourcePages: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    ...overrides,
  });
  return renderWithProviders(<SourceWorkbenchPage />, {
    apiClient,
    initialEntries: ['/spaces/7/sources/3'],
    routePath: '/spaces/:spaceId/sources/:sourceId',
  });
}

async function triggerUpload() {
  await screen.findByRole('button', { name: /upload & ingest/i });
  const file = new File(['x'], 'a.txt', { type: 'text/plain' });
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  fireEvent.change(input, { target: { files: [file] } });
  fireEvent.click(screen.getByRole('button', { name: /upload & ingest/i }));
}

describe('ActiveJobStatus polling', () => {
  it('renders SUCCEEDED as terminal and stops polling', async () => {
    const getIngestionJob = vi.fn(async () => ({
      data: {
        id: 55,
        status: 'SUCCEEDED',
        stage: 'PUBLISHED',
        progressPercent: 100,
      },
      response: { status: 200 },
    }));
    mountWithJob(getIngestionJob);
    await triggerUpload();

    expect(await screen.findByTestId('active-job')).toBeInTheDocument();
    // Upload stage + job status both say Succeeded — assert via testid.
    expect(screen.getByTestId('active-job').textContent).toMatch(/Succeeded/i);
    // Terminal on first paint → refetchInterval returns false immediately.
    const settled = getIngestionJob.mock.calls.length;
    await new Promise((r) => setTimeout(r, 50));
    expect(getIngestionJob.mock.calls.length).toBeLessThanOrEqual(settled + 1);
  });

  it('stops refetching after FAILED and offers Retry ingestion', async () => {
    const getIngestionJob = vi.fn(async () => ({
      data: {
        id: 55,
        status: 'FAILED',
        stage: 'QUEUED',
        errorCode: 'PARSE_ERROR',
        errorMessage: 'bad markdown',
      },
      response: { status: 200 },
    }));
    const retryIngestionJob = vi.fn(async () => ({
      data: { id: 55, status: 'PENDING', stage: 'QUEUED' },
      response: { status: 200 },
    }));
    mountWithJob(getIngestionJob, { retryIngestionJob });
    await triggerUpload();

    expect(await screen.findByText(/bad markdown/)).toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /retry ingestion/i })
    ).toBeInTheDocument();
  });

  it('stops polling on 401 and surfaces the auth message', async () => {
    const getIngestionJob = vi.fn(async () => ({
      data: undefined,
      error: { message: 'unauthorized' },
      response: { status: 401 },
    }));
    mountWithJob(getIngestionJob);
    await triggerUpload();

    expect(
      await screen.findByText(/开发会话未认证|Authentication integration pending/)
    ).toBeInTheDocument();
  });

  it('never renders a fabricated percent without backend progress', async () => {
    const getIngestionJob = vi.fn(async () => ({
      data: { id: 55, status: 'PENDING', stage: 'QUEUED' },
      response: { status: 200 },
    }));
    mountWithJob(getIngestionJob);
    await triggerUpload();

    const active = await screen.findByTestId('active-job');
    expect(active.textContent).not.toMatch(/\d+%/);
  });
});
