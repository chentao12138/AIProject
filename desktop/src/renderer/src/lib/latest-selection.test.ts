/**
 * Latest asset/job selection + content state machine tests
 * (FE-002B Day 2).
 */

import { describe, expect, it } from 'vitest';
import {
  CONTENT_STATE_LABELS,
  deriveContentState,
  filterPagesForAsset,
  selectLatestAsset,
  selectLatestJobForAsset,
} from './latest-selection';

describe('selectLatestAsset', () => {
  it('picks newest by createdAt', () => {
    const result = selectLatestAsset([
      { id: 1, createdAt: '2026-09-01T10:00:00Z' },
      { id: 2, createdAt: '2026-09-02T10:00:00Z' },
    ]);
    expect(result?.id).toBe(2);
  });

  it('tiebreaks by id when createdAt equal', () => {
    const result = selectLatestAsset([
      { id: 5, createdAt: '2026-09-01T10:00:00Z' },
      { id: 9, createdAt: '2026-09-01T10:00:00Z' },
    ]);
    expect(result?.id).toBe(9);
  });

  it('skips entries without id', () => {
    const result = selectLatestAsset([
      { id: undefined, createdAt: '2026-09-03T10:00:00Z' },
      { id: 1, createdAt: '2026-09-01T10:00:00Z' },
    ]);
    expect(result?.id).toBe(1);
  });

  it('returns null for empty list', () => {
    expect(selectLatestAsset([])).toBeNull();
  });
});

describe('selectLatestJobForAsset', () => {
  it('picks newest job for the given asset', () => {
    const jobs = [
      { id: 1, assetId: 10, createdAt: '2026-09-01T10:00:00Z', status: 'SUCCEEDED' },
      { id: 2, assetId: 10, createdAt: '2026-09-02T10:00:00Z', status: 'FAILED' },
      { id: 3, assetId: 20, createdAt: '2026-09-03T10:00:00Z', status: 'SUCCEEDED' },
    ];
    const result = selectLatestJobForAsset(jobs, 10);
    expect(result?.id).toBe(2);
  });

  it('excludes jobs for other assets', () => {
    const jobs = [
      { id: 1, assetId: 20, createdAt: '2026-09-03T10:00:00Z' },
    ];
    expect(selectLatestJobForAsset(jobs, 10)).toBeNull();
  });

  it('returns null when assetId is undefined', () => {
    expect(
      selectLatestJobForAsset([{ id: 1, assetId: 10 }], undefined)
    ).toBeNull();
  });
});

describe('filterPagesForAsset', () => {
  it('returns only pages matching sourceAssetId', () => {
    const pages = [
      { id: 1, sourceAssetId: 10, pageOrder: 0 },
      { id: 2, sourceAssetId: 20, pageOrder: 0 },
      { id: 3, sourceAssetId: 10, pageOrder: 1 },
    ];
    const result = filterPagesForAsset(pages, 10);
    expect(result.map((p) => p.id)).toEqual([1, 3]);
  });

  it('returns empty when assetId undefined', () => {
    expect(filterPagesForAsset([{ id: 1 }], undefined)).toEqual([]);
  });
});

describe('deriveContentState', () => {
  it('NO_ASSET when no asset', () => {
    expect(
      deriveContentState({
        hasAsset: false,
        pageCount: 0,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('NO_ASSET');
  });

  it('INGESTION_PENDING for PENDING status', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: 'PENDING',
        pageCount: 0,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('INGESTION_PENDING');
  });

  it('INGESTION_RUNNING for RUNNING status', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: 'RUNNING',
        pageCount: 0,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('INGESTION_RUNNING');
  });

  it('INGESTION_FAILED for FAILED status', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: 'FAILED',
        pageCount: 0,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('INGESTION_FAILED');
  });

  it('CONTENT_READY when SUCCEEDED with blocks or text', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: 'SUCCEEDED',
        pageCount: 1,
        blockCount: 2,
        hasExtractedText: true,
      })
    ).toBe('CONTENT_READY');
  });

  it('CONTENT_EMPTY_VALID when SUCCEEDED with pages but no text', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: 'SUCCEEDED',
        pageCount: 1,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('CONTENT_EMPTY_VALID');
  });

  it('ASSET_UPLOADED when no job yet', () => {
    expect(
      deriveContentState({
        hasAsset: true,
        jobStatus: undefined,
        pageCount: 0,
        blockCount: 0,
        hasExtractedText: false,
      })
    ).toBe('ASSET_UPLOADED');
  });
});

describe('CONTENT_STATE_LABELS', () => {
  it('has a label for every state', () => {
    const states = [
      'NO_ASSET',
      'ASSET_UPLOADED',
      'INGESTION_PENDING',
      'INGESTION_RUNNING',
      'INGESTION_FAILED',
      'CONTENT_READY',
      'CONTENT_EMPTY_VALID',
      'CONTENT_UNAVAILABLE',
    ] as const;
    for (const s of states) {
      expect(CONTENT_STATE_LABELS[s]).toBeTruthy();
    }
  });
});
