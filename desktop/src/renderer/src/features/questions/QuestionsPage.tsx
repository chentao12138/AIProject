/**
 * Question List (A4.2).
 *
 * /spaces/:spaceId/questions
 *
 * Server-driven filters. Only sends filters that the backend
 * contract supports (status, questionType, knowledgePointId).
 */

import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam, isPositiveId } from '../../lib/ids';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';

export function QuestionsPage() {
  const api = useApiClient();
  const navigate = useNavigate();
  const { spaceId: spaceIdParam } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);

  const status = searchParams.get('status') ?? undefined;
  const questionType = searchParams.get('questionType') ?? undefined;
  const knowledgePointId = searchParams.get('knowledgePointId')
    ? Number(searchParams.get('knowledgePointId'))
    : undefined;

  const listQuery = useQuery({
    queryKey: queryKeys.questions(
      spaceId ?? 0,
      status || undefined,
      questionType || undefined,
      isPositiveId(knowledgePointId) ? knowledgePointId : undefined
    ),
    queryFn: () => {
      if (spaceId === null) {
        return Promise.reject(new Error('invalid space id'));
      }
      return api
        .listQuestions(spaceId, {
          status: status || undefined,
          questionType: questionType || undefined,
          knowledgePointId: isPositiveId(knowledgePointId) ? knowledgePointId : undefined,
        })
        .then(unwrap);
    },
    enabled: spaceId !== null,
  });

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
        title="Questions"
        actions={
          <Button
            variant="primary"
            onClick={() => navigate(`/spaces/${spaceId}/questions/new`)}
          >
            New Question
          </Button>
        }
      />

      <section className="workbench-section" aria-label="Filters">
        <h2 className="section-title">Filters</h2>
        <div className="form">
          <div className="form__field">
            <label htmlFor="q-status" className="form__label">Status</label>
            <select
              id="q-status"
              className="select"
              value={status ?? ''}
              onChange={(e) => setFilter('status', e.target.value)}
            >
              <option value="">All</option>
              <option value="DRAFT">Draft</option>
              <option value="PUBLISHED">Published</option>
            </select>
          </div>
          <div className="form__field">
            <label htmlFor="q-type" className="form__label">Type</label>
            <select
              id="q-type"
              className="select"
              value={questionType ?? ''}
              onChange={(e) => setFilter('questionType', e.target.value)}
            >
              <option value="">All</option>
              <option value="SINGLE_CHOICE">Single Choice</option>
              <option value="MULTIPLE_CHOICE">Multiple Choice</option>
              <option value="BOOLEAN">Boolean</option>
              <option value="TEXT_ANSWER">Text Answer</option>
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
        <EmptyState title="No questions yet" description="Create your first question to get started." />
      )}
      {listQuery.isSuccess && items.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>ID</th>
              <th>Type</th>
              <th>Stem</th>
              <th>Status</th>
              <th>Difficulty</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {items.map((q, idx) => (
              <tr key={q.id ?? `q-${idx}`}>
                <td>#{q.id}</td>
                <td>{q.questionType ?? '—'}</td>
                <td>{q.stem?.slice(0, 80) ?? '—'}</td>
                <td><StatusBadge status={q.status} /></td>
                <td>{q.difficulty ?? '—'}</td>
                <td>
                  <Button
                    type="button"
                    onClick={() => navigate(`/spaces/${spaceId}/questions/${q.id}`)}
                  >
                    View
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
