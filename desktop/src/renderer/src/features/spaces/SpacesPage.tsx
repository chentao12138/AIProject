/**
 * LearningSpace UI (FE-001 PHASE G).
 *
 * /spaces — list my spaces (listLearningSpaces), create
 * (createLearningSpace), select (navigate into the space).
 *
 * ownerSubject is NEVER submitted by the renderer — the backend derives
 * it from the JWT.
 */

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import { unwrap, normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { StatusBadge } from '../../components/StatusBadge';

/** Generated ids are optional — only finite positive ids are navigable. */
function isNavigableId(id: unknown): id is number {
  return typeof id === 'number' && Number.isFinite(id) && id > 0;
}

export function SpacesPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [creating, setCreating] = useState(false);

  const spacesQuery = useQuery({
    queryKey: queryKeys.spaces,
    queryFn: () => api.listLearningSpaces().then(unwrap),
  });

  const createSpace = useMutation({
    mutationFn: (body: { name: string; description?: string }) =>
      api.createLearningSpace(body).then(unwrap),
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.spaces });
      setCreating(false);
      // Only navigate when the response carries a finite positive id —
      // never build /spaces/undefined.
      if (isNavigableId(created?.id)) {
        void navigate(`/spaces/${created.id}/sources`);
      }
    },
  });

  const spaces = spacesQuery.data ?? [];

  return (
    <div className="page">
      <div className="page__header">
        <h1 className="page__title">Learning Spaces</h1>
        <Button variant="primary" onClick={() => setCreating(true)}>
          Create Learning Space
        </Button>
      </div>

      {spacesQuery.isPending && <LoadingState text="加载空间列表…" />}

      {spacesQuery.isError && (
        <ErrorState
          message={normalizeApiError(spacesQuery.error).message}
          onRetry={() => void spacesQuery.refetch()}
        />
      )}

      {spacesQuery.isSuccess && spaces.length === 0 && (
        <EmptyState
          title="No learning spaces yet"
          description="Create your first Learning Space to start organizing sources and knowledge."
          action={
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create Learning Space
            </Button>
          }
        />
      )}

      {spacesQuery.isSuccess && spaces.length > 0 && (
        <div className="space-grid">
          {spaces.map((space, index) => {
            const spaceId = space.id;
            const card = (
              <Card
                title={space.name ?? `Space #${spaceId ?? 'unknown'}`}
                actions={<StatusBadge status={space.status} />}
              >
                <p className="card__text">
                  {space.description || 'No description'}
                </p>
              </Card>
            );
            return isNavigableId(spaceId) ? (
              <Link
                key={spaceId}
                to={`/spaces/${spaceId}/sources`}
                className="space-card-link"
              >
                {card}
              </Link>
            ) : (
              <div key={`space-${index}`} className="space-card-link">
                {card}
              </div>
            );
          })}
        </div>
      )}

      {creating && (
        <CreateSpaceDialog
          onClose={() => setCreating(false)}
          onSubmit={(body) => createSpace.mutate(body)}
          pending={createSpace.isPending}
          error={createSpace.isError ? normalizeApiError(createSpace.error) : null}
        />
      )}
    </div>
  );
}

function CreateSpaceDialog({
  onClose,
  onSubmit,
  pending,
  error,
}: {
  onClose: () => void;
  onSubmit: (body: { name: string; description?: string }) => void;
  pending: boolean;
  error: { message: string } | null;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');

  return (
    <Dialog title="Create Learning Space" onClose={onClose}>
      <form
        className="form"
        onSubmit={(event) => {
          event.preventDefault();
          if (!name.trim()) {
            return;
          }
          onSubmit({
            name: name.trim(),
            description: description.trim() || undefined,
          });
        }}
      >
        <Input
          label="Name"
          required
          value={name}
          onChange={(event) => setName(event.target.value)}
          placeholder="e.g. Math 101"
        />
        <Input
          label="Description (optional)"
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />
        {error && <p className="form__error" role="alert">{error.message}</p>}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={pending || !name.trim()}>
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
