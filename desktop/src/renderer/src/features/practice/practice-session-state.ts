/**
 * Practice session state helpers (audit W01 / W02 / W03).
 *
 * WHY THIS MODULE EXISTS
 * The contract around a practice session is asymmetric, and every previous
 * bug in this feature came from papering over that gap with guesses:
 *
 *  - `GET /practice-sessions/{id}` returns `PracticeSessionDetail` = session
 *    fields + `questions` (safe view). It carries NO score and NO answers
 *    (verified against openapi.json: `PracticeSessionDetail.properties` has
 *    exactly id/spaceId/status/questionCount/startedAt/finishedAt/createdAt/
 *    updatedAt/questions).
 *  - `POST /practice-sessions/{id}/answers` returns `PracticeAnswerView`
 *    ({id, practiceSessionQuestionId, questionId, isCorrect, correctAnswer,
 *    explanation, score, answeredAt}) — the ONLY server statement about a
 *    single question, and it exists only in that response.
 *  - `POST .../finish` returns `PracticeSubmitView` (score/answeredCount/
 *    correctCount/…), again nowhere else.
 *  - There is NO read endpoint for either: `/answers` is declared `post`
 *    only and `get?: never` in the generated client, and no
 *    `/practice-sessions/{id}/result` exists (BACKEND-CONTRACT-REQUESTS A5.3,
 *    audit C06). So "server truth" for one question can only ever be the
 *    response the server already gave us.
 *
 * That is why submitted state lives in the React Query cache instead of
 * component state: the cache outlives unmount and route re-entry, so a
 * question the server already graded is not offered back as unanswered.
 * `useCacheMirror` below is the single place that mirrors response data.
 *
 * Payload rules mirror `PracticeAnswerService.validatePayloadShape` (the
 * backend answers 400 when a payload carries a field its type must not
 * carry), and `AnswerPayloadView` has no `orderingAnswer` / `matchingAnswer`
 * fields, so ORDERING / MATCHING cannot be expressed from this client at all
 * — they are reported honestly instead of being sent as a wrong-shaped body.
 */

