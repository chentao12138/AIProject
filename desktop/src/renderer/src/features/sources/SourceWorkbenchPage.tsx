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
 *   - Asset/job pairing is strict (W14): only the LATEST asset's own job
 *     describes it. An asset without a job shows "尚未开始解析"; a sibling
 *     asset's SUCCEEDED/FAILED is never borrowed.
 *   - Upload-another: same flow, appends to history, updates latest.
 *   - PDF reader: Page X of N, Prev/Next, aria-current, stable selection.
 *     The reader selection is read from `?page=` (W15), and the URL is
 *     normalized whenever it points at a page of a different asset, so
 *     state, highlight and history can never disagree with the screen.
 *     A `page`/`block` that cannot belong to the current asset is REMOVED
 *     once the source's asset+page lists have arrived (W15: an asset with no
 *     pages yet must not keep another asset's page in the URL); before they
 *     arrive the URL is left alone, because nothing is known yet and a valid
 *     deep link must survive the load.
 *   - Cross-module shortcuts (W16): source / page / content block each offer
 *     新建笔记 with the ids the contract really carries for that entity, and
 *     问 AI opens the AI workspace WITH that entity as message context
 *     (A3.2 / G07): each entry names the narrowest id of the entity on screen
 *     via the AI page's `attachSourceId` / `attachSourcePageId` /
 *     `attachContentBlockId` keys, which `AiChatPage` puts into
 *     `SendMessageRequest` and lists above the composer. One pointer per entry,
 *     because the backend derives the levels above it from the one it is given.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
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
  resolveJobForAsset,
  selectLatestAsset,
  type AssetJobResolution,
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
  const spaceId = useSpaceId();
  const { sourceId: sourceIdParam } = useParams();

  const sourceId = parsePositiveIdParam(sourceIdParam);

  const sourceQuery = useQuery(
    entityQuery(
      spaceId,
      sourceId,
      queryKeys.source(spaceId, sourceId),
      (id, sid) => api.getSource(id, sid)
    )
  );

  const assetsQuery = useQuery(
    entityQuery(
      spaceId,
      sourceId,
      queryKeys.sourceAssets(spaceId, sourceId),
      (id, sid) => api.listSourceAssets(id, sid)
    )
  );

  const jobsQuery = useQuery(
    entityQuery(
      spaceId,
      sourceId,
      queryKeys.ingestionJobs(spaceId, sourceId),
      (id, sid) => api.listIngestionJobs(id, sid)
    )
  );

  const pagesQuery = useQuery(
    entityQuery(
      spaceId,
      sourceId,
      queryKeys.sourcePages(spaceId, sourceId),
      (id, sid) => api.listSourcePages(id, sid)
    )
  );

  if (spaceId === null || sourceId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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
  // W14: the LATEST asset's own job is the only thing that can describe it.
  // The old `?? selectLatestJob(jobs)` fallback answered "asset B has no job"
  // with asset A's SUCCEEDED/FAILED, so a failed upload looked fine and a
  // finished one looked broken. No job for this asset now means "尚未开始解析".
  const assetJob = resolveJobForAsset({
    jobs,
    assetId: latestAsset?.id,
    assetCount: assets.length,
  });
  const latestJob = assetJob.job;
  const latestPages = filterPagesForAsset(allPages, latestAsset?.id);
  // W15: "this asset has no pages" is only a FACT once both the asset list
  // and the page list have arrived for this source. Until then the page set is
  // unknown, so the URL normaliser must not rewrite — let alone delete — a
  // `?page=&block=` deep link on a guess.
  const pageSetResolved = assetsQuery.isSuccess && pagesQuery.isSuccess;

  return (
    <div className="page">
      <PageHeader
        title={source?.title ?? '来源详情'}
        actions={
          <>
            <Button onClick={() => navigate(`/spaces/${spaceId}/sources`)}>
              返回来源列表
            </Button>
            {/* W16: a note started here is attached to THIS source. `sourceId`
                is the only context id a source itself owns — the page and
                content-block links live in the reader below, where those ids
                are on screen. The keys are the Notes list page's own prefill
                keys; its listing keys (`page`/`size`/`keyword`) are not
                touched, and neither is the reader's `?page=`. */}
            <Link
              className="nav-link"
              data-testid="source-note-link"
              to={`/spaces/${spaceId}/notes?${new URLSearchParams({
                sourceId: String(sourceId),
              }).toString()}`}
            >
              新建笔记
            </Link>
            {/* A3.2 / G07: an entry into the AI workspace that carries this
                source as the message context. `attachSourceId` is the AI page's
                own prefill key (NOT the Notes prefill key of the same purpose,
                and not the reader's `page`/`block`), it is the only context id a
                source itself owns, and AiChatPage puts it in the request body as
                `SendMessageRequest.sourceId` — the copy below therefore says
                plainly what travels. Page-level and block-level entries are in
                the reader, where those ids are on screen. */}
            <Link
              className="nav-link"
              data-testid="source-ai-link"
              to={`/spaces/${spaceId}/ai?${new URLSearchParams({
                attachSourceId: String(sourceId),
              }).toString()}`}
            >
              问 AI
            </Link>
          </>
        }
      />
      <p className="page__note" data-testid="ai-entry-note">
        问 AI 打开 AI 辅导，并把这条来源附带为上下文；发送前可在输入框上方看到或取消它。
      </p>

      <BusinessSummary
        source={source}
        latestAsset={latestAsset}
        latestJob={latestJob}
        jobScope={assetJob.source}
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
        jobScope={assetJob.source}
        latestPages={latestPages}
        pageSetResolved={pageSetResolved}
      />
    </div>
  );
}

