/**
 * Question Detail (A4.3).
 *
 * /spaces/:spaceId/questions/:questionId
 */

import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam, isPositiveId } from '../../lib/ids';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { QuestionRenderer, type QuestionPayload } from './QuestionRenderer';

export function QuestionDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam, questionId: questionIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);
  const questionId = parsePositiveIdParam(questionIdParam);

  const query = useQuery({
    queryKey: queryKeys.question(spaceId ?? 0, questionId ?? 0),
    queryFn: () => {
      if (spaceId === null || questionId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.getQuestion(spaceId, questionId).then(unwrap);
    },
    enabled: spaceId !== null && questionId !== null,
  });

  const publish = useMutation({
    mutationFn: () => {
      if (spaceId === null || questionId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.publishQuestion(spaceId, questionId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.question(spaceId ?? 0, questionId ?? 0),
      });
    },
  });

  if (!spaceId || !questionId) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }
  if (query.isPending) {
    return <LoadingState text="加载题目…" />;
  }
  if (query.isError) {
    return (
      <ErrorState
        message={normalizeApiError(query.error).message}
        onRetry={() => void query.refetch()}
      />
    );
  }

  const q = query.data;
  if (!q) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const payload: QuestionPayload = {
    questionType: q.questionType ?? 'SINGLE_CHOICE',
    stem: q.stem ?? '',
    options: q.options?.map((o) => ({
      optionKey: o.optionKey ?? '',
      content: o.content ?? '',
      sortOrder: o.sortOrder,
      isCorrect: o.isCorrect,
    })),
    explanation: q.explanation,
  };

  const answer = q.answer
    ? {
        correctOptionKey: q.answer.correctOptionKey,
        correctOptionKeys: q.answer.correctOptionKeys,
        correctBoolean: q.answer.correctBoolean,
        referenceAnswer: q.answer.referenceAnswer,
        explanation: q.explanation,
      }
    : undefined;

  const isDraft = q.status === 'DRAFT';

  return (
    <div className="page">
      <PageHeader
        title={`Question #${q.id}`}
        actions={
          <>
            <StatusBadge status={q.status} />
            {isDraft && (
              <Button
                variant="primary"
                disabled={publish.isPending}
                onClick={() => publish.mutate()}
              >
                {publish.isPending ? 'Publishing…' : 'Publish'}
              </Button>
            )}
          </>
        }
      />
      {publish.isError && (
        <p className="form__error" role="alert">
          {normalizeApiError(publish.error).message}
        </p>
      )}

      <dl className="detail-list">
        <div className="detail-list__row">
          <dt>Type</dt>
          <dd>{q.questionType ?? '—'}</dd>
        </div>
        {q.difficulty && (
          <div className="detail-list__row">
            <dt>Difficulty</dt>
            <dd>{q.difficulty}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>Created</dt>
          <dd>{formatDateTime(q.createdAt)}</dd>
        </div>
      </dl>

      {q.knowledgePointIds && q.knowledgePointIds.length > 0 && (
        <section className="workbench-section" aria-label="Knowledge Points">
          <h2 className="section-title">Knowledge Points</h2>
          <ul className="point-list">
            {q.knowledgePointIds.map((kpId) => (
              <li key={kpId}>
                  {isPositiveId(kpId) && spaceId !== null ? (
                    <Link to={`/spaces/${spaceId}/knowledge/${kpId}`} className="nav-link">
                      Knowledge Point #{kpId}
                    </Link>
                  ) : (
                    <span className="muted">#{kpId}</span>
                  )}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="workbench-section" aria-label="Question">
        <h2 className="section-title">Content</h2>
        <QuestionRenderer question={payload} answer={answer} mode="review" />
      </section>
    </div>
  );
}

function formatDateTime(value?: string) {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString();
}
