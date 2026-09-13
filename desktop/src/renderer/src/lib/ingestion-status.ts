/**
 * IngestionJob status presentation model (FE-002A PHASE 12).
 *
 * Exact known values only — never substring matching. Unknown future
 * statuses render as neutral and must not crash polling.
 *
 * Backend lifecycle (IngestionJobService):
 *   PENDING → RUNNING → SUCCEEDED
 *                      ↘ FAILED → (retry) → PENDING
 */

export const INGESTION_STATUSES = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCEEDED: 'SUCCEEDED',
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
  INGESTION_STATUSES.PENDING,
  INGESTION_STATUSES.RUNNING,
]);

const SUCCESS_TERMINAL = new Set<string>([INGESTION_STATUSES.SUCCEEDED]);

const FAILURE_TERMINAL = new Set<string>([INGESTION_STATUSES.FAILED]);

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

/** True only for PENDING / RUNNING — the only statuses worth polling. */
export function isIngestionPollable(status?: string | null): boolean {
  return classifyIngestionStatus(status) === 'non-terminal';
}

/** True only for FAILED — the only status the contract allows retry on. */
export function isIngestionRetryable(status?: string | null): boolean {
  return (status ?? '').trim().toUpperCase() === INGESTION_STATUSES.FAILED;
}

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
    return 'Succeeded';
  }
  if (kind === 'failure-terminal') {
    return 'Failed';
  }
  if (kind === 'unknown' && !stage) {
    return '—';
  }
  const trimmed = (stage ?? '').trim();
  if (trimmed === '') {
    return kind === 'non-terminal' ? 'Working…' : '—';
  }
  // Humanize known pipeline stages without inventing new ones.
  const label = trimmed.toLowerCase().replace(/_/g, ' ');
  return label.charAt(0).toUpperCase() + label.slice(1);
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
  ready: 'Ready',
  uploading: 'Uploading…',
  uploaded: 'Uploaded',
  'waiting-ingestion': 'Waiting for ingestion…',
  ingesting: 'Ingesting…',
  succeeded: 'Succeeded',
  failed: 'Failed',
};