/* ─────────────── Business summary ─────────────── */

function BusinessSummary({
  source,
  latestAsset,
  latestJob,
  jobScope,
  latestPages,
  assetsQueryPending,
  jobsQueryPending,
  pagesQueryPending,
}: {
  source?: { sourceType?: string; status?: string; createdAt?: string };
  latestAsset: { id?: number; originalName?: string; mimeType?: string; sizeBytes?: number; createdAt?: string } | null;
  latestJob: { id?: number; status?: string; stage?: string; createdAt?: string } | null;
  jobScope: AssetJobResolution['source'];
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
    } else if (latestJob === null) {
      // W14: this asset has no job of its own. Nothing from a sibling asset
      // is borrowed to describe it.
      parts.push('尚未开始解析');
    } else {
      parts.push('等待处理');
    }
    summaryLine = parts.join(' · ');
  }

  return (
    <section className="workbench-section" aria-label="来源概览" data-testid="business-summary">
      <h2 className="section-title">概览</h2>
      <p className="workbench-summary" data-testid="summary-line">
        {summaryLine}
      </p>
      <dl className="meta-list">
        <div>
          <dt>类型</dt>
          <dd>{source?.sourceType ?? '—'}</dd>
        </div>
        <div>
          <dt>来源状态</dt>
          <dd>
            <StatusBadge status={source?.status} />
          </dd>
        </div>
        {latestAsset && (
          <div>
            <dt>最近文件</dt>
            <dd title={latestAsset.originalName}>
              {latestAsset.originalName ?? '—'}
              {latestAsset.sizeBytes !== undefined
                ? ` (${formatBytes(latestAsset.sizeBytes)})`
                : ''}
            </dd>
          </div>
        )}
        {jobScope === 'none' && latestAsset && (
          <div>
            <dt>最近解析</dt>
            <dd data-testid="no-job-for-asset">尚未开始解析</dd>
          </div>
        )}
        {latestJob && (
          <div>
            <dt>最近解析</dt>
            <dd>
              <StatusBadge status={latestJob.status} />
              {jobScope === 'legacy-unlinked' && (
                // Backend rows created before `assetId` was filled: the job is
                // attributed because this source has exactly one asset.
                <span className="muted" data-testid="legacy-job-attribution">
                  {' '}
                  · 历史任务（未关联资产）
                </span>
              )}
            </dd>
          </div>
        )}
        <div>
          <dt>内容</dt>
          <dd data-testid="content-state">
            {CONTENT_STATE_LABELS[contentState]}
          </dd>
        </div>
        {latestAsset?.createdAt && (
          <div>
            <dt>上传时间</dt>
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
    <section className="workbench-section" aria-label="上传文件">
      <h2 className="section-title">上传</h2>
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
            文件（TXT / Markdown / PDF / PNG / JPEG）
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
            {busy ? UPLOAD_STAGE_LABELS[stage] : '上传并解析'}
          </Button>
          <Button onClick={clearFile} disabled={busy || !file} type="button">
            清除
          </Button>
        </div>
      </form>

      {activeJobId !== null && (
        <ActiveJobStatus
          spaceId={spaceId}
          sourceId={sourceId}
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
  sourceId,
  jobId,
  onTerminal,
  onSucceeded,
  onFailed,
}: {
  spaceId: number;
  sourceId: number;
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
    queryFn: () => api.getIngestionJob(spaceId, jobId).then(unwrap),
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
        queryKey: queryKeys.ingestionJobs(spaceId, sourceId),
      });
    },
  });

  if (jobQuery.isPending) {
    return <p className="muted">加载解析任务…</p>;
  }
  if (jobQuery.isError) {
    const err = normalizeApiError(jobQuery.error);
    return (
      <div>
        <p className="form__error" role="alert">
          {err.message}
        </p>
        <Button onClick={() => void jobQuery.refetch()} type="button">
          重新请求状态
        </Button>
      </div>
    );
  }

  const job = jobQuery.data;
  const retryable = isIngestionRetryable(status);

  return (
    <div className="ingestion-active" data-testid="active-job">
      <p role="status" aria-live="polite">
        解析：{formatIngestionStage(job?.stage, status)}
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
              {retry.isPending ? '重试中…' : '重新解析'}
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
    <section className="workbench-section" aria-label="解析历史">
      <h2 className="section-title">解析历史</h2>
      {sorted.length === 0 ? (
        <p className="muted">还没有解析任务。</p>
      ) : (
        <table className="table">
          <thead>
            <tr>
              <th>Job</th>
              <th>文件</th>
              <th>状态</th>
              <th>阶段</th>
              <th>创建时间</th>
              <th>完成时间</th>
              <th>操作</th>
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
                        {retry.isPending ? '重试中…' : '重试'}
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
  jobScope,
  latestPages,
  pageSetResolved,
}: {
  spaceId: number;
  sourceId: number;
  latestAssetId?: number;
  latestJobStatus?: string | null;
  jobScope: AssetJobResolution['source'];
  pageSetResolved: boolean;
  latestPages: {
    id?: number;
    pageType?: string;
    extractedText?: string | null;
    pageOrder?: number;
  }[];
}) {
  const [searchParams, setSearchParams] = useSearchParams();

  const pages = useMemo(() => {
    return [...latestPages].sort(
      (a, b) => (a.pageOrder ?? 0) - (b.pageOrder ?? 0)
    );
  }, [latestPages]);

  // W15: `?page=` is the single source of truth for the selected page, so
  // back/forward/refresh can never disagree with what is rendered. A page id
  // that is not part of the CURRENT asset's pages is a stale deep link — it
  // selects nothing, and the URL is rewritten below instead of staying
  // pointed at another asset's page while pages[0] shows.
  const urlPageId = parsePositiveIdParam(searchParams.get('page') ?? undefined);
  const urlBlockId = parsePositiveIdParam(searchParams.get('block') ?? undefined);
  const pageBelongsToAsset =
    urlPageId !== null && pages.some((page) => page.id === urlPageId);
  const selectedPageId = pageBelongsToAsset
    ? urlPageId
    : (pages.find((page) => isPositiveId(page.id))?.id ?? null);
  // A `?block=` highlight only means something for the page the URL names;
  // after a stale page the block belongs to another asset.
  const highlightBlockId = pageBelongsToAsset ? urlBlockId : null;

  useEffect(() => {
    const next = new URLSearchParams(searchParams);

    if (selectedPageId === null) {
      // The current asset has no page to select. If its page set has settled,
      // any `page`/`block` left in the URL can only be another asset's, so it
      // is removed instead of lingering as residue (and resurrecting on
      // refresh). Before the set settles, nothing is known and the URL stays
      // exactly as it is — that is what keeps a valid deep link alive through
      // the load.
      if (
        !pageSetResolved ||
        (!searchParams.has('page') && !searchParams.has('block'))
      ) {
        return;
      }
      next.delete('page');
      next.delete('block');
      setSearchParams(next, { replace: true });
      return;
    }

    const pageIsCurrent = searchParams.get('page') === String(selectedPageId);
    const blockIsConsistent =
      highlightBlockId !== null || !searchParams.has('block');
    if (pageIsCurrent && blockIsConsistent) {
      return;
    }
    next.set('page', String(selectedPageId));
    if (highlightBlockId === null) {
      next.delete('block');
    }
    // replace: normalizing state is not a navigation the user can go back to.
    setSearchParams(next, { replace: true });
  }, [
    selectedPageId,
    highlightBlockId,
    pageSetResolved,
    searchParams,
    setSearchParams,
  ]);

  const status = (latestJobStatus ?? '').trim().toUpperCase();
  const isImagePages = pages.length > 0 && pages.every(
    (p) => (p.pageType ?? '').trim().toUpperCase() === 'IMAGE'
  );
  const allEmpty = pages.length > 0 && pages.every(
    (p) => (p.extractedText ?? '').trim() === ''
  );

  if (!latestAssetId) {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
        <EmptyState
          title="尚未上传文件"
          description="上传 TXT、Markdown、PDF 或图片文件即可开始。"
        />
      </section>
    );
  }

  // W14: this asset has no job of its own — no sibling asset's
  // SUCCEEDED/FAILED may be shown here.
  if (jobScope === 'none') {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
        <p className="muted" role="status" data-testid="not-started-note">
          尚未开始解析。
        </p>
      </section>
    );
  }

  if (classifyIngestionStatus(status) === 'non-terminal') {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
        <p className="page__note" role="status" data-testid="processing-note">
          新文件正在处理中，完成后内容将自动更新。
        </p>
      </section>
    );
  }

  if (classifyIngestionStatus(status) === 'failure-terminal') {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
        <p className="form__error" role="alert" data-testid="failed-note">
          最新文件处理失败。请查看上方错误信息，或上传其它文件。
        </p>
      </section>
    );
  }

  if (classifyIngestionStatus(status) !== 'success-terminal') {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
        <p className="muted" role="status" data-testid="unknown-status-note">
          等待开始处理。
        </p>
      </section>
    );
  }

  // A terminal-success job: show whatever the pipeline produced.
  if (pages.length === 0) {
    return (
      <section className="workbench-section" aria-label="内容">
        <h2 className="section-title">内容</h2>
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
      // The URL alone selects the page (W15); pushing keeps browser
      // back/forward truthful, and a page change never carries a `?block=`
      // highlight over from the page it belongs to.
      const next = new URLSearchParams(searchParams);
      next.set('page', String(page.id));
      next.delete('block');
      setSearchParams(next);
    }
  }

  return (
    <section className="workbench-section" aria-label="内容">
      <h2 className="section-title">内容</h2>

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
          上一页
        </Button>
        <span className="pdf-reader-nav__label" data-testid="page-indicator">
          Page {safeIndex + 1} of {pages.length}
        </span>
        <Button
          type="button"
          onClick={() => goToPage(safeIndex + 1)}
          disabled={safeIndex >= pages.length - 1}
        >
          下一页
        </Button>
      </div>

      {/* Page list for direct selection */}
      <div className="content-nav" role="navigation" aria-label="页码">
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
        <>
          {/* W16: the entity on screen here is the page, so the note starts
              with the two ids this screen really has — the source from the
              route and this page. Its block, when there is one, is linked
              from the block itself and adds `contentBlockId`.
              A3.2 / G07: the AI entry names only THIS page's id, because the
              backend resolves the owning source from it
              (AiLearningContextService.resolveSelectedContext) and an
              inconsistent source/page pair is a 400 of its own. */}
          <p className="page__note">
            <Link
              className="nav-link"
              data-testid="page-note-link"
              to={`/spaces/${spaceId}/notes?${new URLSearchParams({
                sourceId: String(sourceId),
                sourcePageId: String(selectedPage.id),
              }).toString()}`}
            >
              本页新建笔记
            </Link>
            <Link
              className="nav-link"
              data-testid="page-ai-link"
              to={`/spaces/${spaceId}/ai?${new URLSearchParams({
                attachSourcePageId: String(selectedPage.id),
              }).toString()}`}
            >
              问 AI（本页）
            </Link>
          </p>
          <PageContent
            spaceId={spaceId}
            sourceId={sourceId}
            pageId={selectedPage.id}
            pageType={selectedPage.pageType}
            extractedText={selectedPage.extractedText}
            highlightBlockId={highlightBlockId}
          />
        </>
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
        const blockId = block.id;
        const isHighlighted =
          highlightBlockId !== null && blockId === highlightBlockId;
        return (
          <article
            key={blockId ?? `block-${index}`}
            className={
              isHighlighted
                ? 'content-block content-block--highlighted'
                : 'content-block'
            }
            data-testid="content-block"
            data-block-id={blockId}
          >
            <p className="content-block__type muted">
              {block.blockType ?? 'TEXT'}
              {isPositiveId(block.sortOrder) ? ` · #${block.sortOrder}` : ''}
            </p>
            <pre className="content-block__text">
              {block.normalizedText ?? '—'}
            </pre>
            {isPositiveId(blockId) && (
              <p className="page__note">
                {/* W16: the three ids this content block really has — the
                    source the workbench is open on, the page these blocks were
                    read from, and the block itself. The Notes form keeps the
                    most specific pointer (block > page > source) and lets the
                    server resolve the chain, so none of them is invented. */}
                <Link
                  className="nav-link"
                  data-testid="block-note-link"
                  aria-label={`为片段 #${blockId} 新建笔记`}
                  to={`/spaces/${spaceId}/notes?${new URLSearchParams({
                    sourceId: String(sourceId),
                    sourcePageId: String(pageId),
                    contentBlockId: String(blockId),
                  }).toString()}`}
                >
                  新建笔记
                </Link>
                {/* A3.2 / G07: the block alone is the AI context — the narrowest
                    id here, whose page and source the server derives from the
                    block row itself, so the request cannot contradict itself. */}
                <Link
                  className="nav-link"
                  data-testid="block-ai-link"
                  aria-label={`就片段 #${blockId} 问 AI`}
                  to={`/spaces/${spaceId}/ai?${new URLSearchParams({
                    attachContentBlockId: String(blockId),
                  }).toString()}`}
                >
                  问 AI
                </Link>
              </p>
            )}
          </article>
        );
      })}
    </div>
  );
}
