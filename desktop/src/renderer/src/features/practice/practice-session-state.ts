/**
 * Practice session state helpers (audit W01 / W02 / W03 + the A5.2 resume).
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
 *    explanation, score, answeredAt}) — the server's statement about one
 *    question.
 *  - `GET /practice-sessions/{id}/answers` returns that same view as an ARRAY:
 *    the answers the server already holds for this session. THAT is what a
 *    refreshed or re-entered session resumes from instead of an empty local
 *    cache. It does not echo the user's own answer text — only the verdict —
 *    so a resumed answer shows what the server said and says plainly that the
 *    content is not returned. The payload we sent stays a client-side echo,
 *    kept for the answers THIS device submitted.
 *  - `POST .../finish` returns `PracticeSubmitView` (score/answeredCount/
 *    correctCount/…) for the session it just closed, and
 *    `GET /practice-sessions/{id}/result` returns the same totals as
 *    `PracticeResultView{sessionId,status,questionCount,answeredCount,
 *    correctCount,score,maxScore}` (A5.3 / C06 — now delivered). So a session
 *    finished in an earlier visit, on another device, or straight after 交卷
 *    reads its score from THAT endpoint: the finish response is not a store,
 *    and the totals are never counted here (ADR-031).
 *
 * That is why submitted state lives in the React Query cache instead of
 * component state: the cache outlives unmount and route re-entry, so a
 * question the server already graded is not offered back as unanswered.
 * `useCacheMirror` below is the single place that mirrors response data, and
 * the server read is mirrored into the SAME cache entries (`mirrorServerAnswers`)
 * rather than into a parallel store.
 *
 * Payload rules mirror `PracticeAnswerService.validatePayloadShape` (the
 * backend answers 400 when a payload carries a field its type must not
 * carry). ORDERING / MATCHING have contract fields now (`orderingAnswer`,
 * `matchingAnswer`), so both are answerable from this client; their shape
 * checks are `QuestionAnswerEvaluator.gradeOrdering` (exact list equality over
 * 1-based positions, 400 on a null/empty payload) and `.gradeMatching` (set
 * equality over parsed `{left,right}` maps, 400 on a malformed string) read
 * back — a partial or malformed answer is refused here instead of being sent.
 */