import { useCallback, useMemo } from 'react';
import { useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { ApiRequestError, normalizeApiError } from '../../lib/api-error';
import { queryKeys } from '../../lib/query-keys';
// Aliased so the vocabulary this module already speaks stays intact while the
// shapes themselves come from the client (lib/types.ts) instead of a
// feature-local copy of a generated DTO.
import type {
  PracticeAnswerPayload as AnswerPayloadView,
  PracticeAnswerRecord as PracticeAnswerView,
  PracticeSessionDetail,
  PracticeSubmitSummary,
} from '../../lib/types';

/** One question of the paper, narrowed to what this contract actually sends. */
export interface PracticeQuestionSlot {
  practiceSessionQuestionId: number;
  questionId: number;
  questionType?: string;
  stem?: string;
  sortOrder?: number;
  options?: Array<{ optionKey: string; content: string }>;
}

/** The unsaved input for one question — same shape the API accepts. */
export type PracticeAnswerDraft = AnswerPayloadView;

/**
 * What the server reported for one question, plus the payload we sent.
 * `answer` is a client-side echo (the response carries no user answer), so
 * review copy labels it as our submission, never as server truth.
 */
export interface PracticeSubmittedAnswer {
  answer: PracticeAnswerDraft;
  view: PracticeAnswerView;
}

export interface PracticeLocalState {
  drafts: Record<number, PracticeAnswerDraft>;
  submitted: Record<number, PracticeSubmittedAnswer>;
  currentIndex: number;
}

export const EMPTY_PRACTICE_LOCAL_STATE: PracticeLocalState = {
  drafts: {},
  submitted: {},
  currentIndex: 0,
};

/** Keyed per space+session so switching sessions cannot leak state. */
export function practiceLocalStateKey(
  spaceId: number,
  sessionId: number
): QueryKey {
  return ['practice-session-state', spaceId, sessionId] as const;
}

/**
 * Backend enums arrive as bare strings (openapi types them `string`), so every
 * comparison normalizes case/whitespace first — for question types AND for
 * session statuses (`PRACTICE_SESSION_STATUSES`).
 */
export function normalizeCode(value?: string | null): string {
  return (value ?? '').trim().toUpperCase();
}

export const PRACTICE_CHOICE_TYPES = new Set(['SINGLE_CHOICE', 'MULTIPLE_CHOICE']);

/**
 * Types the backend grades but this client cannot answer: the graded payload
 * needs `orderingAnswer` / `matchingAnswer`, which `AnswerPayloadView` does
 * not declare (audit C09). Sending `textAnswer` for them is a guaranteed 400.
 */
export const PRACTICE_UNANSWERABLE_TYPES: Record<string, string> = {
  ORDERING: '排序题需要 orderingAnswer 字段，当前 AnswerPayloadView 合同未提供，无法在本客户端作答。',
  MATCHING: '配对题需要 matchingAnswer 字段，当前 AnswerPayloadView 合同未提供，无法在本客户端作答。',
};

/** Slots in the order the server publishes them (sortOrder only if complete). */
export function toPracticeSlots(
  detail?: PracticeSessionDetail | null
): PracticeQuestionSlot[] {
  const slots: PracticeQuestionSlot[] = (detail?.questions ?? [])
    .filter(
      (q) =>
        typeof q.practiceSessionQuestionId === 'number' &&
        typeof q.questionId === 'number'
    )
    .map((q) => ({
      practiceSessionQuestionId: q.practiceSessionQuestionId as number,
      questionId: q.questionId as number,
      questionType: q.questionType,
      stem: q.stem,
      sortOrder: q.sortOrder,
      // Contract types every OptionView field as optional; the renderer needs
      // a key+content pair, so a half-populated option is dropped, not faked.
      options: (q.options ?? [])
        .filter(
          (o) => typeof o.optionKey === 'string' && typeof o.content === 'string'
        )
        .map((o) => ({ optionKey: o.optionKey as string, content: o.content as string })),
    }));

  if (slots.length > 1 && slots.every((s) => typeof s.sortOrder === 'number')) {
    return [...slots].sort(
      (a, b) => (a.sortOrder as number) - (b.sortOrder as number)
    );
  }
  return slots;
}

/**
 * Turn the draft into the ONE field shape the backend accepts for this type,
 * or explain why it cannot be sent yet. Never fills in a default answer:
 * an unselected TRUE_FALSE used to be sent as `false`, silently grading the
 * user wrong.
 */
export function buildAnswerPayload(
  questionType?: string,
  draft?: PracticeAnswerDraft
): { ok: true; answer: AnswerPayloadView } | { ok: false; message: string } {
  const type = normalizeCode(questionType);
  const unanswerable = PRACTICE_UNANSWERABLE_TYPES[type];
  if (unanswerable) {
    return { ok: false, message: unanswerable };
  }

  const keys = draft?.selectedOptionKeys ?? [];
  const text = draft?.textAnswer;

  switch (type) {
    case 'SINGLE_CHOICE':
      if (keys.length === 0) {
        return { ok: false, message: '单选题请选择一个选项。' };
      }
      if (keys.length > 1) {
        return { ok: false, message: '单选题只能选择一个选项，请取消多余选项。' };
      }
      return { ok: true, answer: { selectedOptionKeys: keys } };

    case 'MULTIPLE_CHOICE':
      if (keys.length === 0) {
        return { ok: false, message: '多选题请至少选择一个选项。' };
      }
      return { ok: true, answer: { selectedOptionKeys: keys } };

    case 'TRUE_FALSE':
      if (typeof draft?.booleanAnswer !== 'boolean') {
        return { ok: false, message: '请明确选择“正确”或“错误”。' };
      }
      return { ok: true, answer: { booleanAnswer: draft.booleanAnswer } };

    case 'SHORT_ANSWER':
      // The backend only rejects a `null` textAnswer, so an empty string would
      // be stored and graded as wrong — asking for content first is kinder.
      if (text === undefined || text.trim() === '') {
        return { ok: false, message: '请输入简答内容。' };
      }
      return { ok: true, answer: { textAnswer: text } };

    case 'FILL_BLANK':
      // Backend: FILL_BLANK requires a non-blank textAnswer; several blanks are
      // one comma-delimited string (AnswerDataCodec stores referenceAnswer that
      // way), so the UI says so instead of inventing per-blank fields.
      if (text === undefined || text.trim() === '') {
        return { ok: false, message: '请填写填空内容（多个空用英文逗号分隔）。' };
      }
      return { ok: true, answer: { textAnswer: text } };

    default:
      return {
        ok: false,
        message: `暂不支持的题型「${type || '未知'}」，无法作答。`,
      };
  }
}

/** Human-readable line for the answer we sent (PracticeAnswerView has no echo). */
export function describeSubmittedAnswer(
  questionType: string | undefined,
  answer: PracticeAnswerDraft
): string {
  const type = normalizeCode(questionType);
  if (PRACTICE_CHOICE_TYPES.has(type)) {
    const keys = answer.selectedOptionKeys ?? [];
    return keys.length > 0 ? `已选 ${keys.join('、')}` : '未选择选项';
  }
  if (type === 'TRUE_FALSE') {
    if (typeof answer.booleanAnswer !== 'boolean') return '未作答';
    return answer.booleanAnswer ? '选择：正确' : '选择：错误';
  }
  const text = (answer.textAnswer ?? '').trim();
  return text === '' ? '未填写内容' : `提交内容：${text}`;
}

/**
 * The verdict the SERVER reported. `PracticeAnswerService` stores SHORT_ANSWER
 * answers ungraded, so `isCorrect` can be null — that is reported as
 * ungraded rather than turned into a frontend guess (ADR-031).
 */
export function describeServerVerdict(
  view: PracticeAnswerView
): { label: string; graded: boolean; tone: 'correct' | 'wrong' | 'ungraded' } {
  if (typeof view.isCorrect !== 'boolean') {
    return { label: '后端未返回逐题评分', graded: false, tone: 'ungraded' };
  }
  return view.isCorrect
    ? { label: '答对', graded: true, tone: 'correct' }
    : { label: '答错', graded: true, tone: 'wrong' };
}

export type PracticeMutationAction = 'start' | 'finish' | 'answer';

export interface PracticeActionError {
  /** Readable first line — always from `normalizeApiError` or one 409 copy. */
  message: string;
  /** Contract-accurate explanation of what the backend rejects. */
  detail: string;
  /** Retry is meaningless for 401/403/404 — only the fallback helps. */
  retryLabel: string | null;
  /**
   * What retry DOES. A 409 means the server's status moved (CREATED ->
   * IN_PROGRESS -> SUBMITTED), so repeating the write can only fail again —
   * retry re-reads the session instead. Everything else re-runs the call.
   */
  retryAction: 'repeat' | 'reload-session';
  fallbackLabel: string;
}

const START_409 =
  '会话已不在“待开始”状态，无法再次开始。';
const FINISH_409 =
  '会话不在作答中状态，无法交卷。';
const ANSWER_409 =
  '会话状态已变化，本题答案未提交。';

const ACTION_SUBJECT: Record<PracticeMutationAction, string> = {
  start: '开始练习',
  finish: '交卷',
  answer: '提交答案',
};

/**
 * Map a failed start/finish/answer mutation onto readable copy + the action
 * that actually helps. Status codes come from `PracticeSessionService.start`
 * (409 on any non-CREATED), `PracticeAnswerService.finish` / `.answer`
 * (409 unless IN_PROGRESS) and 400 payload-shape rejections.
 */
export function describePracticeError(
  error: unknown,
  action: PracticeMutationAction
): PracticeActionError {
  const normalized = normalizeApiError(error);
  const status =
    error instanceof ApiRequestError ? error.status : normalized.status;
  const subject = ACTION_SUBJECT[action];

  if (status === 409) {
    const message =
      action === 'start'
        ? START_409
        : action === 'finish'
          ? FINISH_409
          : ANSWER_409;
    return {
      message,
      detail: `后端只接受特定状态的${subject}请求，请以服务端最新状态为准。`,
      retryLabel: action === 'answer' ? '重新读取会话状态' : '重新读取会话',
      retryAction: 'reload-session',
      fallbackLabel: '返回练习列表',
    };
  }

  switch (normalized.kind) {
    case 'unauthorized':
      return {
        message: normalized.message,
        detail: `${subject}需要有效的登录状态，请重新登录后再试。`,
        retryLabel: null,
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
    case 'forbidden':
      return {
        message: normalized.message,
        detail: '练习会话只能由所属空间成员操作，请确认空间是否正确。',
        retryLabel: null,
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
    case 'not-found':
      return {
        message: normalized.message,
        detail: '会话可能已被删除，或不属于当前空间。',
        retryLabel: null,
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
    case 'network':
      return {
        message: normalized.message,
        detail: `${subject}请求未能送达后端，本地答案不会被当作已提交。`,
        retryLabel: '重试',
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
    case 'server':
    case 'upstream':
      return {
        message: normalized.message,
        detail: `${subject}失败（服务器状态 ${status ?? '未知'}），请稍后重试。`,
        retryLabel: '重试',
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
    default:
      // 400 payload-shape rejections already arrive as "校验未通过：…".
      return {
        message: normalized.message,
        detail: `${subject}未完成，本题仍按未提交处理。`,
        retryLabel: '重试',
        retryAction: 'repeat',
        fallbackLabel: '返回练习列表',
      };
  }
}

/**
 * Response data kept in the query cache because the contract has no read
 * endpoint for it. `enabled: false` makes React Query the store only: writes
 * go through `setQueryData`, reads come back from the cache — including after
 * this component unmounts and the route is entered again.
 */
function useCacheMirror<T>(key: QueryKey, fallback: T) {
  const queryClient = useQueryClient();
  const cached = useQuery<T>({
    queryKey: key,
    queryFn: () => fallback,
    enabled: false,
    staleTime: Infinity,
  });
  const write = useCallback(
    (update: (prev: T | undefined) => T) => {
      queryClient.setQueryData<T>(key, (prev) => update(prev));
    },
    [key, queryClient]
  );
  return { value: cached.data ?? fallback, write };
}

/** Drafts + server-reported answers + position for ONE session. */
export function usePracticeLocalState(spaceId: number, sessionId: number) {
  const key = useMemo(
    () => practiceLocalStateKey(spaceId, sessionId),
    [spaceId, sessionId]
  );
  const { value: state, write } = useCacheMirror<PracticeLocalState>(
    key,
    EMPTY_PRACTICE_LOCAL_STATE
  );

  const setDraft = useCallback(
    (slotId: number, draft: PracticeAnswerDraft) => {
      write(
        (prev) => ({
          ...EMPTY_PRACTICE_LOCAL_STATE,
          ...prev,
          drafts: {
            ...prev?.drafts,
            [slotId]: { ...prev?.drafts?.[slotId], ...draft },
          },
        })
      );
    },
    [write]
  );

  /** Called ONLY with a server `PracticeAnswerView`, never optimistically. */
  const markSubmitted = useCallback(
    (slotId: number, entry: PracticeSubmittedAnswer) => {
      write(
        (prev) => ({
          ...EMPTY_PRACTICE_LOCAL_STATE,
          ...prev,
          submitted: { ...prev?.submitted, [slotId]: entry },
        })
      );
    },
    [write]
  );

  const setCurrentIndex = useCallback(
    (index: number) => {
      write(
        (prev) => ({
          ...EMPTY_PRACTICE_LOCAL_STATE,
          ...prev,
          currentIndex: index,
        })
      );
    },
    [write]
  );

  return { state, setDraft, markSubmitted, setCurrentIndex };
}

/** `PracticeSubmitView` — returned by `finish` only (A5.3 / C06 gap). */
export function usePracticeSubmitSummary(
  spaceId: number | null,
  sessionId: number | null
) {
  const key = queryKeys.practiceSubmit(spaceId, sessionId);
  const { value, write } = useCacheMirror<PracticeSubmitSummary | null>(key, null);
  const save = useCallback(
    (summary: PracticeSubmitSummary | null) => {
      if (spaceId === null || sessionId === null) {
        // A URL without resolvable ids has nowhere to file this response.
        return;
      }
      write(() => summary);
    },
    [sessionId, spaceId, write]
  );
  return { summary: value, save };
}

/**
 * Position to resume at: the first question the server has NOT reported an
 * answer for, or -1 when every question has one.
 */
export function firstOpenSlotIndex(
  slots: PracticeQuestionSlot[],
  submitted: Record<number, PracticeSubmittedAnswer>
): number {
  return slots.findIndex((s) => !submitted[s.practiceSessionQuestionId]);
}
