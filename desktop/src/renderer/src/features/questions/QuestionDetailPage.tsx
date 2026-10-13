/**
 * Question Detail (A4.3, G04, G09).
 *
 * /spaces/:spaceId/questions/:questionId
 *
 * The actions here are the verbs the contract actually has, per
 * `QuestionController.java` and `QuestionService.java`:
 *
 *  - publish  — DRAFT → PUBLISHED only (QuestionService.java:178-196).
 *  - update   — `PUT /questions/{id}`, and it refuses an ARCHIVED question
 *    outright (QuestionService.java:122-126 returns null → 404), so the edit
 *    link is hidden while archived instead of leading to a dead form.
 *  - archive  — writes `status = 'ARCHIVED'` + `archived_at`, nothing else
 *    (QuestionService.java:145-159, QuestionMapper.java:117-124). It is not a
 *    delete: the row stays, options and answer data included.
 *  - restore  — writes `status = 'DRAFT'` and clears `archived_at`
 *    (QuestionService.java:162-176, QuestionMapper.java:126-132). Restoring
 *    therefore does NOT put a published question back to PUBLISHED — it comes
 *    back as a draft and has to be published again. The copy says so.
 *
 * Archived questions still appear in `GET /questions` when no `status` is
 * asked for, and disappear from a `status=DRAFT|PUBLISHED` filter
 * (`selectBySpaceOwnerFilters`, QuestionMapper.java:39-56) — which is why the
 * list page offers 已归档 as a filter value.
 *
 * A stored ORDERING order / MATCHING pair set cannot be shown: the read side
 * (`QuestionAnswerView`) has no field for it, so the renderer prints the
 * contract-gap notice instead of a fake correct answer.
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { useState } from 'react';
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
import {
  CONTRACT_GAP_NOTICES,
  difficultyLabel,
  formatDateTime,
  isPublishableQuestionType,
  questionTypeGap,
  questionTypeLabel,
} from '../../lib/format';
import { Button } from '../../components/Button';
import { Dialog } from '../../components/Dialog';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';

const STATUS_ARCHIVED = 'ARCHIVED';

export function QuestionDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const spaceId = useSpaceId();
  const { questionId: questionIdParam } = useParams();
  const questionId = parsePositiveIdParam(questionIdParam);

  const [confirmingArchive, setConfirmingArchive] = useState(false);

  const query = useQuery(
    entityQuery(
      spaceId,
      questionId,
      queryKeys.question(spaceId, questionId),
      (id, qid) => api.getQuestion(id, qid)
    )
  );

  /**
   * Every action re-reads the question it just changed: the badge, the edit
   * link and the archive/restore button are all derived from `status`, so a
   * stale cache would offer a verb the server has just stopped allowing.
   */
  function invalidateQuestion() {
    void queryClient.invalidateQueries({
      queryKey: queryKeys.question(spaceId, questionId),
    });
    void queryClient.invalidateQueries({
      queryKey: queryKeys.questions(spaceId),
    });
  }

  const publish = useMutation({
    mutationFn: () =>
      api
        .publishQuestion(
          requireId(spaceId, 'space id'),
          requireId(questionId, 'question id')
        )
        .then(unwrap),
    onSuccess: invalidateQuestion,
  });

  const archive = useMutation({
    mutationFn: () =>
      api
        .archiveQuestion(
          requireId(spaceId, 'space id'),
          requireId(questionId, 'question id')
        )
        .then(unwrap),
    onSuccess: () => {
      setConfirmingArchive(false);
      invalidateQuestion();
    },
  });

  const restore = useMutation({
    mutationFn: () =>
      api
        .restoreQuestion(
          requireId(spaceId, 'space id'),
          requireId(questionId, 'question id')
        )
        .then(unwrap),
    onSuccess: invalidateQuestion,
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

  const status = (q.status ?? '').trim().toUpperCase();
  const isArchived = status === STATUS_ARCHIVED;
  const isDraft = status === 'DRAFT';
  // Publishing a question whose correct answer the contract cannot express
  // would hand exams/practice an ungradeable item, so the action is withheld
  // and the missing field is named instead.
  const canPublish = isPublishableQuestionType(q.questionType);
  const gapNotice = questionTypeGap(q.questionType);
  const busy = publish.isPending || archive.isPending || restore.isPending;
  const failing = archive.isError
    ? archive
    : restore.isError
      ? restore
      : publish.isError
        ? publish
        : null;

  return (
    <div className="page">
      <PageHeader
        title={`题目 #${q.id}`}
        actions={
          <>
            <StatusBadge status={q.status} />
            {isDraft && canPublish && (
              <Button
                variant="primary"
                disabled={busy}
                onClick={() => publish.mutate()}
              >
                {publish.isPending ? '发布中…' : '发布'}
              </Button>
            )}
            {/* `PUT` refuses an ARCHIVED question (QuestionService.java:124),
                so there is no edit form to open while archived. */}
            {!isArchived && (
              <Link
                to={`/spaces/${spaceId}/questions/${questionId}/edit`}
                className="nav-link"
                data-testid="question-edit-link"
              >
                编辑
              </Link>
            )}
            {isArchived ? (
              <Button
                variant="secondary"
                disabled={busy}
                onClick={() => restore.mutate()}
                data-testid="question-restore-button"
              >
                {restore.isPending ? '恢复中…' : '恢复'}
              </Button>
            ) : (
              <Button
                variant="danger"
                disabled={busy}
                onClick={() => setConfirmingArchive(true)}
                data-testid="question-archive-button"
              >
                归档
              </Button>
            )}
          </>
        }
      />
      {isDraft && !canPublish && (
        <p className="page__note" role="note" data-testid="publish-gap-notice">
          本题型暂不提供发布（草稿保留）：
          {gapNotice ?? '契约没有为该题型声明可判分的正确答案字段。'}
        </p>
      )}
      {isArchived && (
        <p className="page__note" role="status" data-testid="archived-notice">
          本题已归档：归档只写入 status=ARCHIVED 与 archived_at，题目、选项与答案数据都还在，
          按状态筛选时它会从 DRAFT / PUBLISHED 列表里消失，不带状态的列表仍能看到。
          契约没有删除接口，归档也不能再编辑；点「恢复」会把它带回<strong>草稿</strong>状态，
          需要重新发布。
        </p>
      )}
      <p className="page__note" role="note" data-testid="update-gap-notice">
        {CONTRACT_GAP_NOTICES.QUESTION_UPDATE_FIELDS}
      </p>
      {failing && failing.isError && (
        <p className="form__error" role="alert" data-testid="question-mutation-error">
          {normalizeApiError(failing.error).message}
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
                    <>
                      <Link to={`/spaces/${spaceId}/knowledge/${kpId}`} className="nav-link">
                        知识点 #{kpId}
                      </Link>
                      {/* W16/A9.2: the practice page reads this query key, so a
                          learner standing on a question can drill its knowledge
                          point without retyping an id. */}
                      <Link
                        to={`/spaces/${spaceId}/practice?${new URLSearchParams({
                          knowledgePointId: String(kpId),
                        }).toString()}`}
                        className="nav-link"
                      >
                        练习该知识点
                      </Link>
                    </>
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

      {confirmingArchive && (
        <Dialog
          title="归档这道题目？"
          onClose={() => setConfirmingArchive(false)}
          busy={archive.isPending}
        >
          <p>
            归档不是删除：题目、选项与正确答案都留在服务端，但状态会变成
            ARCHIVED，按 DRAFT / PUBLISHED 筛选的列表里不再出现，
            练习与考试也选不到它。之后可以在本页恢复，
            恢复会把它带回<strong>草稿</strong>状态而不是原来的发布状态，需要重新发布。
          </p>
          <div className="dialog__actions">
            <Button type="button" onClick={() => setConfirmingArchive(false)}>
              取消
            </Button>
            <Button
              type="button"
              variant="danger"
              disabled={archive.isPending}
              onClick={() => archive.mutate()}
              data-testid="confirm-archive"
            >
              {archive.isPending ? '归档中…' : '确认归档'}
            </Button>
          </div>
        </Dialog>
      )}
    </div>
  );
}
