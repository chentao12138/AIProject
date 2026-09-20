/**
 * Source Library UI (FE-001 PHASE H, FE-002A PHASE 8).
 *
 * /spaces/:spaceId/sources — list sources (listSources), create with
 * sourceType fixed to DESKTOP_UPLOAD. FE-002A adds navigation into the
 * per-source workbench for valid source IDs only.
 */

import { useState } from 'react';
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
        title="Sources"
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            Create Source
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
              <th>Actions</th>
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
                        Open
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
