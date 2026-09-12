/**
 * Source Workbench (FE-002A PHASE 7+).
 *
 * /spaces/:spaceId/sources/:sourceId — per-source workbench:
 *   metadata · SourceAsset upload/list · IngestionJob status/retry ·
 *   TXT/Markdown content (SourcePage + ContentBlock).
 *
 * File selection uses the standard <input type="file"> (PHASE 4):
 * renderer gets a browser File, FormData goes through the stable
 * shared client, sandbox stays intact, no fs/IPC/path exposure.
 *
 * Upload progress is STAGE-based only (PHASE 10) — never fake
 * byte percentages. Ingestion polling stops on terminal/401/403/404/
 * unmount/route change (PHASE 13).
 *
 * Content viewer shows plain readable text (PHASE 17): no
 * dangerouslySetInnerHTML, no Markdown renderer dependency.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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
import { useFormError } from '../../lib/use-form-error';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

/** FE-002A stable TXT / Markdown only. PDF/Image wait for Batch C. */
const ACCEPTED_FILE_TYPES = '.txt,.md,.markdown,text/plain,text/markdown';

const POLL_INTERVAL_MS = 2000;

export function SourceWorkbenchPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const { spaceId: spaceIdParam, sourceId: sourceIdParam } = useParams();

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

  if (spaceId === null || sourceId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  if (sourceQuery.isPending) {
    return <LoadingState text="加载来源…" />;
  }

  if (sourceQuery.isError) {
    const err = normalizeApiError(sourceQuery.error);
    return (
      <ErrorState
        message={err.message}
        onRetry={() => void sourceQuery.refetch()}
      />
    );
  }

  const source = sourceQuery.data;

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

      <section className="workbench-section" aria-label="Source metadata">
        <h2 className="section-title">Metadata</h2>
        <dl className="meta-list">
          <div>
            <dt>Type</dt>
            <dd>{source?.sourceType ?? '—'}</dd>
          </div>
          <div>
            <dt>Status</dt>
            <dd>
              <StatusBadge status={source?.status} />
            </dd>
          </div>
          <div>
            <dt>Created</dt>
            <dd>{formatDateTime(source?.createdAt)}</dd>
          </div>
        </dl>
      </section>

      <UploadSection spaceId={spaceId} sourceId={sourceId} />
      <AssetsSection spaceId={spaceId} sourceId={sourceId} />
      <IngestionSection spaceId={spaceId} sourceId={sourceId} />
      <ContentSection spaceId={spaceId} sourceId={sourceId} />
    </div>
  );
}

