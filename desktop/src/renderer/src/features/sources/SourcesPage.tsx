/**
 * Source Library UI (FE-001 PHASE H, FE-002A PHASE 8).
 *
 * /spaces/:spaceId/sources — list sources (listSources), create with
 * sourceType fixed to DESKTOP_UPLOAD. FE-002A adds navigation into the
 * per-source workbench for valid source IDs only.
 */

import { useState } from 'react';
import { SOURCE_FORM_LIMITS } from '../../lib/format';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { spaceQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { formatDateTime } from '../../lib/format';
import { isPositiveId } from '../../lib/ids';
import { useFormError } from '../../lib/use-form-error';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

const DESKTOP_SOURCE_TYPE = 'DESKTOP_UPLOAD';

export function SourcesPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const spaceId = useSpaceId();
  const [creating, setCreating] = useState(false);

  const sourcesQuery = useQuery(
    spaceQuery(spaceId, queryKeys.sources(spaceId), (id) => api.listSources(id))
  );

  const createSource = useMutation({
    mutationFn: (title: string) =>
      api
        .createSource(requireId(spaceId, 'space id'), {
          title,
          sourceType: DESKTOP_SOURCE_TYPE,
        })
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.sources(spaceId),
      });
      setCreating(false);
    },
  });

  if (!spaceId) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const sources = sourcesQuery.data ?? [];

  return (
    <div className="page">
      <PageHeader
        title="学习来源"
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            创建学习来源
          </Button>
        }
      />

      {sourcesQuery.isPending && <LoadingState text="加载来源列表…" />}

      {sourcesQuery.isError && (
        <ErrorState
          message={normalizeApiError(sourcesQuery.error).message}
          onRetry={() => void sourcesQuery.refetch()}
        />
      )}

      {sourcesQuery.isSuccess && sources.length === 0 && (
        <EmptyState
          title="还没有学习来源"
          description="先为该空间登记一个学习来源。"
          action={
            <Button variant="primary" onClick={() => setCreating(true)}>
              创建学习来源
            </Button>
          }
        />
      )}

      {sourcesQuery.isSuccess && sources.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>标题</th>
              <th>类型</th>
              <th>状态</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {sources.map((source, index) => {
              const sourceId = source.id;
              const navigable = isPositiveId(sourceId);
              return (
                <tr key={sourceId ?? `source-${index}`}>
                  <td>{source.title ?? '—'}</td>
                  <td>{source.sourceType ?? '—'}</td>
                  <td>
                    <StatusBadge status={source.status} />
                  </td>
                  <td>{formatDateTime(source.createdAt)}</td>
                  <td>
                    {navigable ? (
                      <Link
                        to={`/spaces/${spaceId}/sources/${sourceId}`}
                        className="nav-link"
                      >
                        打开
                      </Link>
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

      {creating && (
        <CreateSourceDialog
          onClose={() => setCreating(false)}
          onSubmit={(title) => createSource.mutate(title)}
          pending={createSource.isPending}
          error={
            createSource.isError ? normalizeApiError(createSource.error) : null
          }
        />
      )}
    </div>
  );
}

function CreateSourceDialog({
  onClose,
  onSubmit,
  pending,
  error,
}: {
  onClose: () => void;
  onSubmit: (title: string) => void;
  pending: boolean;
  error: { message: string } | null;
}) {
  const [title, setTitle] = useState('');
  const formError = useFormError(error);

  return (
    <Dialog title="创建学习来源" onClose={onClose} busy={pending}>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!title.trim()) {
            return;
          }
          onSubmit(title.trim());
        }}
      >
        <Input
          label="标题"
          required
          maxLength={SOURCE_FORM_LIMITS.titleMaxLength}
          value={title}
          onChange={(event) => {
            setTitle(event.target.value);
            formError.clear();
          }}
          placeholder="例如：第三章笔记"
        />
        <p className="form__note">
          Source type is fixed to DESKTOP_UPLOAD for this client.
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
            disabled={pending || !title.trim()}
          >
            {pending ? '创建中…' : '创建'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            取消
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
