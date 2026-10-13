/**
 * Exam Attempt (A6.2 + A6.3).
 *
 * /spaces/:spaceId/exams/:examId/attempts/:attemptId
 *
 * Rules this page must keep (rationale in exam-attempt-state.ts / exam-error.ts):
 *  - every question slot owns its save state — no flag may be shared across
 *    slots (G03)
 *  - "已保存" only ever comes from the server's `ExamAnswerView.answeredAt` (W04)
 *  - progress and remaining time come from the attempt the server returned (W06)
 *  - every action failure is rendered with the recovery it promises (W05)
 *  - a SUBMITTED attempt is read-only: the result view, never the paper (W04)
 */

import { useMemo, useState } from 'react';
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
import { parsePositiveIdParam, isPositiveId } from '../../lib/ids';
import { EXAM_ATTEMPT_STATUSES, formatDateTime } from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';
import {
  buildAnswerBody,
  countAnswered,
  isSavedOnServer,
  questionSaveStatus,
  QUESTION_SAVE_LABELS,
  type ExamAnswerBody,
  type ExamAnswerPayload,
  type ExamAnswerRecord,
  type ExamQuestionSlot,
} from './exam-attempt-state';
import { examActionError, type ExamActionError } from './exam-error';
import { useExamCountdown } from './useExamCountdown';

/**
 * W05: action failures render through the shared `ErrorState` primitive plus
 * the recovery the copy promised. Deliberately NOT a new error system — it
 * only decides which action the message can honestly offer.
 */
function ActionErrorAlert({
  error,
  onRetry,
  onReload,
  backTo,
}: {
  error: ExamActionError;
  onRetry: () => void;
  onReload: () => void;
  backTo: string;
}) {
  const retryAction =
    error.recovery === 'retry'
      ? onRetry
      : error.recovery === 'reload'
        ? onReload
        : undefined;

  return (
    <ErrorState message={error.message} onRetry={retryAction}>
      {/* The fallback every failure has, including 401 / 403 / 404. */}
      <Link className="nav-link" to={backTo}>
        返回考试列表
      </Link>
    </ErrorState>
  );
}

