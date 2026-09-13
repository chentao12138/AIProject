/**
 * Deterministic latest asset / job selection (FE-002B Day 2).
 *
 * A Source can have multiple assets and jobs. The UI must never
 * guess via array[0] or query-return order. Selection is driven by
 * stable DTO fields only:
 *
 *   SourceAssetResponse:     id, createdAt
 *   IngestionJobResponse:    id, assetId, createdAt, status
 *   SourcePageResponse:      id, sourceAssetId, pageOrder
 *
 * Rules:
 *   latestAsset  = max(createdAt), tiebreak max(id)
 *   latestJob    = among jobs for that asset, max(createdAt), tiebreak max(id)
 *   pagesForAsset = pages where sourceAssetId === asset.id
 *
 * If the contract cannot associate a job with an asset (missing
 * assetId), the job is excluded from latest-job selection rather
 * than guessed.
 */

export interface AssetLike {
  id?: number;
  createdAt?: string;
}

export interface JobLike {
  id?: number;
  assetId?: number;
  createdAt?: string;
  status?: string;
}

export interface PageLike {
  id?: number;
  sourceAssetId?: number;
  pageOrder?: number;
}

function ts(value?: string): number {
  if (!value) {
    return 0;
  }
  const t = Date.parse(value);
  return Number.isNaN(t) ? 0 : t;
}

/**
 * Pick the latest asset: newest createdAt, tiebreak highest id.
 * Returns null when the list is empty or no entry has a valid id.
 */
export function selectLatestAsset(assets: AssetLike[]): AssetLike | null {
  let best: AssetLike | null = null;
  for (const a of assets) {
    if (typeof a.id !== 'number') {
      continue;
    }
    if (best === null) {
      best = a;
      continue;
    }
    const cmp = ts(a.createdAt) - ts(best.createdAt);
    if (cmp > 0 || (cmp === 0 && (a.id ?? 0) > (best.id ?? 0))) {
      best = a;
    }
  }
  return best;
}

/**
 * Pick the latest job for a given asset. Jobs without a matching
 * assetId are excluded. Returns null when no job matches.
 */
export function selectLatestJobForAsset(
  jobs: JobLike[],
  assetId: number | undefined
): JobLike | null {
  if (typeof assetId !== 'number') {
    return null;
  }
  let best: JobLike | null = null;
  for (const j of jobs) {
    if (j.assetId !== assetId) {
      continue;
    }
    if (typeof j.id !== 'number') {
      continue;
    }
    if (best === null) {
      best = j;
      continue;
    }
    const cmp = ts(j.createdAt) - ts(best.createdAt);
    if (cmp > 0 || (cmp === 0 && (j.id ?? 0) > (best.id ?? 0))) {
      best = j;
    }
  }
  return best;
}

/**
 * Filter pages belonging to a specific asset. Pages without a
 * matching sourceAssetId are excluded (no guessing).
 */
export function filterPagesForAsset(
  pages: PageLike[],
  assetId: number | undefined
): PageLike[] {
  if (typeof assetId !== 'number') {
    return [];
  }
  return pages.filter((p) => p.sourceAssetId === assetId);
}

/**
 * Frontend presentation state for the content area.
 * These are NOT backend enums — they describe what the user sees.
 */
export type ContentPresentationState =
  | 'NO_ASSET'
  | 'ASSET_UPLOADED'
  | 'INGESTION_PENDING'
  | 'INGESTION_RUNNING'
  | 'INGESTION_FAILED'
  | 'CONTENT_READY'
  | 'CONTENT_EMPTY_VALID'
  | 'CONTENT_UNAVAILABLE';

export function deriveContentState(params: {
  hasAsset: boolean;
  jobStatus?: string | null;
  pageCount: number;
  blockCount: number;
  hasExtractedText: boolean;
}): ContentPresentationState {
  if (!params.hasAsset) {
    return 'NO_ASSET';
  }
  const status = (params.jobStatus ?? '').trim().toUpperCase();
  if (status === 'PENDING') {
    return 'INGESTION_PENDING';
  }
  if (status === 'RUNNING') {
    return 'INGESTION_RUNNING';
  }
  if (status === 'FAILED') {
    return 'INGESTION_FAILED';
  }
  if (status === 'SUCCEEDED') {
    if (params.blockCount > 0 || params.hasExtractedText) {
      return 'CONTENT_READY';
    }
    if (params.pageCount > 0) {
      return 'CONTENT_EMPTY_VALID';
    }
    return 'CONTENT_UNAVAILABLE';
  }
  // Asset exists but no job yet (or unknown status).
  return 'ASSET_UPLOADED';
}

/** Human label for a content presentation state. */
export const CONTENT_STATE_LABELS: Record<ContentPresentationState, string> = {
  NO_ASSET: '尚未上传文件',
  ASSET_UPLOADED: '文件已上传，等待开始处理',
  INGESTION_PENDING: '排队等待处理…',
  INGESTION_RUNNING: '正在处理…',
  INGESTION_FAILED: '处理失败',
  CONTENT_READY: '内容就绪',
  CONTENT_EMPTY_VALID: '已成功处理，但没有可提取文本',
  CONTENT_UNAVAILABLE: '暂无可用内容',
};
