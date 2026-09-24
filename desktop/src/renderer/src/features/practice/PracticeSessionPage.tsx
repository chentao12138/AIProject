/**
 * Active Practice Session (A5.2) + Completion (A5.3).
 *
 * /spaces/:spaceId/practice/:sessionId
 */

import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
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
import type { PracticeSubmitSummary } from '../../lib/types';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { QuestionRenderer, type QuestionPayload } from '../../components/QuestionRenderer';

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
  const spaceId = useSpaceId();
  const { sessionId: sessionIdParam } = useParams();
  const sessionId = parsePositiveIdParam(sessionIdParam);
  // Only `finishPracticeSession` returns a score summary; the session detail
  // carries none, so this cannot be derived from the cache (A5.3).
  const [submitSummary, setSubmitSummary] = useState<PracticeSubmitSummary | null>(
    null
  );

  const sessionQuery = useQuery(
    entityQuery(spaceId, sessionId, queryKeys.practiceSession(spaceId, sessionId), (id, entity) =>
      api.getPracticeSession(id, entity)
    )
  );

  const startSession = useMutation({
    mutationFn: () =>
      api
        .startPracticeSession(
          requireId(spaceId, 'space id'),
          requireId(sessionId, 'session id')
        )
        .then(unwrap),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId, sessionId),
      });
    },
  });

  const finishSession = useMutation({
    mutationFn: () =>
      api
        .finishPracticeSession(
          requireId(spaceId, 'space id'),
          requireId(sessionId, 'session id')
        )
        .then(unwrap),
    onSuccess: (summary) => {
      // The score summary exists ONLY on this response — the session detail
      // has no score fields — so it is kept as local state, and the contract
      // gap for reading it back is recorded as A5.3.
      setSubmitSummary(summary ?? null);
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId, sessionId),
      });
    },
  });

  if (spaceId === null || sessionId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
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
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const rawQuestions = (session.questions ?? []) as PracticeQuestion[];
  // The backend's only vocabulary is CREATED -> IN_PROGRESS -> SUBMITTED.
  // Matching FINISHED/COMPLETED here meant a submitted session fell through to
  // the answering UI with an empty local answer map.
  const status = (session.status ?? '').toUpperCase();

  if (status === 'CREATED') {
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

  if (status === 'SUBMITTED') {
    return (
      <PracticeResult
        spaceId={spaceId}
        sessionId={sessionId}
        questionCount={session.questionCount ?? rawQuestions.length}
        summary={submitSummary}
      />
    );
  }

  return (
    <ActiveSession
      spaceId={spaceId}
      sessionId={sessionId}
      questions={rawQuestions}
      onFinish={() => finishSession.mutate()}
      finishing={finishSession.isPending}
    />
  );
}

function ActiveSession({
  spaceId,
  sessionId,
  questions,
  onFinish,
  finishing,
}: {
  spaceId: number;
  sessionId: number;
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
        title={`Practice #${sessionId}`}
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
  questionCount,
  summary,
}: {
  spaceId: number;
  sessionId: number;
  questionCount: number;
  summary: PracticeSubmitSummary | null;
}) {
  const navigate = useNavigate();

  return (
    <div className="page">
      <PageHeader title={`Practice #${sessionId} — Result`} />
      <div className="practice-result">
        {summary ? (
          <>
            <p>
              Score: {summary.score ?? '—'} / {summary.maxScore ?? '—'}
            </p>
            <p>
              Answered: {summary.answeredCount ?? 0} /{' '}
              {summary.questionCount ?? questionCount}
            </p>
            <p>Correct: {summary.correctCount ?? 0}</p>
          </>
        ) : (
          /* A session finished in an earlier visit has no readable score:
             the contract exposes no practice-result endpoint (A5.3), and
             computing one here would copy server-side rules (ADR-031). */
          <>
            <p>Questions: {questionCount}</p>
            <p className="page__note">
              成绩汇总仅在交卷响应中返回，历史会话需要后端结果接口才能读取。
            </p>
          </>
        )}
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
