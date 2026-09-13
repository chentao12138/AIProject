/**
 * Source Workbench (FE-002A + FE-002B Day 2 business).
 *
 * /spaces/:spaceId/sources/:sourceId
 *
 * Day 2 product semantics:
 *   - Business summary: latest asset, media type, latest job status,
 *     content availability — visible immediately on open.
 *   - Latest asset/job selection is deterministic (createdAt + id),
 *     never array[0] or query-return order.
 *   - Stale content prevention: content area always reflects the
 *     LATEST asset's pages. New upload while old content exists shows
 *     a clear "new file processing" banner; old content is never
 *     presented as belonging to the new asset.
 *   - Ingestion history: full job list with asset link, status, stage,
 *     timestamps, retry only for FAILED.
 *   - Upload-another: same flow, appends to history, updates latest.
 *   - PDF reader: Page X of N, Prev/Next, aria-current, stable selection.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { formatBytes, formatDateTime } from '../../lib/format';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';
import {
  UPLOAD_STAGE_LABELS,
  classifyIngestionStatus,
  formatIngestionStage,
  isIngestionPollable,
  isIngestionRetryable,
  type UploadStage,
} from '../../lib/ingestion-status';
import {
  MEDIA_ACCEPT_ATTR,
  classifyMediaFile,
  describeIngestionErrorCode,
  precheckFileSize,
} from '../../lib/media-policy';
import {
  CONTENT_STATE_LABELS,
  deriveContentState,
  filterPagesForAsset,
  selectLatestAsset,
  selectLatestJobForAsset,
} from '../../lib/latest-selection';
import { useFormError } from '../../lib/use-form-error';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

const POLL_INTERVAL_MS = 2000;

export function SourceWorkbenchPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const { spaceId: spaceIdParam, sourceId: sourceIdParam } = useParams();
  const [searchParams] = useSearchParams();

  const spaceId = parsePositiveIdParam(spaceIdParam);
  const sourceId = parsePositiveIdParam(sourceIdParam);

  const sourceQuery = useQuery({
    queryKey: queryKeys.source(spaceId ?? 0, sourceId ?? 0),
    queryFn: () => {
      if (spaceId === null || sourceId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.getSource(spaceId, sourceId).then(unwrap);
    },
    enabled: spaceId !== null && sourceId !== null,
  });

  const assetsQuery = useQuery({
    queryKey: queryKeys.sourceAssets(spaceId ?? 0, sourceId ?? 0),
    queryFn: () => {
      if (spaceId === null || sourceId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.listSourceAssets(spaceId, sourceId).then(unwrap);
    },
    enabled: spaceId !== null && sourceId !== null,
  });

  const jobsQuery = useQuery({
    queryKey: queryKeys.ingestionJobs(spaceId ?? 0, sourceId ?? 0),
    queryFn: () => {
      if (spaceId === null || sourceId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.listIngestionJobs(spaceId, sourceId).then(unwrap);
    },
    enabled: spaceId !== null && sourceId !== null,
  });

  const pagesQuery = useQuery({
    queryKey: queryKeys.sourcePages(spaceId ?? 0, sourceId ?? 0),
    queryFn: () => {
      if (spaceId === null || sourceId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.listSourcePages(spaceId, sourceId).then(unwrap);
    },
    enabled: spaceId !== null && sourceId !== null,
  });

  // Deep-link: ?page=<id>&block=<id> from provenance or refresh.
  const deepLinkPageId = parsePositiveIdParam(searchParams.get('page') ?? undefined);
  const deepLinkBlockId = parsePositiveIdParam(searchParams.get('block') ?? undefined);

  if (spaceId === null || sourceId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  if (sourceQuery.isPending) {
    return <LoadingState text="加载来源…" />;
  }

  if (sourceQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(sourceQuery.error).message}
        onRetry={() => void sourceQuery.refetch()}
      />
    );
  }

  const source = sourceQuery.data;
  const assets = assetsQuery.data ?? [];
  const jobs = jobsQuery.data ?? [];
  const allPages = pagesQuery.data ?? [];

  const latestAsset = selectLatestAsset(assets);
  const latestJob = selectLatestJobForAsset(jobs, latestAsset?.id);
  const latestPages = filterPagesForAsset(allPages, latestAsset?.id);

  return (
    <div className="page">
      <PageHeader
        title={source?.title ?? 'Source workbench'}
        actions={
          <Button onClick={() => navigate(`/spaces/${spaceId}/sources`)}>
            Back to Sources
          </Button>
        }
      />

      <BusinessSummary
        source={source}
        latestAsset={latestAsset}
        latestJob={latestJob}
        latestPages={latestPages}
        assetsQueryPending={assetsQuery.isPending}
        jobsQueryPending={jobsQuery.isPending}
        pagesQueryPending={pagesQuery.isPending}
      />

      <UploadSection spaceId={spaceId} sourceId={sourceId} />

      <IngestionHistory
        spaceId={spaceId}
        sourceId={sourceId}
        jobs={jobs}
        assets={assets}
        latestJobId={latestJob?.id}
      />

      <ContentSection
        spaceId={spaceId}
        sourceId={sourceId}
        latestAssetId={latestAsset?.id}
        latestJobStatus={latestJob?.status}
        latestPages={latestPages}
        deepLinkPageId={deepLinkPageId}
        deepLinkBlockId={deepLinkBlockId}
      />
    </div>
  );
}

/* ─────────────── Business summary ─────────────── */

