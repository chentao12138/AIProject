/**
 * Exam Attempt (A6.2 + A6.3).
 *
 * /spaces/:spaceId/exams/:examId/attempts/:attemptId
 */

import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import { normalizeApiError, unwrap } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { QuestionRenderer, type QuestionPayload } from '../questions/QuestionRenderer';

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
  const { spaceId: spaceIdParam, attemptId: attemptIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);
  const attemptId = parsePositiveIdParam(attemptIdParam);

  const attemptQuery = useQuery({
    queryKey: queryKeys.examAttempt(spaceId ?? 0, attemptId ?? 0),
    queryFn: () => {
      if (spaceId === null || attemptId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.getExamAttempt(spaceId, attemptId).then(unwrap);
    },
    enabled: spaceId !== null && attemptId !== null,
  });

  const startAttempt = useMutation({
    mutationFn: () => {
      if (spaceId === null || attemptId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.startExamAttempt(spaceId, attemptId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId ?? 0, attemptId ?? 0),
      });
    },
  });

  const submitAttempt = useMutation({
    mutationFn: () => {
      if (spaceId === null || attemptId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.submitExamAttempt(spaceId, attemptId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.examAttempt(spaceId ?? 0, attemptId ?? 0),
      });
    },
  });

  if (spaceId === null || attemptId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
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
    return <ErrorState message="资源不存在或当前不可访问。" />;
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
          {startAttempt.isPending ? 'Starting…' : 'Start Exam'}
        </Button>
      </div>
    );
  }

  if (status === 'SUBMITTED' || status === 'GRADED') {
    return (
      <ExamResult
        spaceId={spaceId}
        attemptId={attemptId}
        attempt={attempt}
      />
    );
  }

  return (
    <ActiveExam
      spaceId={spaceId}
      attemptId={attemptId}
      attempt={attempt}
      questions={rawQuestions}
      onSubmit={() => submitAttempt.mutate()}
      submitting={submitAttempt.isPending}
    />
  );
}

function ActiveExam({
  spaceId,
  attemptId,
  attempt,
  questions,
  onSubmit,
  submitting,
}: {
  spaceId: number;
  attemptId: number;
  attempt: Record<string, unknown>;
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
    return <EmptyState title="No questions in this exam." />;
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
        title={`Exam Attempt #${attempt.id}`}
        actions={
          <Button type="button" variant="primary" onClick={onSubmit} disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit Exam'}
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
            submitLabel={submitAnswer.isSuccess ? 'Saved' : 'Save Answer'}
            disabled={submitAnswer.isSuccess}
          />
          {submitError && <p className="form__error" role="alert">{submitError}</p>}
          <div className="practice-nav">
            <Button type="button" onClick={goPrev} disabled={currentIndex <= 0}>Previous</Button>
            <Button type="button" onClick={goNext} disabled={currentIndex >= total - 1}>Next</Button>
          </div>
        </div>
        <aside className="practice-sidebar">
          <h3 className="section-title">Questions</h3>
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
  attempt,
}: {
  spaceId: number;
  attemptId: number;
  attempt: Record<string, unknown>;
}) {
  const api = useApiClient();
  const resultQuery = useQuery({
    queryKey: ['exam-result', spaceId, attemptId],
    queryFn: () => {
      if (spaceId === null || attemptId === null) return Promise.reject(new Error('invalid ids'));
      return api.getExamResult(spaceId, attemptId).then(unwrap);
    },
    enabled: spaceId !== null && attemptId !== null,
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

  const result = resultQuery.data as {
    score?: number;
    maxScore?: number;
    correctCount?: number;
    wrongCount?: number;
    unansweredCount?: number;
    items?: Array<{
      stem?: string;
      questionId?: number;
      isCorrect?: boolean;
      score?: number;
      maxScore?: number;
      correctAnswer?: string;
      explanation?: string;
    }>;
  } | null;
  const score = (result?.score ?? (attempt as Record<string, unknown>).score ?? '—') as string | number;
  const maxScore = (result?.maxScore ?? '—') as string | number;
  const items = result?.items ?? [];

  const attemptIdValue = (attempt as Record<string, unknown>).id as number | undefined;
  return (
    <div className="page">
      <PageHeader title={`Exam Attempt #${String(attemptIdValue ?? '?')} — Result`} />
      <div className="practice-result">
        <p>Score: {score} / {maxScore}</p>
        <p>
          Correct: {result?.correctCount ?? '—'} · Wrong: {result?.wrongCount ?? '—'} ·
          Unanswered: {result?.unansweredCount ?? '—'}
        </p>
      </div>

      {items.length > 0 && (
        <section className="workbench-section" aria-label="Per-question review">
          <h2 className="section-title">Review</h2>
          {items.map((item, idx) => (
            <div key={idx} className="question-renderer" style={{ marginBottom: '1rem' }}>
              <p><strong>Q{idx + 1}:</strong> {item.stem ?? `Question ${item.questionId}`}</p>
              <p>
                {item.isCorrect ? 'Correct' : 'Incorrect'} ·
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
        <Button type="button" variant="primary" onClick={() => window.location.hash = `#/spaces/${spaceId}/exams`}>
          Back to Exams
        </Button>
      </div>
    </div>
  );
}

function formatDateTime(value?: string) {
  if (!value) return '—';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleString();
}
