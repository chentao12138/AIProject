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
import { isPositiveId } from '../../lib/ids';
import { useFormError } from '../../lib/use-form-error';
import { Button } from '../../components/Button';
import { Card } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { Input } from '../../components/Input';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

/** Generated ids are optional — only finite positive integer ids are
 * navigable (guard lives in lib/ids.ts). */

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
      if (isPositiveId(created?.id)) {
        void navigate(`/spaces/${created.id}/sources`);
      }
    },
  });

  const spaces = spacesQuery.data ?? [];

  return (
    <div className="page">
      <PageHeader
        title="学习空间"
        actions={
          <Button variant="primary" onClick={() => setCreating(true)}>
            创建学习空间
          </Button>
        }
      />

      {spacesQuery.isPending && <LoadingState text="加载空间列表…" />}

      {spacesQuery.isError && (
        <ErrorState
          message={normalizeApiError(spacesQuery.error).message}
          onRetry={() => void spacesQuery.refetch()}
        />
      )}

      {spacesQuery.isSuccess && spaces.length === 0 && (
        <EmptyState
          title="还没有学习空间"
          description="先创建一个学习空间，用来组织学习来源与知识。"
          action={
            <Button variant="primary" onClick={() => setCreating(true)}>
              创建学习空间
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
                title={space.name ?? `空间 #${spaceId ?? '未知'}`}
                actions={<StatusBadge status={space.status} />}
              >
                <p className="card__text">
                  {space.description || '暂无说明'}
                </p>
              </Card>
            );
            return isPositiveId(spaceId) ? (
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
  const formError = useFormError(error);

  return (
    <Dialog title="创建学习空间" onClose={onClose} busy={pending}>
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
          label="名称"
          required
          value={name}
          onChange={(event) => {
            setName(event.target.value);
            formError.clear();
          }}
          placeholder="例如：高一数学"
        />
        <Input
          label="说明（可选）"
          value={description}
          onChange={(event) => {
            setDescription(event.target.value);
            formError.clear();
          }}
        />
        {formError.message && (
          <p className="form__error" role="alert">{formError.message}</p>
        )}
        <div className="form__actions">
          <Button type="submit" variant="primary" disabled={pending || !name.trim()}>
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