function BusinessSummary({
  source,
  latestAsset,
  latestJob,
  latestPages,
  assetsQueryPending,
  jobsQueryPending,
  pagesQueryPending,
}: {
  source?: { sourceType?: string; status?: string; createdAt?: string };
  latestAsset: { id?: number; originalName?: string; mimeType?: string; sizeBytes?: number; createdAt?: string } | null;
  latestJob: { id?: number; status?: string; stage?: string; createdAt?: string } | null;
  latestPages: { id?: number; pageType?: string; extractedText?: string | null }[];
  assetsQueryPending: boolean;
  jobsQueryPending: boolean;
  pagesQueryPending: boolean;
}) {
  const loading = assetsQueryPending || jobsQueryPending || pagesQueryPending;
  const media = latestAsset ? classifyMediaFile({ name: latestAsset.originalName ?? '' }) : null;
  const jobStatus = latestJob?.status;
  const kind = classifyIngestionStatus(jobStatus);

  const pageCount = latestPages.length;
  const hasText = latestPages.some((p) => (p.extractedText ?? '').trim() !== '');
  const blockCount = 0; // blocks are per-page; summary uses pages+text as proxy
  const contentState = deriveContentState({
    hasAsset: latestAsset !== null,
    jobStatus,
    pageCount,
    blockCount,
    hasExtractedText: hasText,
  });

  // Build a one-line business summary.
  let summaryLine = '尚未上传文件';
  if (loading && !latestAsset) {
    summaryLine = '加载中…';
  } else if (latestAsset && media) {
    const parts: string[] = [media.label];
    if (kind === 'success-terminal') {
      if (media.kind === 'image') {
        parts.push('图片已处理');
      } else if (pageCount > 0 && hasText) {
        parts.push(`${pageCount} 页 · 内容就绪`);
      } else if (pageCount > 0) {
        parts.push('已处理 · 无可提取文本');
      } else {
        parts.push('已处理');
      }
    } else if (kind === 'non-terminal') {
      parts.push(formatIngestionStage(latestJob?.stage, jobStatus));
    } else if (kind === 'failure-terminal') {
      parts.push('处理失败');
    } else {
      parts.push('等待处理');
    }
    summaryLine = parts.join(' · ');
  }

  return (
    <section className="workbench-section" aria-label="Source summary" data-testid="business-summary">
      <h2 className="section-title">Summary</h2>
      <p className="workbench-summary" data-testid="summary-line">
        {summaryLine}
      </p>
      <dl className="meta-list">
        <div>
          <dt>Type</dt>
          <dd>{source?.sourceType ?? '—'}</dd>
        </div>
        <div>
          <dt>Source status</dt>
          <dd>
            <StatusBadge status={source?.status} />
          </dd>
        </div>
        {latestAsset && (
          <div>
            <dt>Latest file</dt>
            <dd title={latestAsset.originalName}>
              {latestAsset.originalName ?? '—'}
              {latestAsset.sizeBytes !== undefined
                ? ` (${formatBytes(latestAsset.sizeBytes)})`
                : ''}
            </dd>
          </div>
        )}
        {latestJob && (
          <div>
            <dt>Latest ingestion</dt>
            <dd>
              <StatusBadge status={latestJob.status} />
            </dd>
          </div>
        )}
        <div>
          <dt>Content</dt>
          <dd data-testid="content-state">
            {CONTENT_STATE_LABELS[contentState]}
          </dd>
        </div>
        {latestAsset?.createdAt && (
          <div>
            <dt>Uploaded</dt>
            <dd>{formatDateTime(latestAsset.createdAt)}</dd>
          </div>
        )}
      </dl>
    </section>
  );
}

