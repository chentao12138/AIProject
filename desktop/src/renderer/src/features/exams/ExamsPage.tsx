/**
 * Exam List (A6.1).
 *
 * /spaces/:spaceId/exams
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId, requireId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function ExamsPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(60);

  const listQuery = useQuery(
    spaceQuery(spaceId, queryKeys.exams(spaceId), (id) => api.listExams(id))
  );

  const create = useMutation({
    mutationFn: () =>
      api
        .createExam(requireId(spaceId, 'space id'), {
          title,
          description: description || undefined,
          durationMinutes,
          questions: [],
        })
        .then(unwrap),
    onSuccess: (exam) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.exams(spaceId) });
      if (exam?.id) {
        navigate(`/spaces/${spaceId}/exams/${exam.id}`);
      }
    },
  });

  if (!spaceId) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  return (
    <div className="page">
      <PageHeader title="Exams" />
      <section className="workbench-section" aria-label="Create exam">
        <h2 className="section-title">Create Exam</h2>
        <form className="form" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
          <div className="form__field">
            <label htmlFor="e-title" className="form__label">Title</label>
            <input id="e-title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} required />
          </div>
          <div className="form__field">
            <label htmlFor="e-desc" className="form__label">Description</label>
            <textarea id="e-desc" className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </div>
          <div className="form__field">
            <label htmlFor="e-duration" className="form__label">Duration (minutes)</label>
            <input id="e-duration" className="input" type="number" min={1} value={durationMinutes} onChange={(e) => setDurationMinutes(Number(e.target.value))} />
          </div>
          {create.isError && (
            <p className="form__error" role="alert">
              {normalizeApiError(create.error as Error).message}
            </p>
          )}
          <div className="form__actions">
            <Button type="submit" variant="primary" disabled={create.isPending}>
              {create.isPending ? 'Creating…' : 'Create Exam'}
            </Button>
          </div>
        </form>
      </section>

      <section className="workbench-section" aria-label="Exam list">
        <h2 className="section-title">Exams</h2>
        {listQuery.isPending && <LoadingState text="加载考试…" />}
        {listQuery.isError && (
          <ErrorState
            message={normalizeApiError(listQuery.error).message}
            onRetry={() => void listQuery.refetch()}
          />
        )}
        {listQuery.isSuccess && listQuery.data.length === 0 && (
          <EmptyState title="No exams yet" description="Create your first exam above." />
        )}
        {listQuery.isSuccess && listQuery.data.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Title</th>
                <th>Status</th>
                <th>Duration</th>
                <th>Questions</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {listQuery.data.map((exam) => (
                <tr key={exam.id}>
                  <td>#{exam.id}</td>
                  <td>{exam.title}</td>
                  <td><StatusBadge status={exam.status} /></td>
                  <td>{exam.timeLimitMinutes ?? '—'}</td>
                  <td>{exam.questions?.length ?? '—'}</td>
                  <td>
                    {spaceId !== null && (
                      <Button type="button" onClick={() => navigate(`/spaces/${spaceId}/exams/${exam.id}`)}>
                        View
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
