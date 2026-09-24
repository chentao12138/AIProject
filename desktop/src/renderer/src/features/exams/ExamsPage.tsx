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
  /** questionId -> score. The backend rejects an exam with no questions, and
   * a question without a score, so the form cannot submit either. */
  const [selected, setSelected] = useState<Record<number, number>>({});

  const listQuery = useQuery(
    spaceQuery(spaceId, queryKeys.exams(spaceId), (id) => api.listExams(id))
  );

  const publishedQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.questions(spaceId, { status: 'PUBLISHED' }),
      (id) => api.listQuestions(id, { status: 'PUBLISHED' })
    )
  );

  const questions = Object.entries(selected).map(([questionId, score]) => ({
    questionId: Number(questionId),
    score,
  }));
  const canSubmit = title.trim().length > 0 && questions.length > 0;

  const create = useMutation({
    mutationFn: () =>
      api
        .createExam(requireId(spaceId, 'space id'), {
          title,
          description: description || undefined,
          durationMinutes,
          questions,
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
      <PageHeader title="考试" />
      <section className="workbench-section" aria-label="创建考试">
        <h2 className="section-title">创建考试</h2>
        <form className="form" onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
          <div className="form__field">
            <label htmlFor="e-title" className="form__label">标题</label>
            <input id="e-title" className="input" value={title} onChange={(e) => setTitle(e.target.value)} required />
          </div>
          <div className="form__field">
            <label htmlFor="e-desc" className="form__label">说明</label>
            <textarea id="e-desc" className="textarea" value={description} onChange={(e) => setDescription(e.target.value)} rows={2} />
          </div>
          <div className="form__field">
            <label htmlFor="e-duration" className="form__label">时长（分钟）</label>
            <input id="e-duration" className="input" type="number" min={1} value={durationMinutes} onChange={(e) => setDurationMinutes(Number(e.target.value))} />
          </div>

          <div className="form__field">
            <span className="form__label">题目与分值</span>
            {publishedQuery.isPending && <LoadingState text="加载题目…" />}
            {publishedQuery.isError && (
              <ErrorState
                message={normalizeApiError(publishedQuery.error).message}
                onRetry={() => void publishedQuery.refetch()}
              />
            )}
            {publishedQuery.isSuccess && publishedQuery.data.length === 0 && (
              <EmptyState title="没有已发布的题目" description="请先在 Questions 页面创建并发布题目。" />
            )}
            {publishedQuery.isSuccess && publishedQuery.data.length > 0 && (
              <ul className="point-list">
                {publishedQuery.data.map((q) => {
                  const checked = q.id !== undefined && selected[q.id] !== undefined;
                  return (
                    <li key={q.id} className="question-pick">
                      <label>
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(e) =>
                            setSelected((prev) => {
                              if (q.id === undefined) return prev;
                              if (e.target.checked) return { ...prev, [q.id]: 1 };
                              const { [q.id]: _removed, ...rest } = prev;
                              return rest;
                            })
                          }
                        />
                        <span>{q.stem || `#${q.id}`}</span>
                      </label>
                      {checked && q.id !== undefined && (
                        <label className="muted">
                          分值
                          <input
                            className="input"
                            type="number"
                            min={1}
                            value={selected[q.id]}
                            onChange={(e) =>
                              setSelected((prev) => ({ ...prev, [q.id as number]: Math.max(1, Number(e.target.value) || 1) }))
                            }
                          />
                        </label>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </div>

          {create.isError && (
            <p className="form__error" role="alert">
              {normalizeApiError(create.error as Error).message}
            </p>
          )}
          <div className="form__actions">
            <Button type="submit" variant="primary" disabled={create.isPending || !canSubmit}>
              {create.isPending ? '创建中…' : '创建考试'}
            </Button>
          </div>
        </form>
      </section>

      <section className="workbench-section" aria-label="考试列表">
        <h2 className="section-title">考试列表</h2>
        {listQuery.isPending && <LoadingState text="加载考试…" />}
        {listQuery.isError && (
          <ErrorState
            message={normalizeApiError(listQuery.error).message}
            onRetry={() => void listQuery.refetch()}
          />
        )}
        {listQuery.isSuccess && listQuery.data.length === 0 && (
          <EmptyState title="还没有考试" description="用上面的表单创建第一场考试。" />
        )}
        {listQuery.isSuccess && listQuery.data.length > 0 && (
          <table className="table">
            <thead>
              <tr>
                <th>ID</th>
                <th>标题</th>
                <th>状态</th>
                <th>时长</th>
                <th>题目数</th>
                <th>操作</th>
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
                        查看
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