/* ─────────────── Upload ─────────────── */

function UploadSection({
  spaceId,
  sourceId,
}: {
  spaceId: number;
  sourceId: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [stage, setStage] = useState<UploadStage>('idle');
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [activeJobId, setActiveJobId] = useState<number | null>(null);
  const formError = useFormError(uploadError ? { message: uploadError } : null);

  const uploadAndIngest = useMutation({
    mutationFn: async (selected: File) => {
      setStage('uploading');
      setUploadError(null);
      const asset = await api
        .uploadSourceAsset(spaceId, sourceId, selected)
        .then(unwrap);
      setStage('uploaded');
      const assetId = asset.id;
      if (!isPositiveId(assetId)) {
        setStage('failed');
        throw new Error('upload returned no valid asset id');
      }
      setStage('waiting-ingestion');
      const job = await api
        .createIngestionJob(spaceId, sourceId, assetId)
        .then(unwrap);
      // CRITICAL: 201 does NOT mean success — inspect body.status.
      setStage('ingesting');
      return { asset, job };
    },
    onSuccess: ({ job }) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sourceAssets(spaceId, sourceId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sourcePages(spaceId, sourceId),
      });
      if (isPositiveId(job.id)) {
        setActiveJobId(job.id);
        void queryClient.invalidateQueries({
          queryKey: queryKeys.ingestionJob(spaceId, job.id),
        });
      }
    },
    onError: (error) => {
      setStage('failed');
      setUploadError(normalizeApiError(error).message);
    },
  });

  function clearFile() {
    setFile(null);
    setUploadError(null);
    if (stage !== 'ingesting' && stage !== 'uploading') {
      setStage('idle');
    }
    if (fileInputRef.current) {
      fileInputRef.current.value = '';
    }
  }

  const busy = uploadAndIngest.isPending;

  return (
    <section className="workbench-section" aria-label="Upload file">
      <h2 className="section-title">Upload</h2>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!file || busy || uploadError) {
            return;
          }
          uploadAndIngest.mutate(file);
        }}
      >
        <div className="form__field">
          <label htmlFor="source-upload-input" className="form__label">
            File (TXT / Markdown / PDF / PNG / JPEG)
          </label>
          <input
            id="source-upload-input"
            ref={fileInputRef}
            type="file"
            accept={MEDIA_ACCEPT_ATTR}
            disabled={busy}
            onChange={(event) => {
              const picked = event.target.files?.[0] ?? null;
              setFile(picked);
              setUploadError(null);
              if (picked) {
                const sizeErr = precheckFileSize(picked);
                if (sizeErr) {
                  setUploadError(sizeErr);
                  setStage('idle');
                  return;
                }
              }
              setStage(picked ? 'ready' : 'idle');
            }}
          />
        </div>

        {file && (
          <p className="form__note" data-testid="selected-file">
            {file.name} · {formatBytes(file.size)} ·{' '}
            {classifyMediaFile(file).label}
          </p>
        )}

        <p className="upload-stage" role="status" aria-live="polite">
          {UPLOAD_STAGE_LABELS[stage]}
        </p>

        {formError.message && (
          <p className="form__error" role="alert">
            {formError.message}
          </p>
        )}

        <div className="form__actions">
          <Button
            type="submit"
            variant="primary"
            disabled={!file || busy || uploadError !== null}
          >
            {busy ? UPLOAD_STAGE_LABELS[stage] : 'Upload & Ingest'}
          </Button>
          <Button onClick={clearFile} disabled={busy || !file} type="button">
            Clear
          </Button>
        </div>
      </form>

      {activeJobId !== null && (
        <ActiveJobStatus
          spaceId={spaceId}
          jobId={activeJobId}
          onTerminal={() => {
            void queryClient.invalidateQueries({
              queryKey: queryKeys.sourcePages(spaceId, sourceId),
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.sourceAssets(spaceId, sourceId),
            });
            setStage((current) =>
              current === 'ingesting' || current === 'waiting-ingestion'
                ? 'idle'
                : current
            );
          }}
          onSucceeded={() => setStage('succeeded')}
          onFailed={() => setStage('failed')}
        />
      )}
    </section>
  );
}