/* �──────────────────────── Upload ──────────────────────── */

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
  const formError = useFormError(
    uploadError ? { message: uploadError } : null
  );

  const uploadAndIngest = useMutation({
    mutationFn: async (selected: File) => {
      setStage('uploading');
      setUploadError(null);
      // unwrap() throws ApiRequestError on HTTP error / missing data.
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
      setStage('ingesting');
      return { asset, job };
    },
    onSuccess: ({ asset, job }) => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sourceAssets(spaceId, sourceId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
      });
      if (isPositiveId(asset.id)) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.sourceAsset(spaceId, sourceId, asset.id),
        });
      }
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
          if (!file || busy) {
            return;
          }
          uploadAndIngest.mutate(file);
        }}
      >
        <div className="form__field">
          <label htmlFor="source-upload-input" className="form__label">
            File (TXT / Markdown)
          </label>
          <input
            id="source-upload-input"
            ref={fileInputRef}
            type="file"
            accept={ACCEPTED_FILE_TYPES}
            disabled={busy}
            onChange={(event) => {
              const picked = event.target.files?.[0] ?? null;
              setFile(picked);
              setUploadError(null);
              setStage(picked ? 'ready' : 'idle');
            }}
          />
        </div>

        {file && (
          <p className="form__note" data-testid="selected-file">
            {file.name} · {formatBytes(file.size)}
            {file.type ? ` · ${file.type}` : ''}
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
            disabled={!file || busy}
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
              queryKey: queryKeys.contentBlocks(spaceId, sourceId),
            });
            void queryClient.invalidateQueries({
              queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
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

/**
 * Polls a single ingestion job until terminal / auth / unmount
 * (FE-002A PHASE 13). Interval 2s. Stops on success, failure,
 * 401/403/404, and never starts for invalid ids.
 */
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
      if (
        errorStatus === 401 ||
        errorStatus === 403 ||
        errorStatus === 404
      ) {
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
    mutationFn: () => {
      return api.retryIngestionJob(spaceId, jobId).then(unwrap);
    },
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
        {isPositiveId(job?.progressPercent) && kind === 'non-terminal'
          ? ` · ${job.progressPercent}%`
          : ''}
      </p>
      {kind === 'failure-terminal' && (
        <div>
          <p className="form__error" role="alert">
            {job?.errorMessage ?? 'Ingestion failed.'}
            {job?.errorCode ? ` (${job.errorCode})` : ''}
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

/* ───────────────────────── Assets ─────────────────────── */

function AssetsSection({
  spaceId,
  sourceId,
}: {
  spaceId: number;
  sourceId: number;
}) {
  const api = useApiClient();
  const assetsQuery = useQuery({
    queryKey: queryKeys.sourceAssets(spaceId, sourceId),
    queryFn: () => api.listSourceAssets(spaceId, sourceId).then(unwrap),
  });

  if (assetsQuery.isPending) {
    return <LoadingState text="加载文件…" />;
  }
  if (assetsQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(assetsQuery.error).message}
        onRetry={() => void assetsQuery.refetch()}
      />
    );
  }

  const assets = assetsQuery.data ?? [];

  return (
    <section className="workbench-section" aria-label="Source assets">
      <h2 className="section-title">Files</h2>
      {assets.length === 0 ? (
        <p className="muted">No files uploaded yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Size</th>
              <th>Uploaded</th>
            </tr>
          </thead>
          <tbody>
            {assets.map((asset, index) => (
              <tr key={asset.id ?? `asset-${index}`}>
                <td>{asset.originalName ?? '—'}</td>
                <td>{asset.mimeType ?? '—'}</td>
                <td>{formatBytes(asset.sizeBytes)}</td>
                <td>{formatDateTime(asset.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/* ─────────────────────── Ingestion list ────────────────── */

function IngestionSection({
  spaceId,
  sourceId,
}: {
  spaceId: number;
  sourceId: number;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const jobsQuery = useQuery({
    queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
    queryFn: () => api.listIngestionJobs(spaceId, sourceId).then(unwrap),
  });

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

  if (jobsQuery.isPending) {
    return <LoadingState text="加载 ingestion 记录…" />;
  }
  if (jobsQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(jobsQuery.error).message}
        onRetry={() => void jobsQuery.refetch()}
      />
    );
  }

  const jobs = jobsQuery.data ?? [];

  return (
    <section className="workbench-section" aria-label="Ingestion jobs">
      <h2 className="section-title">Ingestion</h2>
      {jobs.length === 0 ? (
        <p className="muted">No ingestion jobs yet.</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Status</th>
              <th>Stage</th>
              <th>Created</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {jobs.map((job, index) => {
              const jobId = job.id;
              const retryable = isIngestionRetryable(job.status);
              return (
                <tr key={jobId ?? `job-${index}`}>
                  <td>#{jobId ?? '—'}</td>
                  <td>
                    <StatusBadge status={job.status} />
                  </td>
                  <td>{formatIngestionStage(job.stage, job.status)}</td>
                  <td>{formatDateTime(job.createdAt)}</td>
                  <td>
                    {retryable && isPositiveId(jobId) ? (
                      <Button
                        type="button"
                        onClick={() => retry.mutate(jobId)}
                        disabled={retry.isPending}
                      >
                        Retry
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

/* ───────────────────────── Content ────────────────────── */

function ContentSection({
  spaceId,
  sourceId,
}: {
  spaceId: number;
  sourceId: number;
}) {
  const api = useApiClient();
  const pagesQuery = useQuery({
    queryKey: queryKeys.sourcePages(spaceId, sourceId),
    queryFn: () => api.listSourcePages(spaceId, sourceId).then(unwrap),
  });

  const [selectedPageId, setSelectedPageId] = useState<number | null>(null);

  const pages = useMemo(() => {
    const raw = pagesQuery.data ?? [];
    return [...raw].sort(
      (a, b) => (a.pageOrder ?? 0) - (b.pageOrder ?? 0)
    );
  }, [pagesQuery.data]);

  // Auto-select first page once loaded.
  useEffect(() => {
    if (selectedPageId === null && pages.length > 0) {
      const first = pages[0].id;
      if (isPositiveId(first)) {
        setSelectedPageId(first);
      }
    }
  }, [pages, selectedPageId]);

  if (pagesQuery.isPending) {
    return <LoadingState text="加载内容…" />;
  }
  if (pagesQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(pagesQuery.error).message}
        onRetry={() => void pagesQuery.refetch()}
      />
    );
  }

  if (pages.length === 0) {
    return (
      <section className="workbench-section" aria-label="Content">
        <h2 className="section-title">Content</h2>
        <EmptyState
          title="No extracted content yet"
          description="Upload a TXT or Markdown file and wait for ingestion to finish."
        />
      </section>
    );
  }

  return (
    <section className="workbench-section" aria-label="Content">
      <h2 className="section-title">Content</h2>
      <div className="content-nav" role="navigation" aria-label="Pages">
        {pages.map((page, index) => {
          const pageId = page.id;
          if (!isPositiveId(pageId)) {
            return null;
          }
          // 1-based display label; pageOrder is the backend sort key.
          const displayNumber =
            typeof page.pageOrder === 'number' ? page.pageOrder + 1 : index + 1;
          const label = `Page ${displayNumber}`;
          const active = pageId === selectedPageId;
          return (
            <button
              key={pageId}
              type="button"
              className={
                active ? 'nav-link nav-link--active' : 'nav-link'
              }
              aria-current={active ? 'page' : undefined}
              onClick={() => setSelectedPageId(pageId)}
            >
              {label}
            </button>
          );
        })}
      </div>
      {selectedPageId !== null && (
        <ContentBlocks
          spaceId={spaceId}
          sourceId={sourceId}
          pageId={selectedPageId}
        />
      )}
    </section>
  );
}

function ContentBlocks({
  spaceId,
  sourceId,
  pageId,
}: {
  spaceId: number;
  sourceId: number;
  pageId: number;
}) {
  const api = useApiClient();
  const blocksQuery = useQuery({
    queryKey: queryKeys.contentBlocks(spaceId, sourceId, pageId),
    queryFn: () =>
      api.listContentBlocks(spaceId, sourceId, pageId).then(unwrap),
    enabled: isPositiveId(pageId),
  });

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
  if (blocks.length === 0) {
    return <p className="muted">No content blocks on this page.</p>;
  }

  return (
    <div className="content-blocks">
      {blocks.map((block, index) => (
        <article
          key={block.id ?? `block-${index}`}
          className="content-block"
          data-testid="content-block"
        >
          <p className="content-block__type muted">
            {block.blockType ?? 'TEXT'}
            {isPositiveId(block.sortOrder) ? ` · #${block.sortOrder}` : ''}
          </p>
          {/* Plain readable text — no dangerouslySetInnerHTML (PHASE 17). */}
          <pre className="content-block__text">
            {block.normalizedText ?? '—'}
          </pre>
        </article>
      ))}
    </div>
  );
}