export function ExamAttemptPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const spaceId = useSpaceId();
  const { attemptId: attemptIdParam } = useParams();
  const attemptId = parsePositiveIdParam(attemptIdParam);

  const attemptQuery = useQuery(
    entityQuery(spaceId, attemptId, queryKeys.examAttempt(spaceId, attemptId), (id, entity) =>
      api.getExamAttempt(id, entity)
    )
  );

  const startAttempt = useMutation({
    mutationFn: () =>
      api
        .startExamAttempt(
          requireId(spaceId, 'space id'),
          requireId(attemptId, 'attempt id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId, attemptId),
      });
    },
  });

  const submitAttempt = useMutation({
    mutationFn: () =>
      api
        .submitExamAttempt(
          requireId(spaceId, 'space id'),
          requireId(attemptId, 'attempt id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId, attemptId),
      });
      // The graded result is read back from the server afterwards
      // (`getExamResult`), never from this response, so a revisit of a
      // SUBMITTED attempt shows the same thing as the first one (W04).
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examResult(spaceId, attemptId),
      });
    },
  });

  /*
   * W06 — how an overdue attempt gets closed.
   * `GET /exam-attempts/{attemptId}` does NOT finalize: `ExamAttemptService
   * .getMine` only reads the row, so re-fetching an IN_PROGRESS attempt whose
   * `deadlineAt` has passed just returns the same overdue row forever.
   * `getResult` is the contract's own way out — it runs `finalizeIfExpired`
   * before answering, grades what was saved, and persists SUBMITTED. Reading
   * the result therefore ends the exam on the SERVER's terms, and the attempt
   * re-read that follows lands this page on its read-only result view (W04).
   */
  const endOverdueAttempt = useMutation({
    mutationFn: () =>
      api
        .getExamResult(
          requireId(spaceId, 'space id'),
          requireId(attemptId, 'attempt id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId, attemptId),
      });
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examResult(spaceId, attemptId),
      });
    },
  });

  if (spaceId === null || attemptId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }
  if (attemptQuery.isPending) {
    return <LoadingState text="加载考试…" />;
  }
  if (attemptQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(attemptQuery.error).message}
        onRetry={() => void attemptQuery.refetch()}
      />
    );
  }

  const attempt = attemptQuery.data;
  if (!attempt) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const rawQuestions = (attempt.questions ?? []) as ExamQuestionSlot[];
  // ExamAttemptService only ever reports NOT_STARTED / IN_PROGRESS / SUBMITTED.
  // Matching CREATED/PENDING meant a fresh attempt skipped its start screen, so
  // every answer was rejected with 409 "exam attempt is not IN_PROGRESS".
  const status = (attempt.status ?? '').toUpperCase();

  // W05: mapped once here so the start screen and the answer screen show the
  // same copy for the same failure.
  const startError = startAttempt.error ? examActionError(startAttempt.error) : null;
  const submitError = submitAttempt.error
    ? examActionError(submitAttempt.error)
    : null;
  const reloadAttempt = () => void attemptQuery.refetch();
  const examsPath = `/spaces/${spaceId}/exams`;

  if (status === EXAM_ATTEMPT_STATUSES.NOT_STARTED) {
    return (
      <div className="page">
        <PageHeader title={`考试作答 #${attempt.id}`} />
        <p className="page__note">共 {rawQuestions.length} 题。</p>
        {attempt.deadlineAt && (
          <p className="page__note">
            截止时间：{formatDateTime(attempt.deadlineAt)}
          </p>
        )}
        <Button
          variant="primary"
          disabled={startAttempt.isPending}
          onClick={() => startAttempt.mutate()}
        >
          {startAttempt.isPending ? '开始中…' : '开始考试'}
        </Button>
        {startError && (
          <ActionErrorAlert
            error={startError}
            onRetry={() => startAttempt.mutate()}
            onReload={reloadAttempt}
            backTo={examsPath}
          />
        )}
      </div>
    );
  }

  if (status === EXAM_ATTEMPT_STATUSES.SUBMITTED) {
    // W04: read-only by construction — the result view renders no answer
    // input, no save button and no 交卷, so a submitted attempt can never be
    // written to again.
    return <ExamResult spaceId={spaceId} attemptId={attemptId} />;
  }

  return (
    <ActiveExam
      spaceId={spaceId}
      attemptId={attemptId}
      questions={rawQuestions}
      deadlineAt={attempt.deadlineAt}
      onSubmit={() => submitAttempt.mutate()}
      submitting={submitAttempt.isPending}
      submitError={submitError}
      onRetrySubmit={() => submitAttempt.mutate()}
      onReloadAttempt={reloadAttempt}
      onEndOverdue={() => endOverdueAttempt.mutate()}
      endingOverdue={endOverdueAttempt.isPending}
      overdueError={
        endOverdueAttempt.error ? examActionError(endOverdueAttempt.error) : null
      }
      backTo={examsPath}
    />
  );
}