function ActiveJobStatus({
  spaceId,
  jobId,
  onTerminal,
  onSucceeded,
  onFailed,
}: {
  spaceId: number;
  jobId: number;
  onTerminal: () => void;
  onSucceeded: () => void;
  onFailed: () => void;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const terminalRef = useRef(false);

  const jobQuery = useQuery({
    queryKey: queryKeys.ingestionJob(spaceId, jobId),
    queryFn: () => {
      if (!isPositiveId(jobId)) {
        return Promise.reject(new Error('invalid job id'));
      }
      return api.getIngestionJob(spaceId, jobId).then(unwrap);
    },
    enabled: isPositiveId(jobId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      const errorStatus = (query.state.error as { status?: number } | null)
        ?.status;
      if (errorStatus === 401 || errorStatus === 403 || errorStatus === 404) {
        return false;
      }
      return isIngestionPollable(status) ? POLL_INTERVAL_MS : false;
    },
  });

  const status = jobQuery.data?.status;
  const kind = classifyIngestionStatus(status);

  useEffect(() => {
    if (terminalRef.current) {
      return;
    }
    if (kind === 'success-terminal') {
      terminalRef.current = true;
      onSucceeded();
      onTerminal();
    } else if (kind === 'failure-terminal') {
      terminalRef.current = true;
      onFailed();
      onTerminal();
    }
  }, [kind, onTerminal, onSucceeded, onFailed]);

  const retry = useMutation({
    mutationFn: () => api.retryIngestionJob(spaceId, jobId).then(unwrap),
    onSuccess: () => {
      terminalRef.current = false;
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJob(spaceId, jobId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJobs(spaceId, jobId),
      });
    },
  });

  if (jobQuery.isPending) {
    return <p className="muted">Loading ingestion job…</p>;
  }
  if (jobQuery.isError) {
    const err = normalizeApiError(jobQuery.error);
    return (
      <div>
        <p className="form__error" role="alert">
          {err.message}
        </p>
        <Button onClick={() => void jobQuery.refetch()} type="button">
          Retry status request
        </Button>
      </div>
    );
  }

  const job = jobQuery.data;
  const retryable = isIngestionRetryable(status);

  return (
    <div className="ingestion-active" data-testid="active-job">
      <p role="status" aria-live="polite">
        Ingestion: {formatIngestionStage(job?.stage, status)}
      </p>
      {kind === 'failure-terminal' && (
        <div>
          <p className="form__error" role="alert">
            {describeIngestionErrorCode(job?.errorCode, job?.errorMessage)}
          </p>
          {retryable && (
            <Button
              type="button"
              onClick={() => retry.mutate()}
              disabled={retry.isPending}
            >
              {retry.isPending ? 'Retrying…' : 'Retry ingestion'}
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

/* ─────────────── Ingestion history ─────────────── */

function IngestionHistory({
  spaceId,
  sourceId,
  jobs,
  assets,
  latestJobId,
}: {
  spaceId: number;
  sourceId: number;
  jobs: {
    id?: number;
    assetId?: number;
    status?: string;
    stage?: string;
    progressPercent?: number;
    retryCount?: number;
    createdAt?: string;
    startedAt?: string;
    finishedAt?: string;
    errorCode?: string;
    errorMessage?: string;
  }[];
  assets: { id?: number; originalName?: string; mimeType?: string }[];
  latestJobId?: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();

  const assetById = new Map<number, { originalName?: string; mimeType?: string }>();
  for (const a of assets) {
    if (typeof a.id === 'number') {
      assetById.set(a.id, a);
    }
  }

  const retry = useMutation({
    mutationFn: (jobId: number) =>
      api.retryIngestionJob(spaceId, jobId).then(unwrap),
    onSuccess: (_data, jobId) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJob(spaceId, jobId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
      });
    },
  });

  // Newest first (backend already returns newest-first; re-sort defensively).
  const sorted = useMemo(() => {
    return [...jobs].sort((a, b) => {
      const ta = Date.parse(a.createdAt ?? '') || 0;
      const tb = Date.parse(b.createdAt ?? '') || 0;
      return tb - ta;
    });
  }, [jobs]);

  return (
    <section className="workbench-section" aria-label="Ingestion history">
      <h2 className="section-title">Ingestion history</h2>
      {sorted.length === 0 ? (
        <p className="muted">No ingestion jobs yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Job</th>
              <th>File</th>
              <th>Status</th>
              <th>Stage</th>
              <th>Created</th>
              <th>Finished</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((job, index) => {
              const jobId = job.id;
              const asset = typeof job.assetId === 'number' ? assetById.get(job.assetId) : undefined;
              const retryable = isIngestionRetryable(job.status);
              const isLatest = jobId !== undefined && jobId === latestJobId;
              return (
                <tr
                  key={jobId ?? `job-${index}`}
                  data-testid="history-row"
                  data-latest={isLatest ? 'true' : undefined}
                >
                  <td>
                    #{jobId ?? '—'}
                    {isLatest && (
                      <span className="badge badge--unknown"> latest</span>
                    )}
                  </td>
                  <td title={asset?.originalName}>
                    {asset?.originalName ?? (job.assetId ? `#${job.assetId}` : '—')}
                  </td>
                  <td>
                    <StatusBadge status={job.status} />
                  </td>
                  <td>{formatIngestionStage(job.stage, job.status)}</td>
                  <td>{formatDateTime(job.createdAt)}</td>
                  <td>{formatDateTime(job.finishedAt)}</td>
                  <td>
                    {retryable && isPositiveId(jobId) ? (
                      <Button
                        type="button"
                        onClick={() => retry.mutate(jobId)}
                        disabled={retry.isPending}
                      >
                        {retry.isPending ? 'Retrying…' : 'Retry'}
                      </Button>
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </section>
  );
}

/* ─────────────── Content (latest asset only) ─────────────── */

function ContentSection({
  spaceId,
  sourceId,
  latestAssetId,
  latestJobStatus,
  latestPages,
  deepLinkPageId,
  deepLinkBlockId,
}: {
  spaceId: number;
  sourceId: number;
  latestAssetId?: number;
  latestJobStatus?: string | null;
  latestPages: {
    id?: number;
    pageType?: string;
    extractedText?: string | null;
    pageOrder?: number;
  }[];
  deepLinkPageId: number | null;
  deepLinkBlockId: number | null;
}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const [selectedPageId, setSelectedPageId] = useState<number | null>(null);

  const pages = useMemo(() => {
    return [...latestPages].sort(
      (a, b) => (a.pageOrder ?? 0) - (b.pageOrder ?? 0)
    );
  }, [latestPages]);

  // Auto-select: deep-link first, then first valid page.
  useEffect(() => {
    if (deepLinkPageId !== null && pages.some((p) => p.id === deepLinkPageId)) {
      setSelectedPageId(deepLinkPageId);
      return;
    }
    if (selectedPageId === null && pages.length > 0) {
      const first = pages[0].id;
      if (isPositiveId(first)) {
        setSelectedPageId(first);
      }
    }
  }, [pages, deepLinkPageId, selectedPageId]);

  const status = (latestJobStatus ?? '').trim().toUpperCase();
  const isImagePages = pages.length > 0 && pages.every(
    (p) => (p.pageType ?? '').trim().toUpperCase() === 'IMAGE'
  );
  const allEmpty = pages.length > 0 && pages.every(
    (p) => (p.extractedText ?? '').trim() === ''
  );

  if (!latestAssetId) {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <EmptyState
          title="No file uploaded yet"
          description="Upload a TXT, Markdown, PDF, or image file to get started."
        />
      </section>
    );
  }

  if (status === 'PENDING' || status === 'RUNNING') {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <p className="page__note" role="status" data-testid="processing-note">
          新文件正在处理中，完成后内容将自动更新。
        </p>
      </section>
    );
  }

  if (status === 'FAILED') {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <p className="form__error" role="alert" data-testid="failed-note">
          最新文件处理失败。请查看上方错误信息，或上传其它文件。
        </p>
      </section>
    );
  }

  if (status !== 'SUCCEEDED') {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <p className="muted">等待开始处理。</p>
      </section>
    );
  }

  // SUCCEEDED
  if (pages.length === 0) {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <p className="muted">处理成功，但没有生成内容页面。</p>
      </section>
    );
  }

  const currentIndex = pages.findIndex((p) => p.id === selectedPageId);
  const safeIndex = currentIndex >= 0 ? currentIndex : 0;
  const selectedPage = pages[safeIndex];

  function goToPage(index: number) {
    const page = pages[index];
    if (page && isPositiveId(page.id)) {
      setSelectedPageId(page.id);
      // Sync URL for deep-link / refresh.
      const next = new URLSearchParams(searchParams);
      next.set('page', String(page.id));
      next.delete('block');
      setSearchParams(next, { replace: true });
    }
  }

  return (
    <section className="workbench-section" aria-label="Content">
      <h2 className="section-title">Content</h2>

      {isImagePages && (
        <p className="page__note" data-testid="image-success-note">
          图片已成功处理。当前版本不提供 OCR 文本提取。
        </p>
      )}
      {!isImagePages && allEmpty && (
        <p className="page__note" data-testid="pdf-no-text-note">
          PDF 已成功处理，但当前页面没有可提取文本。
        </p>
      )}

      {/* Page X of N + Prev/Next */}
      <div className="pdf-reader-nav" data-testid="pdf-reader-nav">
        <Button
          type="button"
          onClick={() => goToPage(safeIndex - 1)}
          disabled={safeIndex <= 0}
        >
          Previous
        </Button>
        <span className="pdf-reader-nav__label" data-testid="page-indicator">
          Page {safeIndex + 1} of {pages.length}
        </span>
        <Button
          type="button"
          onClick={() => goToPage(safeIndex + 1)}
          disabled={safeIndex >= pages.length - 1}
        >
          Next
        </Button>
      </div>

      {/* Page list for direct selection */}
      <div className="content-nav" role="navigation" aria-label="Pages">
        {pages.map((page, index) => {
          const pageId = page.id;
          if (!isPositiveId(pageId)) {
            return null;
          }
          const pageType = (page.pageType ?? '').trim().toUpperCase();
          const label =
            pageType === 'IMAGE' ? `Image ${index + 1}` : `Page ${index + 1}`;
          const active = pageId === selectedPageId;
          return (
            <button
              key={pageId}
              type="button"
              className={active ? 'nav-link nav-link--active' : 'nav-link'}
              aria-current={active ? 'page' : undefined}
              onClick={() => goToPage(index)}
            >
              {label}
            </button>
          );
        })}
      </div>

      {selectedPage && isPositiveId(selectedPage.id) && (
        <PageContent
          spaceId={spaceId}
          sourceId={sourceId}
          pageId={selectedPage.id}
          pageType={selectedPage.pageType}
          extractedText={selectedPage.extractedText}
          highlightBlockId={deepLinkBlockId}
        />
      )}
    </section>
  );
}

function PageContent({
  spaceId,
  sourceId,
  pageId,
  pageType,
  extractedText,
  highlightBlockId,
}: {
  spaceId: number;
  sourceId: number;
  pageId: number;
  pageType?: string;
  extractedText?: string | null;
  highlightBlockId: number | null;
}) {
  const api = useApiClient();
  const blocksQuery = useQuery({
    queryKey: queryKeys.contentBlocks(spaceId, sourceId, pageId),
    queryFn: () =>
      api.listContentBlocks(spaceId, sourceId, pageId).then(unwrap),
    enabled: isPositiveId(pageId),
  });

  const isImagePage = (pageType ?? '').trim().toUpperCase() === 'IMAGE';

  // Scroll highlighted block into view.
  useEffect(() => {
    if (highlightBlockId === null) {
      return;
    }
    const el = document.querySelector(
      `[data-block-id="${highlightBlockId}"]`
    );
    if (el && typeof el.scrollIntoView === 'function') {
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
  }, [highlightBlockId, blocksQuery.data]);

  if (blocksQuery.isPending) {
    return <LoadingState text="加载内容块…" />;
  }
  if (blocksQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(blocksQuery.error).message}
        onRetry={() => void blocksQuery.refetch()}
      />
    );
  }

  const blocks = blocksQuery.data ?? [];

  if (isImagePage && blocks.length === 0) {
    return (
      <p className="muted" data-testid="image-page-note">
        图片页面。当前版本不提供 OCR 文本提取。
      </p>
    );
  }

  if (blocks.length === 0) {
    const text = (extractedText ?? '').trim();
    if (text === '') {
      return (
        <p className="muted" data-testid="empty-body-note">
          当前页面没有可提取文本。
        </p>
      );
    }
    // Show extractedText directly when no blocks but text exists.
    return (
      <article className="content-block" data-testid="content-block">
        <pre className="content-block__text">{text}</pre>
      </article>
    );
  }

  return (
    <div className="content-blocks">
      {blocks.map((block, index) => {
        const isHighlighted =
          highlightBlockId !== null && block.id === highlightBlockId;
        return (
          <article
            key={block.id ?? `block-${index}`}
            className={
              isHighlighted
                ? 'content-block content-block--highlighted'
                : 'content-block'
            }
            data-testid="content-block"
            data-block-id={block.id}
          >
            <p className="content-block__type muted">
              {block.blockType ?? 'TEXT'}
              {isPositiveId(block.sortOrder) ? ` · #${block.sortOrder}` : ''}
            </p>
            <pre className="content-block__text">
              {block.normalizedText ?? '—'}
            </pre>
          </article>
        );
      })}
    </div>
  );
}
