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
import { parsePositiveIdParam } from '../../lib/ids';
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
  const { spaceId: spaceIdParam } = useParams();
  const [creating, setCreating] = useState(false);

  const spaceId = parsePositiveIdParam(spaceIdParam);

  const sourcesQuery = useQuery({
    queryKey: queryKeys.sources(spaceId ?? 0),
    queryFn: () => {
      if (spaceId === null) {
        return Promise.reject(new Error('invalid space id'));
      }
      return api.listSources(spaceId).then(unwrap);
    },
    enabled: spaceId !== null,
  });

  const createSource = useMutation({
    mutationFn: (title: string) => {
      if (spaceId === null) {
        return Promise.reject(new Error('invalid space id'));
      }
      return api
        .createSource(spaceId, { title, sourceType: DESKTOP_SOURCE_TYPE })
        .then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.sources(spaceId ?? 0) });
      setCreating(false);
    },
  });

  if (spaceId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const sources = sourcesQuery.data ?? [];

  return (
    <div className="page">
      <PageHeader
        title="Sources"
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            Create Source
          </Button>
        }
      />

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
  const formError = useFormError(error);

  return (
    <Dialog title="Create Source" onClose={onClose} busy={pending}>
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
          onChange={(event) => {
            setTitle(event.target.value);
            formError.clear();
          }}
          placeholder="e.g. Chapter 3 notes"
        />
        <p className="form__note">
          Source type is fixed to DESKTOP_UPLOAD for this client.
        </p>
        {formError.message && (
          <p className="form__error" role="alert">{formError.message}</p>
        )}
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
