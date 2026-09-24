/**
 * Question Detail (A4.3).
 *
 * /spaces/:spaceId/questions/:questionId
 */

import { useQuery, useQueryClient, useMutation } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { entityQuery } from '../../lib/queries';
import { requireId, useSpaceId } from '../../lib/space-scope';
import { parsePositiveIdParam, isPositiveId } from '../../lib/ids';
import { difficultyLabel, formatDateTime, questionTypeLabel } from '../../lib/format';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';

export function QuestionDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const spaceId = useSpaceId();
  const { questionId: questionIdParam } = useParams();
  const questionId = parsePositiveIdParam(questionIdParam);

  const query = useQuery(
    entityQuery(
      spaceId,
      questionId,
      queryKeys.question(spaceId, questionId),
      (id, qid) => api.getQuestion(id, qid)
    )
  );

  const publish = useMutation({
    mutationFn: () =>
      api
        .publishQuestion(
          requireId(spaceId, 'space id'),
          requireId(questionId, 'question id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.question(spaceId, questionId),
      });
      // The list badge would otherwise keep saying DRAFT after publishing.
      void queryClient.invalidateQueries({
        queryKey: queryKeys.questions(spaceId),
      });
    },
  });

  if (!spaceId || !questionId) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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
        title={`题目 #${q.id}`}
        actions={
          <>
            <StatusBadge status={q.status} />
            {isDraft && (
              <Button
                variant="primary"
                disabled={publish.isPending}
                onClick={() => publish.mutate()}
              >
                {publish.isPending ? '发布中…' : '发布'}
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
          <dt>题型</dt>
          <dd>{questionTypeLabel(q.questionType)}</dd>
        </div>
        {q.difficulty && (
          <div className="detail-list__row">
            <dt>难度</dt>
            <dd>{difficultyLabel(q.difficulty)}</dd>
          </div>
        )}
        <div className="detail-list__row">
          <dt>创建时间</dt>
          <dd>{formatDateTime(q.createdAt)}</dd>
        </div>
      </dl>

      {q.knowledgePointIds && q.knowledgePointIds.length > 0 && (
        <section className="workbench-section" aria-label="关联知识点">
          <h2 className="section-title">关联知识点</h2>
          <ul className="point-list">
            {q.knowledgePointIds.map((kpId) => (
              <li key={kpId}>
                  {isPositiveId(kpId) && spaceId !== null ? (
                    <Link to={`/spaces/${spaceId}/knowledge/${kpId}`} className="nav-link">
                      知识点 #{kpId}
                    </Link>
                  ) : (
                    <span className="muted">#{kpId}</span>
                  )}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section className="workbench-section" aria-label="题目内容">
        <h2 className="section-title">题目内容</h2>
        <QuestionRenderer question={payload} answer={answer} mode="review" />
      </section>
    </div>
  );
}
