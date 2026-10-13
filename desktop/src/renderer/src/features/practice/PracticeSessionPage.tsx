/**
 * Active Practice Session (A5.2) + Completion (A5.3).
 *
 * /spaces/:spaceId/practice/:sessionId
 *
 * State model (audit W01 / W02 / W03):
 *  - Session status and the question paper come from
 *    `GET /practice-sessions/{id}`; the server's status wins whenever a 409
 *    says our snapshot is stale.
 *  - "Submitted" for ONE question is the `PracticeAnswerView` the server
 *    returned for it, mirrored into the query cache by `markSubmitted`.
 *    Nothing is marked submitted optimistically, and an unsaved draft never
 *    counts as an answer.
 *  - `PracticeSubmitView` (the only score this contract has) is mirrored the
 *    same way, so leaving the page and coming back does not erase the result.
 */

import { useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApiClient } from '../../lib/api-context';
import {
  NOT_FOUND_MESSAGE,
  ApiRequestError,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { entityQuery } from '../../lib/queries';
import { useSpaceId, requireId } from '../../lib/space-scope';
import { queryKeys } from '../../lib/query-keys';
import { parsePositiveIdParam } from '../../lib/ids';
import { PRACTICE_SESSION_STATUSES } from '../../lib/format';
import type {
  PracticeAnswerPayload as AnswerPayloadView,
  PracticeSessionDetail,
  PracticeSubmitSummary,
} from '../../lib/types';
import { Button } from '../../components/Button';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PageHeader } from '../../components/PageHeader';
import { PracticeQuestionCard } from './PracticeQuestionCard';
import { PracticeReviewList } from './PracticeReviewList';
import {
  buildAnswerPayload,
  describePracticeError,
  firstOpenSlotIndex,
  normalizeCode,
  toPracticeSlots,
  usePracticeLocalState,
  usePracticeSubmitSummary,
  type PracticeActionError,
  type PracticeQuestionSlot,
} from './practice-session-state';

export function PracticeSessionPage() {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const spaceId = useSpaceId();
  const { sessionId: sessionIdParam } = useParams();
  const sessionId = parsePositiveIdParam(sessionIdParam);

  const sessionQuery = useQuery(
    entityQuery(
      spaceId,
      sessionId,
      queryKeys.practiceSession(spaceId, sessionId),
      (id, entity) => api.getPracticeSession(id, entity)
    )
  );

  // The score summary exists ONLY on the finish response, so it is mirrored
  // into the query cache — as component state it vanished on the first
  // navigation away (audit G05, reachable part).
  const { summary: submitSummary, save: saveSubmitSummary } =
    usePracticeSubmitSummary(spaceId, sessionId);

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
      saveSubmitSummary(summary ?? null);
      void queryClient.invalidateQueries({
        queryKey: queryKeys.practiceSession(spaceId, sessionId),
      });
    },
  });

  const startError = startSession.isError
    ? describePracticeError(startSession.error, 'start')
    : null;
  const finishError = finishSession.isError
    ? describePracticeError(finishSession.error, 'finish')
    : null;

  /**
   * Retry depends on WHY it failed: a 409 means the server's session status
   * moved, so re-running the same write can only fail again — retry re-reads
   * the session and the page re-renders from that truth.
   */
  function reloadSession() {
    startSession.reset();
    finishSession.reset();
    void sessionQuery.refetch();
  }

  function backToPracticeList() {
    if (spaceId === null) {
      navigate(-1);
      return;
    }
    navigate(`/spaces/${spaceId}/practice`);
  }

  if (spaceId === null || sessionId === null) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }
  if (sessionQuery.isPending) {
    return <LoadingState text="加载练习…" />;
  }
  if (sessionQuery.isError) {
    return (
      <div className="page">
        <ErrorState
          message={normalizeApiError(sessionQuery.error).message}
          detail="无法读取该练习会话，请重试或返回练习列表。"
          onRetry={() => void sessionQuery.refetch()}
        >
          <Button variant="ghost" onClick={backToPracticeList}>
            返回练习列表
          </Button>
        </ErrorState>
      </div>
    );
  }

  const session = sessionQuery.data;
  if (!session) {
    return <ErrorState message={NOT_FOUND_MESSAGE} />;
  }

  const slots = toPracticeSlots(session);
  // The backend's only vocabulary is CREATED -> IN_PROGRESS -> SUBMITTED
  // (PRACTICE_SESSION_STATUSES is pinned to PracticeSessionService.STATUS_*).
  const status = normalizeCode(session.status);

  if (status === PRACTICE_SESSION_STATUSES.CREATED) {
    return (
      <div className="page">
        <PageHeader title={`练习 #${session.id}`} />
        <p className="page__note">会话已就绪，共 {slots.length} 题。</p>
        {startError && (
          <SessionErrorBand
            error={startError}
            onRepeat={() => startSession.mutate()}
            onReload={reloadSession}
            onFallback={backToPracticeList}
          />
        )}
        <Button
          variant="primary"
          disabled={startSession.isPending}
          onClick={() => startSession.mutate()}
        >
          {startSession.isPending ? '开始中…' : '开始作答'}
        </Button>
      </div>
    );
  }

  if (status === PRACTICE_SESSION_STATUSES.SUBMITTED) {
    return (
      <PracticeResult
        spaceId={spaceId}
        sessionId={sessionId}
        session={session}
        slots={slots}
        summary={submitSummary}
        finishError={finishError}
        onRepeatFinish={() => finishSession.mutate()}
        onReload={reloadSession}
        onFallback={backToPracticeList}
      />
    );
  }

  return (
    <ActiveSession
      spaceId={spaceId}
      sessionId={sessionId}
      session={session}
      slots={slots}
      finishing={finishSession.isPending}
      finishError={finishError}
      onRepeatFinish={() => finishSession.mutate()}
      onReload={reloadSession}
      onFallback={backToPracticeList}
      onFinish={() => finishSession.mutate()}
    />
  );
}

