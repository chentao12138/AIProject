/**
 * Latest asset/job selection + content state machine tests
 * (FE-002B Day 2).
 */

import { describe, expect, it } from 'vitest';
import {
  CONTENT_STATE_LABELS,
  deriveContentState,
  filterPagesForAsset,
  resolveJobForAsset,
  selectLatestAsset,
  selectLatestJob,
  selectLatestJobForAsset,
} from './latest-selection';

describe('selectLatestJob', () => {
  // The backend can return a job whose assetId is null. Dropping it made the
  // workbench keep a terminal failure on screen as "queued", because the poll
  // gate reads this job.
  it('still reports a job that never got linked to its asset', () => {
    const orphan = { id: 193, assetId: null, status: 'PARTIAL_FAILED', createdAt: '2026-09-03T10:00:00Z' };
    expect(selectLatestJobForAsset([orphan], 238)).toBeNull();
    expect(selectLatestJob([orphan])?.status).toBe('PARTIAL_FAILED');
  });

  it('picks newest by createdAt, tiebreaking on id', () => {
    const picked = selectLatestJob([
      { id: 1, createdAt: '2026-09-01T10:00:00Z' },
      { id: 7, createdAt: '2026-09-05T10:00:00Z' },
      { id: 4, createdAt: '2026-09-05T10:00:00Z' },
    ]);
    expect(picked?.id).toBe(7);
  });

  it('returns null for an empty or id-less list', () => {
    expect(selectLatestJob([])).toBeNull();
    expect(selectLatestJob([{ createdAt: '2026-09-01T10:00:00Z' }])).toBeNull();
  });
});

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
      { id: 1, assetId: 10, createdAt: '2026-09-01T10:00:00Z', status: 'PUBLISHED' },
      { id: 2, assetId: 10, createdAt: '2026-09-02T10:00:00Z', status: 'FAILED' },
      { id: 3, assetId: 20, createdAt: '2026-09-03T10:00:00Z', status: 'PUBLISHED' },
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

/**
 * W14: which job is allowed to describe an asset.
 *
 * The old workbench wrote `selectLatestJobForAsset(...) ?? selectLatestJob(jobs)`,
 * i.e. when the newest asset had no job it borrowed the newest job of the
 * whole Source — asset A's SUCCEEDED/FAILED shown as asset B's state.
 */
describe('resolveJobForAsset', () => {
  const assetAPublished = {
    id: 55,
    assetId: 90,
    status: 'PUBLISHED',
    createdAt: '2026-09-01T10:00:01Z',
  };
  const assetBQueued = {
    id: 56,
    assetId: 91,
    status: 'QUEUED',
    createdAt: '2026-09-02T10:00:01Z',
  };

  it('reports the asset\'s own latest job', () => {
    const result = resolveJobForAsset({
      jobs: [assetAPublished, assetBQueued],
      assetId: 91,
      assetCount: 2,
    });
    expect(result.source).toBe('asset');
    expect(result.job?.id).toBe(56);
  });

  it('does not borrow a sibling\'s SUCCEEDED when the newest asset has no job', () => {
    const result = resolveJobForAsset({
      jobs: [assetAPublished],
      assetId: 91,
      assetCount: 2,
    });
    expect(result).toEqual({ source: 'none', job: null });
  });

  it('does not borrow a sibling\'s FAILED either', () => {
    const result = resolveJobForAsset({
      jobs: [
        { id: 54, assetId: 89, status: 'FAILED', createdAt: '2026-09-01T09:00:00Z' },
      ],
      assetId: 90,
      assetCount: 2,
    });
    expect(result).toEqual({ source: 'none', job: null });
  });

  it('reports none when there is no asset at all', () => {
    expect(
      resolveJobForAsset({ jobs: [assetAPublished], assetId: undefined, assetCount: 0 })
    ).toEqual({ source: 'none', job: null });
  });

  it('reports none for an empty job list', () => {
    expect(
      resolveJobForAsset({ jobs: [], assetId: 90, assetCount: 1 })
    ).toEqual({ source: 'none', job: null });
  });

  // The backend used to answer `assetId: null` on every job; a one-asset
  // Source is the only case where such a job has a provable owner.
  it('attributes an unlinked job when the Source has exactly one asset', () => {
    const orphan = {
      id: 193,
      assetId: null,
      status: 'PARTIAL_FAILED',
      createdAt: '2026-09-03T10:00:00Z',
    };
    const result = resolveJobForAsset({
      jobs: [orphan],
      assetId: 90,
      assetCount: 1,
    });
    expect(result.source).toBe('legacy-unlinked');
    expect(result.job?.status).toBe('PARTIAL_FAILED');
  });

  it('never attributes an unlinked job once the Source has two assets', () => {
    const orphan = {
      id: 193,
      assetId: null,
      status: 'PUBLISHED',
      createdAt: '2026-09-03T10:00:00Z',
    };
    expect(
      resolveJobForAsset({ jobs: [orphan], assetId: 91, assetCount: 2 })
    ).toEqual({ source: 'none', job: null });
  });

  it('prefers the asset\'s own job over an unlinked one', () => {
    const result = resolveJobForAsset({
      jobs: [
        { id: 1, assetId: null, status: 'FAILED', createdAt: '2026-09-05T10:00:00Z' },
        assetAPublished,
      ],
      assetId: 90,
      assetCount: 1,
    });
    expect(result.source).toBe('asset');
    expect(result.job?.status).toBe('PUBLISHED');
  });

  it('ignores a job linked to an asset that is not in the list', () => {
    expect(
      resolveJobForAsset({
        jobs: [{ id: 1, assetId: 99, status: 'PUBLISHED', createdAt: '2026-09-01T10:00:00Z' }],
        assetId: 90,
        assetCount: 1,
      })
    ).toEqual({ source: 'none', job: null });
  });

  it('treats a job whose assetId key is missing as unlinked', () => {
    const result = resolveJobForAsset({
      jobs: [{ id: 7, status: 'IMPORTING', createdAt: '2026-09-01T10:00:00Z' }],
      assetId: 90,
      assetCount: 1,
    });
    expect(result.source).toBe('legacy-unlinked');
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
        jobStatus: 'QUEUED',
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
        jobStatus: 'IMPORTING',
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
        jobStatus: 'PUBLISHED',
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
        jobStatus: 'PUBLISHED',
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

  // W14: an asset without its own job must never be described as done.
  it('says parsing has not started for an asset with no job', () => {
    expect(CONTENT_STATE_LABELS.ASSET_UPLOADED).toContain('尚未开始解析');
  });
});