function ActiveExam({
  spaceId,
  attemptId,
  questions,
  deadlineAt,
  onSubmit,
  submitting,
  submitError,
  onRetrySubmit,
  onReloadAttempt,
  onEndOverdue,
  endingOverdue,
  overdueError,
  backTo,
}: {
  spaceId: number;
  attemptId: number;
  questions: ExamQuestionSlot[];
  deadlineAt?: string | null;
  onSubmit: () => void;
  submitting: boolean;
  submitError: ExamActionError | null;
  onRetrySubmit: () => void;
  onReloadAttempt: () => void;
  onEndOverdue: () => void;
  endingOverdue: boolean;
  overdueError: ExamActionError | null;
  backTo: string;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<number, ExamAnswerPayload>>({});
  /**
   * G03 + W04: the server's `ExamAnswerView` per `examQuestionId`. This map —
   * not `mutation.isSuccess`, not a boolean — is what makes a slot "已保存",
   * and a save on slot A can never mark slot B.
   */
  const [serverRecords, setServerRecords] = useState<
    Record<number, ExamAnswerRecord>
  >({});
  /** Draft touched after that record arrived -> the save is stale again. */
  const [editedSinceSave, setEditedSinceSave] = useState<Record<number, boolean>>({});
  const [answerErrors, setAnswerErrors] = useState<Record<number, ExamActionError>>({});

  const current = questions[currentIndex];
  const total = questions.length;

  const slotIds = useMemo(
    () => questions.map((question) => question.examQuestionId),
    [questions]
  );
  // W06: counted from server-confirmed records only, so an unsaved draft is
  // never reported as answered.
  const answeredCount = useMemo(
    () => countAnswered(slotIds, serverRecords),
    [slotIds, serverRecords]
  );

  // W06: remaining time over the SERVER's deadline; no deadlineAt -> no timer.
  const timing = useExamCountdown(deadlineAt);
  const expired = timing.expired;

  const submitAnswer = useMutation({
    mutationFn: (body: ExamAnswerBody) =>
      api.submitExamAnswer(spaceId, attemptId, body).then(unwrap),
    onSuccess: (record: ExamAnswerRecord | undefined, body: ExamAnswerBody) => {
      if (isSavedOnServer(record)) {
        setServerRecords((prev) => ({
          ...prev,
          [body.examQuestionId]: record as ExamAnswerRecord,
        }));
        setEditedSinceSave((prev) => ({ ...prev, [body.examQuestionId]: false }));
        setAnswerErrors((prev) => {
          const next = { ...prev };
          delete next[body.examQuestionId];
          return next;
        });
      } else {
        // A 2xx with no `answeredAt` is NOT proof of a stored answer (W04), so
        // it must not be shown as saved either.
        setAnswerErrors((prev) => ({
          ...prev,
          [body.examQuestionId]: {
            message: '服务器未返回已保存的答题记录，请重试保存。',
            recovery: 'retry',
          },
        }));
      }
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId, attemptId),
      });
    },
    onError: (error: unknown, body: ExamAnswerBody) => {
      // W05: filed under the slot that failed, so switching questions never
      // shows another question's failure.
      setAnswerErrors((prev) => ({
        ...prev,
        [body.examQuestionId]: examActionError(error),
      }));
    },
  });

  if (!current) {
    return <EmptyState title="本次考试没有题目。" />;
  }

  const currentId = current.examQuestionId;
  const currentAnswer = answers[currentId];
  const currentError = answerErrors[currentId];

  const saveStatus = questionSaveStatus({
    // `variables` is the in-flight slot, so only THAT button reads "保存中…".
    saving:
      submitAnswer.isPending && submitAnswer.variables?.examQuestionId === currentId,
    failed: currentError != null,
    serverRecord: serverRecords[currentId],
    editedSinceSave: editedSinceSave[currentId] === true,
  });

  function handleSubmit() {
    if (expired) return;
    submitAnswer.mutate(buildAnswerBody(current, currentAnswer));
  }

  /**
   * Any edit marks the slot dirty. `QuestionRenderer` has ONE `disabled` prop
   * shared by the inputs and the submit button, so a saved slot keeps its
   * inputs unlocked (the backend overwrites an answer for the same
   * examQuestionId — ExamAttemptService.answer) and the label carries the
   * state. See the report: a `submitDisabled` split would let "已保存" lock
   * the button only.
   */
  function editDraft(patch: (previous: ExamAnswerPayload | undefined) => ExamAnswerPayload) {
    setEditedSinceSave((prev) => ({ ...prev, [currentId]: true }));
    setAnswers((prev) => ({ ...prev, [currentId]: patch(prev[currentId]) }));
  }

  function goNext() {
    if (currentIndex < total - 1) setCurrentIndex(currentIndex + 1);
  }
  function goPrev() {
    if (currentIndex > 0) setCurrentIndex(currentIndex - 1);
  }

  // The contract types `optionKey`/`content` as optional, while the renderer
  // needs both: an option without one can be neither labelled nor submitted.
  const renderableOptions = (current.options ?? []).filter(
    (option): option is { optionKey: string; content: string; sortOrder?: number } =>
      typeof option.optionKey === 'string' &&
      typeof option.content === 'string'
  );

  const payload: QuestionPayload = {
    questionType: current.questionType,
    stem: current.stem ?? '',
    options: renderableOptions,
  };

  return (
    <div className="page">
      <PageHeader
        title={`考试作答 #${attemptId}`}
        actions={
          expired ? (
            // Past the server's deadline 交卷 can only ever 409 (`submit`
            // finalizes first, then refuses a non-IN_PROGRESS attempt), so the
            // honest action here is the one that ends and reveals the exam.
            <Button
              type="button"
              variant="primary"
              onClick={onEndOverdue}
              disabled={endingOverdue}
            >
              {endingOverdue ? '处理中…' : '结束并查看成绩'}
            </Button>
          ) : (
            <Button type="button" variant="primary" onClick={onSubmit} disabled={submitting}>
              {submitting ? '交卷中…' : '交卷'}
            </Button>
          )
        }
      />
      <div className="practice-layout">
        <div className="practice-main">
          <div className="practice-progress" role="status">
            第 {currentIndex + 1} / {total} 题 · 已答 {answeredCount} · 未答{' '}
            {total - answeredCount}
          </div>
          {timing.hasDeadline && (
            <p className="page__note">
              {expired
                ? '答题时间已结束，作答已停止。点击上方按钮结束本次考试并查看成绩。'
                : `剩余时间 ${timing.text}`}
            </p>
          )}
          {overdueError && (
            <ActionErrorAlert
              error={overdueError}
              onRetry={onEndOverdue}
              onReload={onReloadAttempt}
              backTo={backTo}
            />
          )}
          <QuestionRenderer
            question={payload}
            mode="answer"
            selectedOptionKeys={currentAnswer?.selectedOptionKeys}
            booleanAnswer={currentAnswer?.booleanAnswer}
            textAnswer={currentAnswer?.textAnswer}
            onSelectedOptionKeysChange={(keys) =>
              editDraft((previous) => ({ ...previous, selectedOptionKeys: keys }))
            }
            onBooleanAnswerChange={(val) =>
              editDraft((previous) => ({ ...previous, booleanAnswer: val }))
            }
            onTextAnswerChange={(val) =>
              editDraft((previous) => ({ ...previous, textAnswer: val }))
            }
            onSubmit={handleSubmit}
            submitLabel={QUESTION_SAVE_LABELS[saveStatus]}
            disabled={saveStatus === 'saving' || expired}
          />
          {currentError && (
            <p className="form__error" role="alert">
              {currentError.message}
            </p>
          )}
          {submitError && (
            <ActionErrorAlert
              error={submitError}
              onRetry={onRetrySubmit}
              onReload={onReloadAttempt}
              backTo={backTo}
            />
          )}
          <div className="practice-nav">
            <Button type="button" onClick={goPrev} disabled={currentIndex <= 0}>
              上一题
            </Button>
            <Button type="button" onClick={goNext} disabled={currentIndex >= total - 1}>
              下一题
            </Button>
          </div>
        </div>
        <aside className="practice-sidebar">
          <h3 className="section-title">题目</h3>
          <ol className="practice-question-list">
            {questions.map((question, idx) => {
              const qid = question.examQuestionId;
              const savedHere = isSavedOnServer(serverRecords[qid]);
              const savingHere =
                submitAnswer.isPending && submitAnswer.variables?.examQuestionId === qid;
              return (
                <li key={qid}>
                  <Button
                    type="button"
                    variant={
                      idx === currentIndex ? 'primary' : savedHere ? 'secondary' : 'ghost'
                    }
                    // The button's only text is the number, so variant and CSS
                    // class carry the answered/saving state — invisible to a
                    // screen reader. Say it in the name instead (W17/A9.4).
                    aria-label={`第 ${idx + 1} 题${
                      savingHere ? '（保存中）' : savedHere ? '（已作答）' : '（未作答）'
                    }`}
                    aria-current={idx === currentIndex ? 'true' : undefined}
                    className={savingHere ? 'is-saving' : undefined}
                    onClick={() => setCurrentIndex(idx)}
                  >
                    {idx + 1}
                  </Button>
                </li>
              );
            })}
          </ol>
        </aside>
      </div>
    </div>
  );
}

