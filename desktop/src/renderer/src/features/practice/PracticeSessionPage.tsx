/**
 * Active Practice Session (A5.2) + Completion (A5.3).
 *
 * /spaces/:spaceId/practice/:sessionId
 */

import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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

interface PracticeQuestion {
  practiceSessionQuestionId: number;
  questionId: number;
  questionType?: string;
  stem?: string;
  options?: Array<{ optionKey: string; content: string }>;
}

export function PracticeSessionPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { spaceId: spaceIdParam, sessionId: sessionIdParam } = useParams();
  const spaceId = parsePositiveIdParam(spaceIdParam);
  const sessionId = parsePositiveIdParam(sessionIdParam);

  const sessionQuery = useQuery({
    queryKey: queryKeys.practiceSession(spaceId ?? 0, sessionId ?? 0),
    queryFn: () => {
      if (spaceId === null || sessionId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.getPracticeSession(spaceId, sessionId).then(unwrap);
    },
    enabled: spaceId !== null && sessionId !== null,
  });

  const startSession = useMutation({
    mutationFn: () => {
      if (spaceId === null || sessionId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.startPracticeSession(spaceId, sessionId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId ?? 0, sessionId ?? 0),
      });
    },
  });

  const finishSession = useMutation({
    mutationFn: () => {
      if (spaceId === null || sessionId === null) {
        return Promise.reject(new Error('invalid ids'));
      }
      return api.finishPracticeSession(spaceId, sessionId).then(unwrap);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId ?? 0, sessionId ?? 0),
      });
    },
  });

  if (spaceId === null || sessionId === null) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }
  if (sessionQuery.isPending) {
    return <LoadingState text="加载练习…" />;
  }
  if (sessionQuery.isError) {
    return (
      <ErrorState
        message={normalizeApiError(sessionQuery.error).message}
        onRetry={() => void sessionQuery.refetch()}
      />
    );
  }

  const session = sessionQuery.data;
  if (!session) {
    return <ErrorState message="资源不存在或当前不可访问。" />;
  }

  const rawQuestions = (session.questions ?? []) as PracticeQuestion[];
  const status = (session.status ?? '').toUpperCase();

  if (status === 'CREATED' || status === 'PENDING') {
    return (
      <div className="page">
        <PageHeader title={`Practice #${session.id}`} />
        <p className="page__note">Session ready. {rawQuestions.length} questions.</p>
        <Button
          variant="primary"
          disabled={startSession.isPending}
          onClick={() => startSession.mutate()}
        >
          {startSession.isPending ? 'Starting…' : 'Start'}
        </Button>
      </div>
    );
  }

  if (status === 'FINISHED' || status === 'COMPLETED') {
    return <PracticeResult spaceId={spaceId} sessionId={sessionId} session={session} />;
  }

  return (
    <ActiveSession
      spaceId={spaceId}
      sessionId={sessionId}
      session={session}
      questions={rawQuestions}
      onFinish={() => finishSession.mutate()}
      finishing={finishSession.isPending}
    />
  );
}

