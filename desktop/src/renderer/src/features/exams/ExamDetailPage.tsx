/**
 * Exam Detail (A6.2).
 *
 * /spaces/:spaceId/exams/:examId
 *
 * The exams list already linked here, but the page did not exist, so
 * `getExam` / `publishExam` / `createExamAttempt` had no caller at all:
 * an exam could be created but never reviewed, published or taken.
 */

import { Link, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery } from '../../lib/queries';
import { useSpaceId, requireId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
import { formatDateTime } from '../../lib/format';
import type { ExamDetail } from '../../lib/types';
import { Button } from '../../components/Button';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { examActionError } from './exam-error';

export function ExamDetailPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();
  const { examId: examIdParam } = useParams();
  const examId = parsePositiveIdParam(examIdParam);

  const examQuery = useQuery(
    entityQuery(
      spaceId,
      examId,
      queryKeys.exam(spaceId, examId),
      (id, exam) => api.getExam(id, exam)
    )
  );

  const publish = useMutation({
    mutationFn: () =>
      api
        .publishExam(
          requireId(spaceId, 'space id'),
          requireId(examId, 'exam id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.exam(spaceId, examId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.exams(spaceId),
      });
    },
  });

  const startAttempt = useMutation({
    mutationFn: () =>
      api
        .createExamAttempt(
          requireId(spaceId, 'space id'),
          requireId(examId, 'exam id')
        )
        .then(unwrap),
    onSuccess: (attempt) => {
      if (attempt?.id) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.examAttempt(spaceId, attempt.id),
        });
        navigate(
          `/spaces/${spaceId}/exams/${examId}/attempts/${attempt.id}`
        );
      }
    },
  });

  if (spaceId === null || examId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }
  if (examQuery.isPending) {
    return <LoadingState text="加载考试…" />;
  }
  if (examQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(examQuery.error).message}
        onRetry={() => void examQuery.refetch()}
      />
    );
  }

  const exam: ExamDetail | undefined = examQuery.data;
  if (!exam) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const status = (exam.status ?? '').toUpperCase();
  const questions = exam.questions ?? [];

  /*
   * W07 — CONTRACT GAP, deliberately not implemented.
   * This page cannot offer an entry to past attempts: the contract exposes no
   * attempt-list endpoint at all. `GET /exams/{examId}` returns `ExamResponse`
   * = {id, spaceId, title, description, examType, timeLimitMinutes, totalScore,
   * status, paperVersion, questions, createdAt, updatedAt, publishedAt} — no
   * attempts/attempts field — and the only attempt reads are
   * `GET /exam-attempts/{attemptId}`, `.../result` and `.../diagnosis`, all of
   * which already need an attemptId we have no way to discover here.
   * `POST /exams/{examId}/sessions` always CREATES a new attempt, so "开始考试"
   * is not a history entry and must not be labelled as one. Recorded as a
   * contract request (C10 / W07): `GET /exams/{examId}/attempts` (or an
   * `attempts` array on ExamResponse).
   */

  return (
    <div className="page">
      <PageHeader title={exam.title ?? `Exam #${examId}`} />

      {exam.description && <p className="page__note">{exam.description}</p>}
      {exam.publishedAt && (
        <p className="page__note">发布时间：{formatDateTime(exam.publishedAt)}</p>
      )}

      <dl className="detail-list">
        <div className="detail-list__row">
          <dt>状态</dt>
          <dd>
            <StatusBadge status={exam.status} />
          </dd>
        </div>
        <div className="detail-list__row">
          <dt>时长</dt>
          <dd>{exam.timeLimitMinutes ?? '—'} 分钟</dd>
        </div>
        <div className="detail-list__row">
          <dt>总分</dt>
          <dd>{exam.totalScore ?? '—'}</dd>
        </div>
        <div className="detail-list__row">
          <dt>题目数</dt>
          <dd>{questions.length}</dd>
        </div>
        <div className="detail-list__row">
          <dt>创建时间</dt>
          <dd>{formatDateTime(exam.createdAt)}</dd>
        </div>
      </dl>

      <div className="form__actions">
        {status === 'DRAFT' && (
          <Button
            type="button"
            variant="primary"
            disabled={publish.isPending}
            onClick={() => publish.mutate()}
          >
            {publish.isPending ? '发布中…' : '发布考试'}
          </Button>
        )}
        {status === 'PUBLISHED' && (
          <Button
            type="button"
            variant="primary"
            disabled={startAttempt.isPending}
            onClick={() => startAttempt.mutate()}
          >
            {startAttempt.isPending ? '开始中…' : '开始考试'}
          </Button>
        )}
        <Button
          type="button"
          variant="ghost"
          onClick={() => navigate(`/spaces/${spaceId}/exams`)}
        >
          返回考试列表
        </Button>
      </div>

      {/* W05: a failed action states what went wrong in readable Chinese and
          offers the retry that can actually help. */}
      {publish.error && (
        <ErrorState
          message={examActionError(publish.error).message}
          onRetry={() => publish.mutate()}
        />
      )}
      {startAttempt.error && (
        <ErrorState
          message={examActionError(startAttempt.error).message}
          onRetry={() => startAttempt.mutate()}
        >
          <Link className="nav-link" to={`/spaces/${spaceId}/exams`}>
            返回考试列表
          </Link>
        </ErrorState>
      )}

      <section className="workbench-section" aria-label="试卷列表">
        <h2 className="section-title">题目</h2>
        {questions.length === 0 ? (
          <p className="page__note">这份考试还没有题目，请在考试中添加题目后再发布。</p>
        ) : (
          <table className="table">
            <thead>
              <tr>
                <th>#</th>
                <th>题型</th>
                <th>题干</th>
                <th>分值</th>
              </tr>
            </thead>
            <tbody>
              {questions.map((question, index) => (
                <tr key={question.examQuestionId ?? index}>
                  <td>{question.sortOrder ?? index + 1}</td>
                  <td>{question.questionType ?? '—'}</td>
                  <td>
                    {question.questionId ? (
                      <Link
                        className="nav-link"
                        to={`/spaces/${spaceId}/questions/${question.questionId}`}
                      >
                        {question.stem ?? `Question #${question.questionId}`}
                      </Link>
                    ) : (
                      (question.stem ?? '—')
                    )}
                  </td>
                  <td>{question.score ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
