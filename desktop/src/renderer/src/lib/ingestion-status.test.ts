/**
 * Ingestion status model tests (FE-002A PHASE 12).
 *
 * Exact matching only — no substring classification. Unknown statuses
 * stay neutral and never crash polling logic.
 */

import { describe, expect, it } from 'vitest';
import {
  UPLOAD_STAGE_LABELS,
  classifyIngestionStatus,
  formatIngestionStage,
  isIngestionPollable,
  isIngestionRetryable,
} from './ingestion-status';

describe('classifyIngestionStatus', () => {
  it('classifies the worker pipeline as non-terminal', () => {
    for (const status of ['QUEUED', 'IMPORTING', 'EXTRACTING', 'STRUCTURING', 'AI_PROCESSING']) {
      expect(classifyIngestionStatus(status)).toBe('non-terminal');
    }
    expect(classifyIngestionStatus('queued')).toBe('non-terminal');
  });

  it('classifies PUBLISHED and NEEDS_REVIEW as success-terminal', () => {
    expect(classifyIngestionStatus('PUBLISHED')).toBe('success-terminal');
    expect(classifyIngestionStatus('NEEDS_REVIEW')).toBe('success-terminal');
  });

  it('classifies FAILED and PARTIAL_FAILED as failure-terminal', () => {
    expect(classifyIngestionStatus('FAILED')).toBe('failure-terminal');
    expect(classifyIngestionStatus('PARTIAL_FAILED')).toBe('failure-terminal');
  });

  it('classifies unknown / missing / lookalikes as unknown', () => {
    expect(classifyIngestionStatus('UNDEFINED')).toBe('unknown');
    expect(classifyIngestionStatus('SUCCESS')).toBe('unknown');
    expect(classifyIngestionStatus('FAIL')).toBe('unknown');
    expect(classifyIngestionStatus('RUNNING')).toBe('unknown');
    expect(classifyIngestionStatus('')).toBe('unknown');
    expect(classifyIngestionStatus(undefined)).toBe('unknown');
    expect(classifyIngestionStatus(null)).toBe('unknown');
  });

  it('does not use substring matching', () => {
    expect(classifyIngestionStatus('SUPERSEDED')).toBe('unknown');
    expect(classifyIngestionStatus('NOT_QUEUED')).toBe('unknown');
    expect(classifyIngestionStatus('FAILEDLY')).toBe('unknown');
  });
});

describe('isIngestionPollable', () => {
  it('is true only while the worker can still advance the job', () => {
    expect(isIngestionPollable('QUEUED')).toBe(true);
    expect(isIngestionPollable('IMPORTING')).toBe(true);
    expect(isIngestionPollable('PUBLISHED')).toBe(false);
    expect(isIngestionPollable('PARTIAL_FAILED')).toBe(false);
    expect(isIngestionPollable('FAILED')).toBe(false);
    expect(isIngestionPollable('UNKNOWN_X')).toBe(false);
    expect(isIngestionPollable(undefined)).toBe(false);
  });
});

describe('isIngestionRetryable', () => {
  it('matches the backend retry guard: FAILED and PARTIAL_FAILED only', () => {
    expect(isIngestionRetryable('FAILED')).toBe(true);
    expect(isIngestionRetryable('failed')).toBe(true);
    expect(isIngestionRetryable('PARTIAL_FAILED')).toBe(true);
    expect(isIngestionRetryable('QUEUED')).toBe(false);
    expect(isIngestionRetryable('PUBLISHED')).toBe(false);
    expect(isIngestionRetryable('NEEDS_REVIEW')).toBe(false);
    expect(isIngestionRetryable(undefined)).toBe(false);
  });
});

describe('formatIngestionStage', () => {
  it('renders terminal states with their status label', () => {
    expect(formatIngestionStage('REVIEW', 'PUBLISHED')).toBe('已发布');
    expect(formatIngestionStage('REVIEW', 'PARTIAL_FAILED')).toBe('部分失败');
    expect(formatIngestionStage('IMPORT', 'FAILED')).toBe('失败');
  });

  it('translates known pipeline stages while non-terminal', () => {
    expect(formatIngestionStage('IMPORT', 'IMPORTING')).toBe('导入');
    expect(formatIngestionStage('STRUCTURE', 'STRUCTURING')).toBe('结构化');
  });

  it('shows an unknown stage verbatim rather than guessing', () => {
    expect(formatIngestionStage('MYSTERY', 'IMPORTING')).toBe('MYSTERY');
  });

  it('falls back safely for missing stage', () => {
    expect(formatIngestionStage(undefined, 'QUEUED')).toBe('处理中…');
    expect(formatIngestionStage('', 'IMPORTING')).toBe('处理中…');
    expect(formatIngestionStage(undefined, undefined)).toBe('—');
  });
});

describe('UPLOAD_STAGE_LABELS', () => {
  it('never contains a fake numeric percentage', () => {
    for (const label of Object.values(UPLOAD_STAGE_LABELS)) {
      expect(label).not.toMatch(/\d+%/);
    }
  });

  it('covers the documented stage list', () => {
    expect(UPLOAD_STAGE_LABELS.ready).toBe('待上传');
    expect(UPLOAD_STAGE_LABELS.uploading).toBe('上传中…');
    expect(UPLOAD_STAGE_LABELS.uploaded).toBe('已上传');
    expect(UPLOAD_STAGE_LABELS['waiting-ingestion']).toBe('等待解析…');
    expect(UPLOAD_STAGE_LABELS.ingesting).toBe('解析中…');
    expect(UPLOAD_STAGE_LABELS.succeeded).toBe('解析成功');
    expect(UPLOAD_STAGE_LABELS.failed).toBe('解析失败');
  });
});
