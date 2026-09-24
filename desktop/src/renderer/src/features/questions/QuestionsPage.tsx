/**
 * Question List (A4.2).
 *
 * /spaces/:spaceId/questions
 *
 * Server-driven filters. Only sends filters that the backend
 * contract supports (status, questionType, knowledgePointId).
 */

import { useNavigate, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { spaceQuery } from '../../lib/queries';
import { useSpaceId } from '../../lib/space-scope';
import { isPositiveId } from '../../lib/ids';
import { questionTypeLabel, QUESTION_TYPES } from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function QuestionsPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const spaceId = useSpaceId();

  const status = searchParams.get('status') || undefined;
  const questionType = searchParams.get('questionType') || undefined;
  const rawKnowledgePointId = searchParams.get('knowledgePointId')
    ? Number(searchParams.get('knowledgePointId'))
    : undefined;
  const knowledgePointId = isPositiveId(rawKnowledgePointId)
    ? rawKnowledgePointId
    : undefined;

  const listQuery = useQuery(
    spaceQuery(
      spaceId,
      queryKeys.questions(spaceId, { status, questionType, knowledgePointId }),
      (id) =>
        api.listQuestions(id, {
          status,
          questionType,
          knowledgePointId,
        })
    )
  );

  const items = listQuery.data ?? [];

  function setFilter(key: string, value: string | null) {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === '') {
      next.delete(key);
    } else {
      next.set(key, value);
    }
    setSearchParams(next, { replace: true });
  }

  return (
    <div className="page">
      <PageHeader
        title="题库"
        actions={
          <Button
            variant="primary"
            onClick={() => navigate(`/spaces/${spaceId}/questions/new`)}
          >
            新建题目
          </Button>
        }
      />

      <section className="workbench-section" aria-label="筛选">
        <h2 className="section-title">筛选</h2>
        <div className="form">
          <div className="form__field">
            <label htmlFor="q-status" className="form__label">状态</label>
            <select
              id="q-status"
              className="select"
              value={status ?? ''}
              onChange={(e) => setFilter('status', e.target.value)}
            >
              <option value="">全部</option>
              <option value="DRAFT">草稿</option>
              <option value="PUBLISHED">已发布</option>
            </select>
          </div>
          <div className="form__field">
            <label htmlFor="q-type" className="form__label">题型</label>
            <select
              id="q-type"
              className="select"
              value={questionType ?? ''}
              onChange={(e) => setFilter('questionType', e.target.value)}
            >
              <option value="">全部</option>
              {QUESTION_TYPES.map((t) => (
                <option key={t} value={t}>{questionTypeLabel(t)}</option>
              ))}
            </select>
          </div>
        </div>
      </section>

      {listQuery.isPending && <LoadingState text="加载题目…" />}
      {listQuery.isError && (
        <ErrorState
          message={normalizeApiError(listQuery.error).message}
          onRetry={() => void listQuery.refetch()}
        />
      )}
      {listQuery.isSuccess && items.length === 0 && (
        <EmptyState title="还没有题目" description="先创建一道题，才能开始练习和考试。" />
      )}
      {listQuery.isSuccess && items.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>题型</th>
              <th>题干</th>
              <th>状态</th>
              <th>难度</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {items.map((q, idx) => (
              <tr key={q.id ?? `q-${idx}`}>
                <td>#{q.id}</td>
                <td>{questionTypeLabel(q.questionType)}</td>
                <td>{q.stem?.slice(0, 80) ?? '—'}</td>
                <td><StatusBadge status={q.status} /></td>
                <td>{q.difficulty ?? '—'}</td>
                <td>
                  <Button
                    type="button"
                    onClick={() => spaceId !== null && navigate(`/spaces/${spaceId}/questions/${q.id}`)}
                  >
                    查看
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