/**
 * One band for a failed session-level action: the shared `ErrorState` renders
 * message + detail + retry, and the button label follows the retry kind — a
 * 409 re-reads the server instead of repeating a write that just failed.
 */
function SessionErrorBand({
  error,
  onRepeat,
  onReload,
  onFallback,
}: {
  error: PracticeActionError;
  onRepeat: () => void;
  onReload: () => void;
  onFallback: () => void;
}) {
  return (
    <ErrorState
      message={error.message}
      detail={error.detail}
      onRetry={error.retryAction === 'repeat' ? onRepeat : undefined}
    >
      {error.retryAction === 'reload-session' && error.retryLabel && (
        <Button variant="secondary" onClick={onReload}>
          {error.retryLabel}
        </Button>
      )}
      <Button variant="ghost" onClick={onFallback}>
        {error.fallbackLabel}
      </Button>
    </ErrorState>
  );
}

function ActiveSession({
  spaceId,
  sessionId,
  session,
  slots,
  finishing,
  finishError,
  onRepeatFinish,
  onReload,
  onFallback,
  onFinish,
}: {
  spaceId: number;
  sessionId: number;
  session: PracticeSessionDetail;
  slots: PracticeQuestionSlot[];
  finishing: boolean;
  finishError: PracticeActionError | null;
  onRepeatFinish: () => void;
  onReload: () => void;
  onFallback: () => void;
  onFinish: () => void;
}) {
  const api = useApiClient();
  const queryClient = useQueryClient();
  const { state, setDraft, markSubmitted, setCurrentIndex } =
    usePracticeLocalState(spaceId, sessionId);

  const canAnswer =
    normalizeCode(session.status) === PRACTICE_SESSION_STATUSES.IN_PROGRESS;
  const submittedCount = Object.keys(state.submitted).length;
  const currentIndex = clampIndex(state.currentIndex, slots.length);
  const current = slots[currentIndex];

  const answerMutation = useMutation({
    mutationFn: (vars: {
      slot: PracticeQuestionSlot;
      answer: AnswerPayloadView;
    }) =>
      api
        .submitPracticeAnswer(spaceId, sessionId, {
          practiceSessionQuestionId: vars.slot.practiceSessionQuestionId,
          answer: vars.answer,
        })
        .then(unwrap)
        .then((view) => {
          if (!view) {
            // No statement from the server: the question must NOT render as
            // answered, so this counts as a failed submit.
            throw new Error('practice answer response was empty');
          }
          return view;
        }),
    onSuccess: (view, vars) => {
      markSubmitted(vars.slot.practiceSessionQuestionId, {
        answer: vars.answer,
        view,
      });
    },
    onError: (error: unknown) => {
      // A 409 means the session left IN_PROGRESS somewhere we cannot see
      // (the backend only answers IN_PROGRESS sessions); server status wins.
      if (error instanceof ApiRequestError && error.status === 409) {
        void queryClient.invalidateQueries({
          queryKey: queryKeys.practiceSession(spaceId, sessionId),
        });
      }
    },
  });

  if (slots.length === 0 || !current) {
    return (
      <div className="page">
        <PageHeader title={`练习 #${sessionId}`} />
        <EmptyState title="本次练习没有题目" />
      </div>
    );
  }

  const slotId = current.practiceSessionQuestionId;
  // Per-question mutation view: pending and error belong to ONE slot, never
  // to the whole session — a shared flag is what left the next question's
  // button disabled after the first save succeeded (audit G03 / W01).
  const isPendingHere =
    answerMutation.isPending &&
    answerMutation.variables?.slot.practiceSessionQuestionId === slotId;
  const answerError =
    answerMutation.isError &&
    answerMutation.variables?.slot.practiceSessionQuestionId === slotId
      ? describePracticeError(answerMutation.error, 'answer')
      : null;
  const firstOpenIndex = firstOpenSlotIndex(slots, state.submitted);

  function goTo(index: number) {
    answerMutation.reset();
    setCurrentIndex(clampIndex(index, slots.length));
  }

  function submitCurrent() {
    const target = slots[currentIndex];
    if (!target) {
      return;
    }
    const payload = buildAnswerPayload(
      target.questionType,
      state.drafts[target.practiceSessionQuestionId]
    );
    if (!payload.ok) {
      return;
    }
    answerMutation.mutate({ slot: target, answer: payload.answer });
  }

  // Started earlier but this client holds no answers: almost always a reload,
  // because the contract has no GET for answers. Say so instead of letting a
  // blank form read as "nothing was answered".
  const resumedWithoutRecords =
    canAnswer && submittedCount === 0 && Boolean(session.startedAt);

  return (
    <div className="page">
      <PageHeader
        title={`练习 #${sessionId}`}
        actions={
          <Button
            type="button"
            variant="primary"
            onClick={onFinish}
            disabled={finishing || !canAnswer}
          >
            {finishing ? '交卷中…' : '交卷'}
          </Button>
        }
      />

      {finishError && (
        <SessionErrorBand
          error={finishError}
          onRepeat={onRepeatFinish}
          onReload={onReload}
          onFallback={onFallback}
        />
      )}

      {resumedWithoutRecords && (
        <p className="page__note" data-testid="resume-notice">
          此会话此前已开始（服务端 startedAt 已设置）。后端没有逐题答案读取端点，
          刷新后本页无法显示此前的作答；同一题重新提交会覆盖服务端答案。
        </p>
      )}

      <div className="practice-layout">
        <div className="practice-main">
          <div className="practice-progress">
            第 {currentIndex + 1} / {slots.length} 题 · 已提交 {submittedCount}{' '}
            题（以服务端响应为准）
          </div>
          <PracticeQuestionCard
            slot={current}
            position={currentIndex + 1}
            draft={state.drafts[slotId]}
            submitted={state.submitted[slotId]}
            pending={isPendingHere}
            error={answerError}
            canAnswer={canAnswer}
            onDraftChange={(draft) => setDraft(slotId, draft)}
            onSubmit={submitCurrent}
          />
          <div className="practice-nav">
            <Button
              type="button"
              onClick={() => goTo(currentIndex - 1)}
              disabled={currentIndex <= 0}
            >
              上一题
            </Button>
            <Button
              type="button"
              onClick={() => goTo(currentIndex + 1)}
              disabled={currentIndex >= slots.length - 1}
            >
              下一题
            </Button>
            {firstOpenIndex >= 0 && firstOpenIndex !== currentIndex && (
              <Button
                type="button"
                variant="ghost"
                onClick={() => goTo(firstOpenIndex)}
              >
                跳到未提交题
              </Button>
            )}
          </div>
        </div>
        <aside className="practice-sidebar">
          <h3 className="section-title">题目</h3>
          <ol className="practice-question-list">
            {slots.map((slot, idx) => {
              const submitted = Boolean(
                state.submitted[slot.practiceSessionQuestionId]
              );
              const drafted =
                !submitted && Boolean(state.drafts[slot.practiceSessionQuestionId]);
              return (
                <li key={slot.practiceSessionQuestionId}>
                  <Button
                    type="button"
                    variant={
                      idx === currentIndex
                        ? 'primary'
                        : submitted
                          ? 'secondary'
                          : 'ghost'
                    }
                    aria-label={`第 ${idx + 1} 题${submitted ? '（已提交）' : drafted ? '（有草稿）' : '（未作答）'}`}
                    aria-current={idx === currentIndex ? 'true' : undefined}
                    onClick={() => goTo(idx)}
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
  slots,
  summary,
  finishError,
  onRepeatFinish,
  onReload,
  onFallback,
}: {
  spaceId: number;
  sessionId: number;
  session: PracticeSessionDetail;
  slots: PracticeQuestionSlot[];
  summary: PracticeSubmitSummary | null;
  finishError: PracticeActionError | null;
  onRepeatFinish: () => void;
  onReload: () => void;
  onFallback: () => void;
}) {
  const navigate = useNavigate();
  // The same cache entry the answering view wrote: this page shows what the
  // server reported for each question, and nothing it did not report.
  const { state } = usePracticeLocalState(spaceId, sessionId);
  const questionCount =
    session.questionCount ?? summary?.questionCount ?? slots.length;

  return (
    <div className="page">
      <PageHeader title={`练习 #${sessionId} — 成绩`} />

      {finishError && (
        <SessionErrorBand
          error={finishError}
          onRepeat={onRepeatFinish}
          onReload={onReload}
          onFallback={onFallback}
        />
      )}

      <div className="practice-result">
        {summary ? (
          <>
            <p>
              得分：{summary.score ?? '—'} / {summary.maxScore ?? '—'}
            </p>
            <p>
              已作答：{summary.answeredCount ?? 0} /{' '}
              {summary.questionCount ?? questionCount}
            </p>
            <p>答对：{summary.correctCount ?? 0}</p>
          </>
        ) : (
          /* A session finished in an earlier visit has no readable score:
             PracticeSessionDetail carries none and the contract exposes no
             result endpoint (A5.3 / C06). Deriving one here would copy
             server-side grading (ADR-031), so the gap is stated instead. */
          <>
            <p>题目数：{questionCount}</p>
            <p className="page__note" data-testid="score-unavailable">
              成绩需后端 result 端点：
              GET /api/v1/spaces/{'{spaceId}'}/practice-sessions/{'{sessionId}'}/result
              未出现在当前 openapi 合同（缺口 C06），历史会话无分数可读，因此不显示分数。
            </p>
          </>
        )}
      </div>

      <PracticeReviewList
        spaceId={spaceId}
        slots={slots}
        submitted={state.submitted}
      />

      <div className="form__actions">
        <Button
          type="button"
          variant="primary"
          onClick={() => navigate(`/spaces/${spaceId}/practice`)}
        >
          返回练习列表
        </Button>
      </div>
    </div>
  );
}

function clampIndex(index: number, length: number): number {
  if (length <= 0) {
    return 0;
  }
  if (!Number.isFinite(index) || index < 0) {
    return 0;
  }
  return Math.min(index, length - 1);
}
