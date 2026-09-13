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
  it('classifies PENDING and RUNNING as non-terminal', () => {
    expect(classifyIngestionStatus('PENDING')).toBe('non-terminal');
    expect(classifyIngestionStatus('RUNNING')).toBe('non-terminal');
    expect(classifyIngestionStatus('pending')).toBe('non-terminal');
  });

  it('classifies SUCCEEDED as success-terminal', () => {
    expect(classifyIngestionStatus('SUCCEEDED')).toBe('success-terminal');
  });

  it('classifies FAILED as failure-terminal', () => {
    expect(classifyIngestionStatus('FAILED')).toBe('failure-terminal');
  });

  it('classifies unknown / missing / lookalikes as unknown', () => {
    expect(classifyIngestionStatus('UNDEFINED')).toBe('unknown');
    expect(classifyIngestionStatus('SUCCESS')).toBe('unknown');
    expect(classifyIngestionStatus('FAIL')).toBe('unknown');
    expect(classifyIngestionStatus('PARTIAL_FAILED')).toBe('unknown');
    expect(classifyIngestionStatus('')).toBe('unknown');
    expect(classifyIngestionStatus(undefined)).toBe('unknown');
    expect(classifyIngestionStatus(null)).toBe('unknown');
  });

  it('does not use substring matching (SUCCESS inside SUCCEEDED is exact only)', () => {
    // SUCCEEDED is exact-known; a hypothetical SUPERSEDED-like value is unknown.
    expect(classifyIngestionStatus('SUPERSEDED')).toBe('unknown');
    expect(classifyIngestionStatus('NOT_PENDING')).toBe('unknown');
  });
});

describe('isIngestionPollable', () => {
  it('is true only for PENDING / RUNNING', () => {
    expect(isIngestionPollable('PENDING')).toBe(true);
    expect(isIngestionPollable('RUNNING')).toBe(true);
    expect(isIngestionPollable('SUCCEEDED')).toBe(false);
    expect(isIngestionPollable('FAILED')).toBe(false);
    expect(isIngestionPollable('UNKNOWN_X')).toBe(false);
    expect(isIngestionPollable(undefined)).toBe(false);
  });
});

describe('isIngestionRetryable', () => {
  it('is true only for FAILED', () => {
    expect(isIngestionRetryable('FAILED')).toBe(true);
    expect(isIngestionRetryable('failed')).toBe(true);
    expect(isIngestionRetryable('PENDING')).toBe(false);
    expect(isIngestionRetryable('SUCCEEDED')).toBe(false);
    expect(isIngestionRetryable(undefined)).toBe(false);
  });
});

describe('formatIngestionStage', () => {
  it('renders terminal states as Succeeded / Failed', () => {
    expect(formatIngestionStage('PUBLISHED', 'SUCCEEDED')).toBe('Succeeded');
    expect(formatIngestionStage('QUEUED', 'FAILED')).toBe('Failed');
  });

  it('humanizes known pipeline stages while non-terminal', () => {
    expect(formatIngestionStage('QUEUED', 'PENDING')).toBe('Queued');
    expect(formatIngestionStage('IMPORTING', 'RUNNING')).toBe('Importing');
  });

  it('falls back safely for missing stage', () => {
    expect(formatIngestionStage(undefined, 'PENDING')).toBe('Working…');
    expect(formatIngestionStage('', 'RUNNING')).toBe('Working…');
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
    expect(UPLOAD_STAGE_LABELS.ready).toBe('Ready');
    expect(UPLOAD_STAGE_LABELS.uploading).toBe('Uploading…');
    expect(UPLOAD_STAGE_LABELS.uploaded).toBe('Uploaded');
    expect(UPLOAD_STAGE_LABELS['waiting-ingestion']).toBe(
      'Waiting for ingestion…'
    );
    expect(UPLOAD_STAGE_LABELS.ingesting).toBe('Ingesting…');
    expect(UPLOAD_STAGE_LABELS.succeeded).toBe('Succeeded');
    expect(UPLOAD_STAGE_LABELS.failed).toBe('Failed');
  });
});
