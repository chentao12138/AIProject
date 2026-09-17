/**
 * Exam List (A6.1).
 *
 * /spaces/:spaceId/exams
 */

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
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
  const { spaceId: spaceIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(60);

  const listQuery = useQuery({
    queryKey: queryKeys.exams(spaceId ?? 0),
    queryFn: () => {
      if (spaceId === null) return Promise.reject(new Error('invalid space id'));
      return api.listExams(spaceId).then(unwrap);
    },
    enabled: spaceId !== null,
  });

  const create = useMutation({
    mutationFn: () => {
      if (spaceId === null) return Promise.reject(new Error('invalid space id'));
      const body = {
        title,
        description: description || undefined,
        durationMinutes,
        questions: [],
      };
      return api.createExam(spaceId, body).then(unwrap);
    },
    onSuccess: (exam) => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.exams(spaceId ?? 0) });
      if (exam?.id) {
        navigate(`/spaces/${spaceId}/exams/${exam.id}`);
      }
    },
  });

  if (!spaceId) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
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
                    <Button type="button" onClick={() => navigate(`/spaces/${spaceId}/exams/${exam.id}`)}>
                      View
                    </Button>
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