function ActiveSession({
  spaceId,
  sessionId,
  session,
  questions,
  onFinish,
  finishing,
}: {
  spaceId: number;
  sessionId: number;
  session: Record<string, unknown>;
  questions: PracticeQuestion[];
  onFinish: () => void;
  finishing: boolean;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const [currentIndex, setCurrentIndex] = useState(0);
  const [answers, setAnswers] = useState<Record<number, { selectedOptionKeys?: string[]; booleanAnswer?: boolean; textAnswer?: string }>>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submittedIds, setSubmittedIds] = useState<Set<number>>(new Set());

  const answeredIds = useMemo(() => new Set(Object.keys(answers).map(Number)), [answers]);

  const current = questions[currentIndex];
  const total = questions.length;
  const answeredCount = answeredIds.size;

  const submitAnswer = useMutation({
    mutationFn: (body: {
      practiceSessionQuestionId: number;
      answer: {
        selectedOptionKeys?: string[];
        booleanAnswer?: boolean;
        textAnswer?: string;
      };
    }) =>
      api.submitPracticeAnswer(spaceId, sessionId, body).then(unwrap),
    onSuccess: (_data, vars) => {
      setSubmittedIds((prev) => new Set(prev).add(vars.practiceSessionQuestionId));
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId, sessionId),
      });
    },
    onError: (error: unknown) => {
      setSubmitError(normalizeApiError(error).message);
    },
  });

  if (!current) {
    return <EmptyState title="No questions in this session." />;
  }

  const currentAnswer = answers[current.practiceSessionQuestionId];
  const isCurrentSubmitted = submittedIds.has(current.practiceSessionQuestionId);

  function handleSubmit() {
    setSubmitError(null);
    const body: {
      practiceSessionQuestionId: number;
      answer: {
        selectedOptionKeys?: string[];
        booleanAnswer?: boolean;
        textAnswer?: string;
      };
    } = {
      practiceSessionQuestionId: current.practiceSessionQuestionId,
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
    if (currentIndex < total - 1) {
      setCurrentIndex(currentIndex + 1);
    }
  }

  function goPrev() {
    if (currentIndex > 0) {
      setCurrentIndex(currentIndex - 1);
    }
  }

  const payload: QuestionPayload = {
    questionType: current.questionType,
    stem: current.stem ?? '',
    options: current.options,
  };

  return (
    <div className="page">
      <PageHeader
        title={`Practice #${(session as Record<string, unknown>).id ?? '?'}`}
        actions={
          <Button type="button" variant="primary" onClick={onFinish} disabled={finishing}>
            {finishing ? 'Finishing…' : 'Finish'}
          </Button>
        }
      />
      <div className="practice-layout">
        <div className="practice-main">
          <div className="practice-progress">
            Question {currentIndex + 1} of {total} · {answeredCount} answered
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
                [current.practiceSessionQuestionId]: { ...prev[current.practiceSessionQuestionId], selectedOptionKeys: keys },
              }))
            }
            onBooleanAnswerChange={(val) =>
              setAnswers((prev) => ({
                ...prev,
                [current.practiceSessionQuestionId]: { ...prev[current.practiceSessionQuestionId], booleanAnswer: val },
              }))
            }
            onTextAnswerChange={(val) =>
              setAnswers((prev) => ({
                ...prev,
                [current.practiceSessionQuestionId]: { ...prev[current.practiceSessionQuestionId], textAnswer: val },
              }))
            }
            onSubmit={handleSubmit}
            submitLabel={isCurrentSubmitted ? 'Submitted' : 'Submit'}
            disabled={isCurrentSubmitted || submitAnswer.isPending}
          />
          {submitError && <p className="form__error" role="alert">{submitError}</p>}
          <div className="practice-nav">
            <Button type="button" onClick={goPrev} disabled={currentIndex <= 0}>
              Previous
            </Button>
            <Button type="button" onClick={goNext} disabled={currentIndex >= total - 1}>
              Next
            </Button>
          </div>
        </div>
        <aside className="practice-sidebar">
          <h3 className="section-title">Questions</h3>
          <ol className="practice-question-list">
            {questions.map((q, idx) => {
              const qid = q.practiceSessionQuestionId;
              const answered = answeredIds.has(qid);
              const currentQ = idx === currentIndex;
              return (
                <li key={qid}>
                  <Button
                    type="button"
                    variant={currentQ ? 'primary' : answered ? 'secondary' : 'ghost'}
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

function PracticeResult({
  spaceId,
  sessionId,
  session,
}: {
  spaceId: number;
  sessionId: number;
  session: Record<string, unknown>;
}) {
  const submitView = session as {
    id: number;
    score?: number;
    maxScore?: number;
    answeredCount?: number;
    correctCount?: number;
    questionCount?: number;
  };
  const score = submitView.score;
  const maxScore = submitView.maxScore;
  const answeredCount = submitView.answeredCount ?? 0;
  const correctCount = submitView.correctCount ?? 0;
  const navigate = useNavigate();

  return (
    <div className="page">
      <PageHeader title={`Practice #${sessionId} — Result`} />
      <div className="practice-result">
        <p>
          Score: {score ?? '—'} / {maxScore ?? '—'}
        </p>
        <p>
          Answered: {answeredCount} / {submitView.questionCount ?? '—'}
        </p>
        <p>
          Correct: {correctCount}
        </p>
      </div>
      <div className="form__actions">
        <Button
          type="button"
          variant="primary"
          onClick={() => navigate(`/spaces/${spaceId}/practice`)}
        >
          New Practice
        </Button>
      </div>
    </div>
  );
}