import { useCallback, useEffect, useMemo } from 'react';
import { useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import type { ApiClient } from '@aistudy/api-client';
import {
  ApiRequestError,
  apiProblemCode,
  normalizeApiError,
  unwrap,
} from '../../lib/api-error';
import { useApiClient } from '../../lib/api-context';
import { queryKeys } from '../../lib/query-keys';
import {
  PRACTICE_RESULT_ERROR_CODES,
  PRACTICE_SESSION_STATUSES,
  QUESTION_FIELD_LIMITS,
  questionTypeSupport,
} from '../../lib/format';
// The pair / position grammar lives in one module, so the payload built here is
// checked by the SAME reader the editor writes with — one place decides what a
// legal `{left,right}` pair or a legal ordering position is.
import {
  readMatchingPair,
  serializeMatchingPairs,
  splitMatchingOptions,
  type MatchingPair,
} from '../../components/answer-grammar';
// Aliased so the vocabulary this module already speaks stays intact while the
// shapes themselves come from the client (lib/types.ts) instead of a
// feature-local copy of a generated DTO.
import type {
  PracticeAnswerPayload as AnswerPayloadView,
  PracticeAnswerRecord as PracticeAnswerView,
  PracticeResult,
  PracticeSessionDetail,
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
 * One row of `GET .../answers`: derived from the client method itself so the
 * resume read cannot drift from the contract (the element is the same
 * `PracticeAnswerView` the POST returns).
 */
export type PracticeServerAnswer = NonNullable<
  Awaited<ReturnType<ApiClient['listPracticeAnswers']>>['data']
>[number];

/**
 * What the server reported for one question, plus — when this device sent it —
 * the payload we sent.
 *
 * `answer` is a client-side echo (no endpoint returns the user's answer text),
 * so review copy labels it as our submission, never as server truth. It is
 * ABSENT for an answer read back from `GET .../answers`: that view carries the
 * verdict only, and inventing the previous content is exactly the bug this
 * feature has kept having.
 */
export interface PracticeSubmittedAnswer {
  answer?: PracticeAnswerDraft;
  view: PracticeAnswerView;
  /**
   * Where the entry came from. `'server'` = mirrored from `listPracticeAnswers`
   * (verdict only), anything else = the POST this client made.
   */
  source?: 'submitted' | 'server';
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
 * Copy for a type this client cannot answer, or null when it can.
 *
 * ORDERING and MATCHING used to live here as a contract refusal (audit C09:
 * `AnswerPayloadView` had no `orderingAnswer` / `matchingAnswer`). The
 * regenerated contract has both, so the two types are answerable now and this
 * reads the support matrix instead of a stale list: only a type the backend
 * does not grade at all (or one this app has never seen) is unanswerable.
 */
export function unanswerableTypeNotice(questionType?: string): string | null {
  const support = questionTypeSupport(questionType);
  if (support?.answerable) return null;
  const type = normalizeCode(questionType);
  return type
    ? `暂不支持的题型「${type}」，无法作答。`
    : '本题没有题型信息，无法作答。';
}

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
 * The paper-derived counts an answer has to cover, computed the same way the
 * editor renders it (first half of the option list = the left column), so the
 * gate and the control can never disagree about what "complete" means.
 */
export function practicePayloadContext(
  slot: Pick<PracticeQuestionSlot, 'options'>
): PracticePayloadContext {
  const options = slot.options ?? [];
  return {
    optionCount: options.length,
    leftCount: splitMatchingOptions(options).left.length,
  };
}

/**
 * What the question looks like from the outside, so a payload can be checked
 * against the paper instead of against itself. Both counts are optional: the
 * answer's own shape is still validated without them.
 */
export interface PracticePayloadContext {
  /** ORDERING has to place every option the question presents. */
  optionCount?: number;
  /** MATCHING has to pair every left-column item. */
  leftCount?: number;
}

/**
 * `QuestionAnswerEvaluator.gradeOrdering` compares the payload with the stored
 * `correctOrder` for EXACT list equality (no partial credit) and throws — a
 * 400 — on a null/empty one. `AnswerPayloadView.orderingAnswer` puts
 * `minimum: 1` on every item and `maxItems: 100` on the array, so positions are
 * 1-based and 0 is never legal. Anything that is not one permutation of the
 * paper is refused here rather than sent.
 */
function orderingProblem(
  order: readonly number[],
  optionCount?: number
): string | null {
  if (order.length === 0) {
    return '排序题还没有确定的顺序：请用「上移 / 下移」调整，或点击「确认当前顺序」。';
  }
  if (order.length > QUESTION_FIELD_LIMITS.orderingAnswer) {
    return `排序题最多 ${QUESTION_FIELD_LIMITS.orderingAnswer} 项，本题超出上限。`;
  }
  if (order.some((position) => !Number.isInteger(position) || position < 1)) {
    return '排序位置必须是从 1 开始的序号（合同的 orderingAnswer 限定 minimum: 1）。';
  }
  if (new Set(order).size !== order.length) {
    return '每个选项只能占一个位置，请检查是否有重复。';
  }
  if (typeof optionCount === 'number' && optionCount > 0) {
    if (order.length !== optionCount) {
      return `本题有 ${optionCount} 个选项，请为每一个都确定位置。`;
    }
    if (order.some((position) => position > optionCount)) {
      return `排序位置不能超过选项数量（1–${optionCount}）。`;
    }
  }
  return null;
}

/**
 * `gradeMatching` (same evaluator, line 152) parses the stored pairs and this
 * string as lists of `{left,right}` maps and compares them as SETS: the order
 * the user made the pairs in is irrelevant, but a malformed JSON string is a
 * 400, a renamed or extra key can never match, and every pair has to be there.
 */
function matchingPairsProblem(
  raw: string,
  pairs: readonly MatchingPair[],
  leftCount?: number
): string | null {
  if (raw.trim() === '') {
    return '配对题请为每个左侧项目选择一个配对项。';
  }
  if (raw.length > QUESTION_FIELD_LIMITS.matchingAnswer) {
    return `配对内容超过合同上限 ${QUESTION_FIELD_LIMITS.matchingAnswer} 字符，请缩短题干选项或减少配对。`;
  }
  if (pairs.length === 0) {
    return '配对内容无效：服务端只接受 [{"left":"…","right":"…"}] 形式的 JSON 数组。';
  }
  if (new Set(pairs.map((pair) => pair.left)).size !== pairs.length) {
    return '每个左侧项目只能配对一次。';
  }
  if (typeof leftCount === 'number' && leftCount > 0 && pairs.length !== leftCount) {
    return `本题有 ${leftCount} 个左侧项目，请全部配对后再提交。`;
  }
  return null;
}

/** Read a drafted pair string into pairs, tolerating a malformed one. */
function readMatchingPairs(raw: string): MatchingPair[] | null {
  if (raw.trim() === '') return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!Array.isArray(parsed)) return null;
  const pairs: MatchingPair[] = [];
  for (const entry of parsed) {
    const pair = readMatchingPair(entry);
    if (!pair) return null;
    pairs.push(pair);
  }
  return pairs;
}

/**
 * Turn the draft into the ONE field shape the backend accepts for this type,
 * or explain why it cannot be sent yet. Never fills in a default answer:
 * an unselected TRUE_FALSE used to be sent as `false`, silently grading the
 * user wrong.
 */
export function buildAnswerPayload(
  questionType?: string,
  draft?: PracticeAnswerDraft,
  context: PracticePayloadContext = {}
): { ok: true; answer: AnswerPayloadView } | { ok: false; message: string } {
  const type = normalizeCode(questionType);
  const unanswerable = unanswerableTypeNotice(questionType);
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

    case 'ORDERING': {
      const order = draft?.orderingAnswer ?? [];
      const problem = orderingProblem(order, context.optionCount);
      if (problem) {
        return { ok: false, message: problem };
      }
      // Only this field: a payload carrying another one is a 400.
      return { ok: true, answer: { orderingAnswer: [...order] } };
    }

    case 'MATCHING': {
      const raw = draft?.matchingAnswer ?? '';
      const pairs = readMatchingPairs(raw);
      if (pairs === null) {
        return {
          ok: false,
          message: '配对内容无效：服务端只接受 [{"left":"…","right":"…"}] 形式的 JSON 数组。',
        };
      }
      const problem = matchingPairsProblem(raw, pairs, context.leftCount);
      if (problem) {
        return { ok: false, message: problem };
      }
      // Re-serialized from the parsed pairs, so the wire string carries
      // exactly the two keys the grader parses and nothing else.
      return {
        ok: true,
        answer: { matchingAnswer: serializeMatchingPairs(pairs) },
      };
    }

    default:
      return {
        ok: false,
        message: `暂不支持的题型「${type || '未知'}」，无法作答。`,
      };
  }
}

/** Human-readable line for the answer we sent (no endpoint echoes it back). */
export function describeSubmittedAnswer(
  questionType: string | undefined,
  answer?: PracticeAnswerDraft
): string {
  const type = normalizeCode(questionType);
  if (!answer) return '本端未记录作答内容';
  if (PRACTICE_CHOICE_TYPES.has(type)) {
    const keys = answer.selectedOptionKeys ?? [];
    return keys.length > 0 ? `已选 ${keys.join('、')}` : '未选择选项';
  }
  if (type === 'TRUE_FALSE') {
    if (typeof answer.booleanAnswer !== 'boolean') return '未作答';
    return answer.booleanAnswer ? '选择：正确' : '选择：错误';
  }
  if (type === 'ORDERING') {
    const order = answer.orderingAnswer ?? [];
    return order.length > 0
      ? `提交顺序：${order.map((position) => `第${position}项`).join(' → ')}`
      : '未确定顺序';
  }
  if (type === 'MATCHING') {
    const pairs = readMatchingPairs(answer.matchingAnswer ?? '') ?? [];
    return pairs.length > 0
      ? `提交配对：${pairs.map((pair) => `${pair.left}↔${pair.right}`).join('、')}`
      : '未配对';
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
 * Response data kept in the query cache because the contract has nowhere else
 * to read it from (drafts, the per-question answer echo).
 * `enabled: false` makes React Query the store only: writes
 * go through `setQueryData`, reads come back from the cache — including after
 * this component unmounts and the route is entered again.
 *
 * Scores are NOT stored this way: `GET …/result` reads them back (A5.3), which
 * is what kept a finished session from losing its score between visits.
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

  /**
   * Mirror the answers `GET .../answers` reports into the SAME `submitted`
   * entries the POST responses wrote, so "已作答" has one source and a
   * refreshed / re-entered session resumes from server state instead of an
   * empty cache.
   *
   * Rules: only slots on this paper, never over an entry this client submitted
   * (ours carries the payload echo, the read one cannot), and nothing is
   * written when there is no new entry — the previous object is returned so the
   * cache does not churn on every render.
   */
  const mirrorServerAnswers = useCallback(
    (
      slots: readonly { practiceSessionQuestionId: number }[],
      records: readonly PracticeServerAnswer[]
    ) => {
      write((prev) => {
        const base: PracticeLocalState = {
          ...EMPTY_PRACTICE_LOCAL_STATE,
          ...prev,
        };
        const onPaper = new Set(slots.map((slot) => slot.practiceSessionQuestionId));
        const submitted = { ...base.submitted };
        let changed = false;
        for (const record of records) {
          const slotId = record.practiceSessionQuestionId;
          if (typeof slotId !== 'number' || !onPaper.has(slotId)) continue;
          if (submitted[slotId]) continue;
          submitted[slotId] = { view: record, source: 'server' };
          changed = true;
        }
        if (!changed) return prev ?? EMPTY_PRACTICE_LOCAL_STATE;
        return { ...base, submitted };
      });
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

  return { state, setDraft, markSubmitted, mirrorServerAnswers, setCurrentIndex };
}

/**
 * `GET /practice-sessions/{sessionId}/answers` — the answers the server already
 * holds for a session. This is the read that makes a refreshed session resume
 * truthfully (audit W01 / W02 / A5.2); before it existed the only server
 * statement about a question was the POST response, so a reload looked empty.
 *
 * An absent body is read as "nothing on record yet", not as an error: an empty
 * list is a legitimate answer set, and React Query treats `undefined` data as a
 * failed query.
 */
export function usePracticeServerAnswers(spaceId: number, sessionId: number) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.practiceAnswers(spaceId, sessionId),
    queryFn: () =>
      api
        .listPracticeAnswers(spaceId, sessionId)
        .then(unwrap)
        .then((rows) => rows ?? []),
  });
}

/**
 * The session's answer state: this client's drafts/POSTs from the cache, plus
 * the server's read answers mirrored into the same entries.
 *
 * Both the answering view and the result view call this, so re-entering a
 * session in either state shows the questions the server has on record — with
 * its verdict only, never an invented copy of what the user answered.
 */
export function usePracticeResumeState(
  spaceId: number,
  sessionId: number,
  slots: readonly PracticeQuestionSlot[]
) {
  const { state, setDraft, markSubmitted, mirrorServerAnswers, setCurrentIndex } =
    usePracticeLocalState(spaceId, sessionId);
  const answersQuery = usePracticeServerAnswers(spaceId, sessionId);
  const records = answersQuery.data;

  useEffect(() => {
    if (!records || records.length === 0) return;
    const onPaper = new Set(
      slots.map((slot) => slot.practiceSessionQuestionId)
    );
    // Only rows the cache does not already carry: a slot this client submitted
    // keeps its payload echo, which the read cannot provide.
    const missing = records.some(
      (record) =>
        typeof record.practiceSessionQuestionId === 'number' &&
        onPaper.has(record.practiceSessionQuestionId) &&
        !state.submitted[record.practiceSessionQuestionId]
    );
    if (!missing) return;
    mirrorServerAnswers(slots, records);
  }, [records, slots, state.submitted, mirrorServerAnswers]);

  return { state, setDraft, markSubmitted, setCurrentIndex, answersQuery };
}

/**
 * `GET /practice-sessions/{sessionId}/result` — the session's score totals,
 * readable at any time (A5.3, which closed gap C06).
 *
 * This is the ONLY score source the finished view uses: the `finish` response
 * is not mirrored anywhere, so a session reopened from history, refreshed, or
 * opened on another device shows exactly what the server says — never a number
 * this client remembered or computed (ADR-031).
 *
 * An absent body is read as `null` ("the endpoint returned no result"), which
 * the view reports as such: React Query treats `undefined` as a failed query,
 * and a missing payload is a fact about the response, not a transport error.
 */
export function usePracticeResult(spaceId: number, sessionId: number) {
  const api = useApiClient();
  return useQuery({
    queryKey: queryKeys.practiceResult(spaceId, sessionId),
    queryFn: () =>
      api
        .getPracticeResult(spaceId, sessionId)
        .then(unwrap)
        .then((result) => result ?? null),
  });
}

/**
 * The totals `PracticeResultView` carries. Every one of them is optional in the
 * contract (`components['schemas']['PracticeResultView']` declares no required
 * property), so a missing number stays `null` — rendering it as `0` would be a
 * grade the backend never gave.
 */
export interface PracticeResultCounts {
  score: number | null;
  maxScore: number | null;
  answeredCount: number | null;
  correctCount: number | null;
  questionCount: number | null;
}

/**
 * Which server state this result statement describes, so the view can say it
 * instead of guessing. The session vocabulary is exactly the three constants in
 * `PracticeSessionService` (server/src/main/java/com/aistudy/server/practice/
 * service/PracticeSessionService.java:58-60 — CREATED / IN_PROGRESS / SUBMITTED),
 * and `lib/backend-vocabulary-contract.test.ts` pins that list to
 * `PRACTICE_SESSION_STATUSES`. There is therefore NO "not yet graded" session
 * status to render: what a finished session CAN lack is the score itself
 * (`score` absent from the payload), which is its own state below.
 */
export type PracticeResultState =
  /** The read succeeded with no result body — nothing to show, not zero. */
  | { kind: 'no-payload' }
  /** Status is outside the three the backend can emit: no interpretation. */
  | { kind: 'unknown-status'; status: string; counts: PracticeResultCounts }
  /** The server says the session is still CREATED / IN_PROGRESS. */
  | { kind: 'not-finished'; status: string; counts: PracticeResultCounts }
  /** SUBMITTED, but the payload carries no score. */
  | { kind: 'unscored'; counts: PracticeResultCounts }
  /** SUBMITTED with a numeric score — the only state that shows one. */
  | { kind: 'graded'; counts: PracticeResultCounts & { score: number } };

/**
 * Anything that is not a number — absent, or a wire `null` the generated types
 * do not model (every `PracticeResultView` field is optional `int32`) — becomes
 * `null`, which the view renders as missing instead of as 0.
 */
function optionalCount(value?: number | null): number | null {
  return typeof value === 'number' ? value : null;
}

/**
 * Read `PracticeResultView` without inventing anything it did not say.
 * Pure, so the state split is testable apart from the page.
 */
export function practiceResultState(
  result?: PracticeResult | null
): PracticeResultState {
  if (!result) {
    return { kind: 'no-payload' };
  }
  const counts: PracticeResultCounts = {
    score: optionalCount(result.score),
    maxScore: optionalCount(result.maxScore),
    answeredCount: optionalCount(result.answeredCount),
    correctCount: optionalCount(result.correctCount),
    questionCount: optionalCount(result.questionCount),
  };
  // Backend enums arrive as bare strings, so the comparison normalizes first.
  const status = normalizeCode(result.status);

  if (
    status === PRACTICE_SESSION_STATUSES.CREATED ||
    status === PRACTICE_SESSION_STATUSES.IN_PROGRESS
  ) {
    return { kind: 'not-finished', status, counts };
  }
  if (status !== PRACTICE_SESSION_STATUSES.SUBMITTED) {
    return { kind: 'unknown-status', status, counts };
  }
  if (typeof counts.score === 'number') {
    return { kind: 'graded', counts: { ...counts, score: counts.score } };
  }
  return { kind: 'unscored', counts };
}

/**
 * Copy for a failed result read, when the server said WHY in its `code`.
 *
 * The backend answers `GET .../result` with 409 + one of two codes, and the two
 * mean different things to the learner: one says the session is still open, the
 * other says a score was never stored for it (it finished before the columns
 * existed). Both must not read as "0 分", and neither may fall back to the
 * English `detail` — that text is not a contract (C12).
 *
 * Returns null for anything else, so the view keeps its generic read-failure
 * copy instead of guessing at an unknown code.
 */
export function practiceResultErrorNotice(error: unknown): string | null {
  const code = apiProblemCode(error);
  if (code === PRACTICE_RESULT_ERROR_CODES.NOT_SUBMITTED) {
    return '服务端说这次练习还没有交卷，因此没有成绩可读。';
  }
  if (code === PRACTICE_RESULT_ERROR_CODES.NOT_RECORDED) {
    return '这次练习结束时后端还没有记录分数的字段，服务端没有留存成绩——这不代表 0 分，只是无分可读。';
  }
  return null;
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
