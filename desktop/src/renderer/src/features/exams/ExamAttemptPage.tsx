/**
 * Exam Attempt (A6.2 + A6.3).
 *
 * /spaces/:spaceId/exams/:examId/attempts/:attemptId
 */

import { useState } from 'react';
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
import { formatDateTime } from '../../lib/format';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';

interface ExamQuestion {
  examQuestionId: number;
  questionId: number;
  questionType?: string;
  stem?: string;
  options?: Array<{ optionKey: string; content: string }>;
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

  const rawQuestions = (attempt.questions ?? []) as ExamQuestion[];
  const status = (attempt.status ?? '').toUpperCase();

  if (status === 'CREATED' || status === 'PENDING') {
    return (
      <div className="page">
        <PageHeader title={`Exam Attempt #${attempt.id}`} />
        <p className="page__note">{rawQuestions.length} questions.</p>
        {attempt.deadlineAt && (
          <p className="page__note">
            Deadline: {formatDateTime(attempt.deadlineAt)}
          </p>
        )}
        <Button
          variant="primary"
          disabled={startAttempt.isPending}
          onClick={() => startAttempt.mutate()}
        >
          {startAttempt.isPending ? '开始中…' : '开始考试'}
        </Button>
      </div>
    );
  }

  if (status === 'SUBMITTED' || status === 'GRADED') {
    return <ExamResult spaceId={spaceId} attemptId={attemptId} />;
  }

  return (
    <ActiveExam
      spaceId={spaceId}
      attemptId={attemptId}
      questions={rawQuestions}
      onSubmit={() => submitAttempt.mutate()}
      submitting={submitAttempt.isPending}
    />
  );
}

function ActiveExam({
  spaceId,
  attemptId,
  questions,
  onSubmit,
  submitting,
}: {
  spaceId: number;
  attemptId: number;
  questions: ExamQuestion[];
  onSubmit: () => void;
  submitting: boolean;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<number, { selectedOptionKeys?: string[]; booleanAnswer?: boolean; textAnswer?: string }>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);

  const current = questions[currentIndex];
  const total = questions.length;

  const submitAnswer = useMutation({
    mutationFn: (body: {
      examQuestionId: number;
      answer: {
        selectedOptionKeys?: string[];
        booleanAnswer?: boolean;
        textAnswer?: string;
      };
    }) =>
      api.submitExamAnswer(spaceId, attemptId, body).then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId, attemptId),
      });
    },
    onError: (error: unknown) => {
      setSubmitError(normalizeApiError(error).message);
    },
  });

  if (!current) {
    return <EmptyState title="本次考试没有题目。" />;
  }

  const currentAnswer = answers[current.examQuestionId];

  function handleSubmit() {
    setSubmitError(null);
    const body: {
      examQuestionId: number;
      answer: {
        selectedOptionKeys?: string[];
        booleanAnswer?: boolean;
        textAnswer?: string;
      };
    } = {
      examQuestionId: current.examQuestionId,
      answer: {},
    };
    const qType = (current.questionType ?? '').toUpperCase();
    if (qType === 'SINGLE_CHOICE' || qType === 'MULTIPLE_CHOICE') {
      body.answer.selectedOptionKeys = currentAnswer?.selectedOptionKeys ?? [];
    } else if (qType === 'BOOLEAN') {
      body.answer.booleanAnswer = currentAnswer?.booleanAnswer ?? false;
    } else {
      body.answer.textAnswer = currentAnswer?.textAnswer ?? '';
    }
    submitAnswer.mutate(body);
  }

  function goNext() {
    if (currentIndex < total - 1) setCurrentIndex(currentIndex + 1);
  }
  function goPrev() {
    if (currentIndex > 0) setCurrentIndex(currentIndex - 1);
  }

  const payload: QuestionPayload = {
    questionType: current.questionType,
    stem: current.stem ?? '',
    options: current.options,
  };

  return (
    <div className="page">
      <PageHeader
        title={`Exam Attempt #${attemptId}`}
        actions={
          <Button type="button" variant="primary" onClick={onSubmit} disabled={submitting}>
            {submitting ? '交卷中…' : '交卷'}
          </Button>
        }
      />
      <div className="practice-layout">
        <div className="practice-main">
          <div className="practice-progress">
            Question {currentIndex + 1} of {total}
          </div>
          <QuestionRenderer
            question={payload}
            mode="answer"
            selectedOptionKeys={currentAnswer?.selectedOptionKeys}
            booleanAnswer={currentAnswer?.booleanAnswer}
            textAnswer={currentAnswer?.textAnswer}
            onSelectedOptionKeysChange={(keys) =>
              setAnswers((prev) => ({
                ...prev,
                [current.examQuestionId]: { ...prev[current.examQuestionId], selectedOptionKeys: keys },
              }))
            }
            onBooleanAnswerChange={(val) =>
              setAnswers((prev) => ({
                ...prev,
                [current.examQuestionId]: { ...prev[current.examQuestionId], booleanAnswer: val },
              }))
            }
            onTextAnswerChange={(val) =>
              setAnswers((prev) => ({
                ...prev,
                [current.examQuestionId]: { ...prev[current.examQuestionId], textAnswer: val },
              }))
            }
            onSubmit={handleSubmit}
            submitLabel={submitAnswer.isSuccess ? '已保存' : '保存答案'}
            disabled={submitAnswer.isSuccess}
          />
          {submitError && <p className="form__error" role="alert">{submitError}</p>}
          <div className="practice-nav">
            <Button type="button" onClick={goPrev} disabled={currentIndex <= 0}>上一题</Button>
            <Button type="button" onClick={goNext} disabled={currentIndex >= total - 1}>下一题</Button>
          </div>
        </div>
        <aside className="practice-sidebar">
          <h3 className="section-title">题目</h3>
          <ol className="practice-question-list">
            {questions.map((q, idx) => {
              const qid = q.examQuestionId;
              return (
                <li key={qid}>
                  <Button
                    type="button"
                    variant={idx === currentIndex ? 'primary' : 'ghost'}
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
      <PageHeader title={`Exam Attempt #${attemptId} — Result`} />
      <div className="practice-result">
        <p>
          Score: {result?.score ?? '—'} / {result?.maxScore ?? '—'}
        </p>
        <p>
          Correct: {result?.correctCount ?? '—'} · Wrong: {result?.wrongCount ?? '—'} ·
          Unanswered: {result?.unansweredCount ?? '—'}
        </p>
      </div>

      {items.length > 0 && (
        <section className="workbench-section" aria-label="逐题回顾">
          <h2 className="section-title">逐题回顾</h2>
          {items.map((item, idx) => (
            <div key={idx} className="result-item">
              <p>
                <strong>Q{idx + 1}:</strong>{' '}
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
                {item.isCorrect ? '答对' : '答错'} ·
                {item.score ?? 0} / {item.maxScore ?? 0}
              </p>
              {item.correctAnswer && (
                <p className="muted">Correct answer: {item.correctAnswer}</p>
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
          Back to Exams
        </Button>
      </div>
    </div>
  );
}
