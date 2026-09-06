/**
 * Source Library UI (FE-001 PHASE H).
 *
 * /spaces/:spaceId/sources — list sources (listSources), create with
 * sourceType fixed to DESKTOP_UPLOAD (the UI never exposes an admin
 * sourceType dropdown).
 *
 * FE-001 deliberately does NOT implement file upload: no file input,
 * no drag&drop, no picker IPC, no multipart fetch. A short note tells
 * the user upload arrives with the SourceAsset contract.
 */

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import { unwrap, normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { formatDateTime } from '../../lib/format';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { StatusBadge } from '../../components/StatusBadge';

const DESKTOP_SOURCE_TYPE = 'DESKTOP_UPLOAD';

export function SourcesPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam } = useParams();
  const [creating, setCreating] = useState(false);

  const spaceId = Number(spaceIdParam);
  const spaceIdValid = Number.isFinite(spaceId) && spaceId > 0;

  const sourcesQuery = useQuery({
    queryKey: queryKeys.sources(spaceId),
    queryFn: () => api.listSources(spaceId).then(unwrap),
    enabled: spaceIdValid,
  });

  const createSource = useMutation({
    mutationFn: (title: string) =>
      api
        .createSource(spaceId, { title, sourceType: DESKTOP_SOURCE_TYPE })
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.sources(spaceId) });
      setCreating(false);
    },
  });

  if (!spaceIdValid) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const sources = sourcesQuery.data ?? [];

  return (
    <div className="page">
      <div className="page__header">
        <h1 className="page__title">Sources</h1>
        <Button variant="primary" onClick={() => setCreating(true)}>
          Create Source
        </Button>
      </div>

      <p className="page__note">
        File upload will become available when SourceAsset contract is
        integrated.
      </p>

      {sourcesQuery.isPending && <LoadingState text="加载来源列表…" />}

      {sourcesQuery.isError && (
        <ErrorState
          message={normalizeApiError(sourcesQuery.error).message}
          onRetry={() => void sourcesQuery.refetch()}
        />
      )}

      {sourcesQuery.isSuccess && sources.length === 0 && (
        <EmptyState
          title="No sources yet"
          description="Register your first source for this space."
          action={
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create Source
            </Button>
          }
        />
      )}

      {sourcesQuery.isSuccess && sources.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Title</th>
              <th>Type</th>
              <th>Status</th>
              <th>Created</th>
            </tr>
          </thead>
          <tbody>
            {sources.map((source, index) => (
              <tr key={source.id ?? `source-${index}`}>
                <td>{source.title ?? '—'}</td>
                <td>{source.sourceType ?? '—'}</td>
                <td>
                  <StatusBadge status={source.status} />
                </td>
                <td>{formatDateTime(source.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {creating && (
        <CreateSourceDialog
          onClose={() => setCreating(false)}
          onSubmit={(title) => createSource.mutate(title)}
          pending={createSource.isPending}
          error={createSource.isError ? normalizeApiError(createSource.error) : null}
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

  return (
    <Dialog title="Create Source" onClose={onClose}>
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
          label="Title"
          required
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="e.g. Chapter 3 notes"
        />
        <p className="form__note">
          Source type is fixed to DESKTOP_UPLOAD for this client.
        </p>
        {error && <p className="form__error" role="alert">{error.message}</p>}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={pending || !title.trim()}>
            {pending ? 'Creating…' : 'Create'}
          </Button>
          <Button onClick={onClose} disabled={pending}>
            Cancel
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