function ExamResult({
  spaceId,
  attemptId,
}: {
  spaceId: number;
  attemptId: number;
}) {
  const api = useApiClient();
  const navigate = useNavigate();
  const resultQuery = useQuery({
    queryKey: queryKeys.examResult(spaceId, attemptId),
    queryFn: () => api.getExamResult(spaceId, attemptId).then(unwrap),
  });

  if (resultQuery.isPending) return <LoadingState text="加载成绩…" />;
  if (resultQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(resultQuery.error).message}
        onRetry={() => void resultQuery.refetch()}
      />
    );
  }

  /*
   * ExamResultView is the only authority for a graded attempt: the previous
   * version invented a local shape and fell back to `attempt.score` /
   * `item.stem`, neither of which the contract returns.
   */
  const result = resultQuery.data;
  const items = result?.items ?? [];

  return (
    <div className="page">
      <PageHeader title={`考试作答 #${attemptId} — 成绩`} />
      <p className="page__note">本次考试已交卷，以下为只读成绩。</p>
      <div className="practice-result">
        <p>
          得分：{result?.score ?? '—'} / {result?.maxScore ?? '—'}
        </p>
        <p>
          答对 {result?.correctCount ?? '—'} · 答错 {result?.wrongCount ?? '—'} ·
          未作答 {result?.unansweredCount ?? '—'}
        </p>
      </div>

      {items.length > 0 && (
        <section className="workbench-section" aria-label="逐题回顾">
          <h2 className="section-title">逐题回顾</h2>
          {items.map((item, idx) => (
            <div key={idx} className="result-item">
              <p>
                <strong>第 {idx + 1} 题：</strong>{' '}
                {isPositiveId(item.questionId) ? (
                  <Link
                    className="nav-link"
                    to={`/spaces/${spaceId}/questions/${item.questionId}`}
                  >
                    题目 #{item.questionId}
                  </Link>
                ) : (
                  '题目 —'
                )}
              </p>
              <p>
                {/* The server leaves `isCorrect` null for types it does not
                    auto-grade (QuestionAnswerEvaluator returns
                    GradeResult.ungraded() for SHORT_ANSWER). Treating null as
                    答错 would invent a verdict the backend never gave. */}
                {item.isCorrect === true
                  ? '答对'
                  : item.isCorrect === false
                    ? '答错'
                    : '未评分（需人工批改）'}{' '}
                ·{' '}
                {typeof item.score === 'number' ? item.score : '—'} /{' '}
                {item.maxScore ?? 0}
              </p>
              {item.correctAnswer && (
                <p className="muted">正确答案：{item.correctAnswer}</p>
              )}
              {item.explanation && (
                <p>{item.explanation}</p>
              )}
            </div>
          ))}
        </section>
      )}

      <div className="form__actions">
        <Button type="button" variant="primary" onClick={() => navigate(`/spaces/${spaceId}/exams`)}>
          返回考试列表
        </Button>
      </div>
    </div>
  );
}
