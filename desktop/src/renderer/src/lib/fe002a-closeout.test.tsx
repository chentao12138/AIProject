/**
 * FE-002A-CLOSEOUT supplementary pure-function and integration edges.
 * All assertions target real contract behavior — no filler.
 */

import { describe, expect, it } from 'vitest';
import { formatBytes, formatDateTime } from './format';
import {
  classifyIngestionStatus,
  formatIngestionStage,
  isIngestionPollable,
  isIngestionRetryable,
} from './ingestion-status';

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
