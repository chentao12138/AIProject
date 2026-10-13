/**
 * Exam Detail (A6.2 + W07 / A6.3).
 *
 * /spaces/:spaceId/exams/:examId
 *
 * The exams list already linked here, but the page did not exist, so
 * `getExam` / `publishExam` / `createExamAttempt` had no caller at all:
 * an exam could be created but never reviewed, published or taken.
 *
 * W07 / A6.3 — the paper also lists the learner's OWN past attempts, read from
 * `GET /api/v1/spaces/{spaceId}/exam-attempts?examId=&page=&size=`. The server
 * pages it, so this page renders exactly the `items` of the page it asked for
 * and drives its pager from the server's `totalItems` / `totalPages`; a local
 * slice of some other page would show rows that are not there. Each row links
 * to the attempt route, where A6.2 resumes an unfinished attempt and a
 * submitted one opens its read-only result.
 */

import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery, spaceQuery } from '../../lib/queries';
import { useSpaceId, requireId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { isPositiveId, parsePositiveIdParam } from '../../lib/ids';
import { formatDateTime } from '../../lib/format';
import type {
  ExamAttemptPage,
  ExamAttemptSummary,
  ExamDetail,
} from '../../lib/types';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { StatusBadge } from '../../components/StatusBadge';
import { examActionError } from './exam-error';
import {
  ATTEMPT_PROGRESS_ACTIONS,
  ATTEMPT_PROGRESS_NOTES,
  attemptProgressOf,
  attemptScoreText,
} from './exam-attempt-history';

/**
 * Rows per page, asked of the server. The contract's own default is 20; a
 * shorter page is friendlier on a detail screen, and either way the pager is
 * the server's (`totalPages`), never this list's length. Exported so the tests
 * assert the exact `size` that goes out rather than re-guessing it.
 */
export const ATTEMPT_HISTORY_PAGE_SIZE = 10;

/**
 * An absent envelope is "no attempt of mine on record yet", not a failure —
 * the same reading the practice side uses for its answer list. Only `items` is
 * filled in: the page numbers are the server's, and this stub must not invent
 * a `totalPages` for a read that sent none.
 */
const EMPTY_ATTEMPT_HISTORY: ExamAttemptPage = { items: [] };

/**
 * 0-based, like the server's own `page`. A garbage or negative URL value
 * falls back to the first page instead of requesting a page that cannot exist.
 */
function parseHistoryPage(raw: string | null): number {
  const value = Number(raw ?? '0');
  return Number.isInteger(value) && value > 0 ? value : 0;
}

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

  /**
   * W07 — this learner's own attempts on this paper, one server-chosen page at
   * a time. `page` lives in the URL (like every other paged list here) so a
   * reload or a shared link lands on the same page, and the query key carries
   * `examId` + `page` + `size` so pages never overwrite each other's cache.
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const historyPage = parseHistoryPage(searchParams.get('page'));
  const historyQuery = useQuery({
    ...spaceQuery(
      spaceId,
      queryKeys.examAttempts(spaceId, examId, {
        page: historyPage,
        size: ATTEMPT_HISTORY_PAGE_SIZE,
      }),
      async (id) => {
        const result = await api.listExamAttempts(id, {
          examId: requireId(examId, 'exam id'),
          page: historyPage,
          size: ATTEMPT_HISTORY_PAGE_SIZE,
        });
        return { ...result, data: result.data ?? EMPTY_ATTEMPT_HISTORY };
      }
    ),
    // Without a readable exam id there is no paper to ask the history of.
    enabled: spaceId !== null && isPositiveId(examId),
  });

  function goToHistoryPage(next: number) {
    const updates = new URLSearchParams(searchParams);
    if (next <= 0) {
      updates.delete('page');
    } else {
      updates.set('page', String(next));
    }
    setSearchParams(updates);
  }

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
        // The new attempt is a row of the history list this page just showed,
        // so its cache goes stale the moment it exists (W07).
        void queryClient.invalidateQueries({
          queryKey: queryKeys.examAttempts(spaceId, examId),
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
   * W07 — the attempt history the contract now offers: `listExamAttempts`
   * filtered to this paper, already paged by the server. Everything below reads
   * that page as it arrived: `items` for the rows, `totalItems` / `totalPages`
   * for the pager. The list is never filtered or sliced here, so a row the
   * server put on page 2 can only be reached by asking for page 2.
   */
  const historyItems: ExamAttemptSummary[] = historyQuery.data?.items ?? [];
  const historyTotalItems = historyQuery.data?.totalItems ?? 0;
  const historyTotalPages = historyQuery.data?.totalPages ?? 0;

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

      <section className="workbench-section" aria-label="作答历史">
        <h2 className="section-title">我的作答历史</h2>
        {historyQuery.isPending && <LoadingState text="加载作答历史…" />}
        {/* W05: a history read that failed is said so, with the retry that can
            fix it — an empty list must never be the fallback for an error. */}
        {historyQuery.isError && (
          <ErrorState
            message={normalizeApiError(historyQuery.error).message}
            onRetry={() => void historyQuery.refetch()}
          />
        )}
        {historyQuery.isSuccess && historyItems.length === 0 && (
          <EmptyState
            title="还没有作答记录"
            description="开始考试后，你在本空间的每一次作答都会列在这里。"
          />
        )}
        {historyQuery.isSuccess && historyItems.length > 0 && (
          <>
            <p className="page__note" data-testid="attempt-history-total">
              第 {historyPage + 1} / {Math.max(historyTotalPages, 1)} 页 · 共{' '}
              {historyTotalItems} 条 · 每页 {ATTEMPT_HISTORY_PAGE_SIZE} 条
            </p>
            <table className="table">
              <thead>
                <tr>
                  <th>作答</th>
                  <th>开始时间</th>
                  <th>交卷时间</th>
                  <th>状态</th>
                  <th>得分</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {historyItems.map((row, index) => {
                  // The server's own status, plus what it says about grading:
                  // 进行中 / 已交卷等待评分 / 已评分 are three different rows,
                  // and only the graded one has a score to show.
                  const progress = attemptProgressOf(row.status, row.score);
                  return (
                    <tr key={row.id ?? `row-${index}`}>
                      <td>{isPositiveId(row.id) ? `#${row.id}` : '—'}</td>
                      <td>{formatDateTime(row.startedAt)}</td>
                      <td>{formatDateTime(row.submittedAt)}</td>
                      <td>
                        <StatusBadge status={row.status} />{' '}
                        <span className="muted">{ATTEMPT_PROGRESS_NOTES[progress]}</span>
                      </td>
                      {/* Null score on an ungraded attempt renders as —, never 0. */}
                      <td data-testid={isPositiveId(row.id) ? `attempt-score-${row.id}` : undefined}>
                        {attemptScoreText(row)}
                      </td>
                      <td>
                        {isPositiveId(row.id) ? (
                          <Link
                            className="nav-link"
                            to={`/spaces/${spaceId}/exams/${examId}/attempts/${row.id}`}
                          >
                            {ATTEMPT_PROGRESS_ACTIONS[progress]} #{row.id}
                          </Link>
                        ) : (
                          '—'
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {historyTotalPages > 1 && (
              <div className="form__actions">
                <Button
                  type="button"
                  variant="secondary"
                  disabled={historyPage === 0 || historyQuery.isFetching}
                  data-testid="attempt-history-prev-page"
                  onClick={() => goToHistoryPage(historyPage - 1)}
                >
                  上一页
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  disabled={
                    historyPage >= historyTotalPages - 1 || historyQuery.isFetching
                  }
                  data-testid="attempt-history-next-page"
                  onClick={() => goToHistoryPage(historyPage + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}
