/**
 * IngestionJob status presentation model (FE-002A PHASE 12).
 *
 * Exact known values only — never substring matching. Unknown future
 * statuses render as neutral and must not crash polling.
 *
 * Backend lifecycle (`IngestionJobService.IngestionStatus`):
 *   QUEUED -> IMPORTING -> EXTRACTING -> STRUCTURING -> AI_PROCESSING
 *                                        -> NEEDS_REVIEW | PUBLISHED
 *                                        -> PARTIAL_FAILED (some assets failed)
 *                                        -> FAILED        (job-level error)
 * The vocabulary is pinned to the Java enum by
 * `ingestion-status-contract.test.ts`, because guessing it here is what made
 * the workbench sit on "waiting" for a job that had already finished.
 */

export const INGESTION_STATUSES = {
  QUEUED: 'QUEUED',
  IMPORTING: 'IMPORTING',
  EXTRACTING: 'EXTRACTING',
  STRUCTURING: 'STRUCTURING',
  AI_PROCESSING: 'AI_PROCESSING',
  NEEDS_REVIEW: 'NEEDS_REVIEW',
  PUBLISHED: 'PUBLISHED',
  PARTIAL_FAILED: 'PARTIAL_FAILED',
  FAILED: 'FAILED',
} as const;

export type IngestionStatus =
  (typeof INGESTION_STATUSES)[keyof typeof INGESTION_STATUSES];

export type IngestionStatusKind =
  | 'non-terminal'
  | 'success-terminal'
  | 'failure-terminal'
  | 'unknown';

const NON_TERMINAL = new Set<string>([
  INGESTION_STATUSES.QUEUED,
  INGESTION_STATUSES.IMPORTING,
  INGESTION_STATUSES.EXTRACTING,
  INGESTION_STATUSES.STRUCTURING,
  INGESTION_STATUSES.AI_PROCESSING,
]);

/** NEEDS_REVIEW means every asset parsed; only human review is outstanding. */
const SUCCESS_TERMINAL = new Set<string>([
  INGESTION_STATUSES.PUBLISHED,
  INGESTION_STATUSES.NEEDS_REVIEW,
]);

const FAILURE_TERMINAL = new Set<string>([
  INGESTION_STATUSES.PARTIAL_FAILED,
  INGESTION_STATUSES.FAILED,
]);

/**
 * Classify a raw backend status string into a UI kind.
 * Unknown / missing → 'unknown' (safe, not polled forever, not a crash).
 */
export function classifyIngestionStatus(
  status?: string | null
): IngestionStatusKind {
  const normalized = (status ?? '').trim().toUpperCase();
  if (NON_TERMINAL.has(normalized)) {
    return 'non-terminal';
  }
  if (SUCCESS_TERMINAL.has(normalized)) {
    return 'success-terminal';
  }
  if (FAILURE_TERMINAL.has(normalized)) {
    return 'failure-terminal';
  }
  return 'unknown';
}

/** True only while the worker can still move the job forward. */
export function isIngestionPollable(status?: string | null): boolean {
  return classifyIngestionStatus(status) === 'non-terminal';
}

/** The backend's `retry` accepts exactly FAILED and PARTIAL_FAILED. */
export function isIngestionRetryable(status?: string | null): boolean {
  const normalized = (status ?? '').trim().toUpperCase();
  return (
    normalized === INGESTION_STATUSES.FAILED ||
    normalized === INGESTION_STATUSES.PARTIAL_FAILED
  );
}

/** Display copy for each backend status. */
const STATUS_LABELS: Record<IngestionStatus, string> = {
  QUEUED: '排队中',
  IMPORTING: '导入中',
  EXTRACTING: '抽取中',
  STRUCTURING: '结构化中',
  AI_PROCESSING: 'AI 处理中',
  NEEDS_REVIEW: '待复核',
  PUBLISHED: '已发布',
  PARTIAL_FAILED: '部分失败',
  FAILED: '失败',
};

export function ingestionStatusLabel(status?: string | null): string {
  const normalized = (status ?? '').trim().toUpperCase();
  return STATUS_LABELS[normalized as IngestionStatus] ?? (status || '—');
}

/** Pipeline position (`stage`), distinct from the job status. */
const STAGE_LABELS: Record<string, string> = {
  IMPORT: '导入',
  EXTRACT: '抽取',
  STRUCTURE: '结构化',
  AI_PROCESS: 'AI 处理',
  REVIEW: '复核',
};

/**
 * Stage progress for UI display (FE-002A PHASE 10). Stage strings are
 * backend pipeline positions, NOT byte percentages. Unknown stages
 * render as themselves; missing → neutral placeholder.
 */
export function formatIngestionStage(
  stage?: string | null,
  status?: string | null
): string {
  const kind = classifyIngestionStatus(status);
  if (kind === 'success-terminal') {
    return ingestionStatusLabel(status);
  }
  if (kind === 'failure-terminal') {
    return ingestionStatusLabel(status);
  }
  const trimmed = (stage ?? '').trim();
  if (trimmed === '') {
    return kind === 'non-terminal' ? '处理中…' : '—';
  }
  return STAGE_LABELS[trimmed.toUpperCase()] ?? trimmed;
}

/**
 * Upload / ingestion stage-state for the workbench status line
 * (FE-002A PHASE 10). NEVER fabricates byte percentages.
 */
export type UploadStage =
  | 'idle'
  | 'ready'
  | 'uploading'
  | 'uploaded'
  | 'waiting-ingestion'
  | 'ingesting'
  | 'succeeded'
  | 'failed';

export const UPLOAD_STAGE_LABELS: Record<UploadStage, string> = {
  idle: '—',
  ready: '待上传',
  uploading: '上传中…',
  uploaded: '已上传',
  'waiting-ingestion': '等待解析…',
  ingesting: '解析中…',
  succeeded: '解析成功',
  failed: '解析失败',
};
