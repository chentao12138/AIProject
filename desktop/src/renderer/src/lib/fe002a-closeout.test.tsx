/**
 * FE-002A-CLOSEOUT supplementary pure-function and integration edges.
 * All assertions target real contract behavior — no filler.
 */

import { describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import { formatBytes, formatDateTime } from './format';
import {
  classifyIngestionStatus,
  formatIngestionStage,
  isIngestionPollable,
  isIngestionRetryable,
} from './ingestion-status';
import { mockApiClient, renderWithProviders } from '../test/test-utils';
import { SourceWorkbenchPage } from '../features/sources/SourceWorkbenchPage';

describe('formatBytes — additional edges', () => {
  it('handles MAX_SAFE_INTEGER without overflow crash', () => {
    const result = formatBytes(Number.MAX_SAFE_INTEGER);
    expect(result).toMatch(/TB|GB/);
    expect(result).not.toContain('undefined');
  });

  it('rounds KB values to one decimal when under 100', () => {
    // 1178/1024 ≈ 1.150 → Math.round(11.50)/10 = 1.2
    expect(formatBytes(1178)).toBe('1.2 KB');
  });

  it('formats values just under 1 MB boundary', () => {
    expect(formatBytes(1024 * 1024 - 1)).toBe('1024 KB');
  });
});

describe('formatDateTime — already covered baseline, ensure null path', () => {
  it('returns — for null', () => {
    expect(formatDateTime(null)).toBe('—');
  });
});

describe('ingestion-status — additional edges', () => {
  it('trims whitespace around status before classify', () => {
    expect(classifyIngestionStatus('  PENDING  ')).toBe('non-terminal');
    expect(classifyIngestionStatus('\tFAILED\n')).toBe('failure-terminal');
  });

  it('formatIngestionStage humanizes EXTRACTING and STRUCTURING', () => {
    expect(formatIngestionStage('EXTRACTING', 'RUNNING')).toBe('Extracting');
    expect(formatIngestionStage('STRUCTURING', 'RUNNING')).toBe('Structuring');
  });

  it('formatIngestionStage ignores stage when terminal', () => {
    expect(formatIngestionStage('ANYTHING', 'SUCCEEDED')).toBe('Succeeded');
    expect(formatIngestionStage('ANYTHING', 'FAILED')).toBe('Failed');
  });

  it('isIngestionPollable treats unknown as non-pollable', () => {
    expect(isIngestionPollable('QUEUED')).toBe(false); // stage ≠ status
    expect(isIngestionPollable('partial')).toBe(false);
  });

  it('isIngestionRetryable is case-insensitive but exact-value only', () => {
    expect(isIngestionRetryable(' failed ')).toBe(true);
    expect(isIngestionRetryable('FAILED_RETRY')).toBe(false);
    expect(isIngestionRetryable('FAIL')).toBe(false);
  });
});

/* Integration: content 500 + workbench invalid source in nested route */

const sourceOk = {
  data: { id: 3, title: 'N', sourceType: 'DESKTOP_UPLOAD', status: 'ACTIVE' },
  response: { status: 200 },
};

function workbenchApi(overrides: Record<string, unknown> = {}) {
  return mockApiClient({
    getSource: vi.fn(async () => sourceOk),
    listSourceAssets: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    listIngestionJobs: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    listSourcePages: vi.fn(async () => ({ data: [], response: { status: 200 } })),
    ...overrides,
  });
}

describe('SourceWorkbenchPage — content 500 and blocks 403', () => {
  it('shows 5xx on pages query', async () => {
    renderWithProviders(<SourceWorkbenchPage />, {
      apiClient: workbenchApi({
        listSourcePages: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 500 },
        })),
      }),
      initialEntries: ['/spaces/7/sources/3'],
      routePath: '/spaces/:spaceId/sources/:sourceId',
    });
    expect(
      await screen.findByText(/服务器暂时无法完成请求/)
    ).toBeInTheDocument();
  });

  it('shows 403 on content-blocks query when page is selected', async () => {
    renderWithProviders(<SourceWorkbenchPage />, {
      apiClient: workbenchApi({
        listSourcePages: vi.fn(async () => ({
          data: [{ id: 20, pageOrder: 0 }],
          response: { status: 200 },
        })),
        listContentBlocks: vi.fn(async () => ({
          data: undefined,
          error: { message: 'no' },
          response: { status: 403 },
        })),
      }),
      initialEntries: ['/spaces/7/sources/3'],
      routePath: '/spaces/:spaceId/sources/:sourceId',
    });
    expect(await screen.findByText(/无权执行此操作/)).toBeInTheDocument();
  });
});
