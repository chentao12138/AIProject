/**
 * Per-question save state for an exam attempt (G03 / W04 / W06 / A6.2).
 *
 * G03 — the root cause. `ExamAttemptPage` used to derive the "保存答案"
 * button from the ONE shared `submitAnswer` mutation's `isSuccess`, so after
 * the first successful save EVERY slot looked saved and its button stayed
 * disabled: question 2 could never be answered. TanStack Query keeps a single
 * state per `useMutation` hook, therefore per-question state has to live in a
 * map keyed by `examQuestionId` — exactly what the practice page already does
 * (`PracticeSessionPage.tsx:171,205`).
 *
 * W04 — "已保存" is decided by the SERVER, not by memory. Two server reads
 * state it, and both feed the SAME map below:
 *  - `POST .../exam-attempts/{attemptId}/answers` returns the `ExamAnswerView`
 *    it wrote, and `answeredAt` is the field that makes it a fact. A mutation
 *    that resolves WITHOUT that record (e.g. an empty 200 body) must NOT be
 *    shown as saved.
 *  - `GET .../exam-attempts/{attemptId}` reports, per `AttemptQuestionView`,
 *    an `answered` flag plus the saved `answer` payload (A6.2 / W04). That is
 *    what makes a re-opened attempt resume from the server instead of starting
 *    empty: see `resumeFromAttempt`.
 *
 * A6.2 — the resume read. `AttemptQuestionView` is `{examQuestionId,
 * questionId, sortOrder, score, questionType, stem, options, answer,
 * answered}` and `answer` is the `AnswerPayloadView` the learner saved
 * (`selectedOptionKeys` / `booleanAnswer` / `textAnswer` / `orderingAnswer` /
 * `matchingAnswer`). Which slots are answered comes from `answered`, never from
 * local memory, and what each answered slot contains comes from `answer`.
 * `answered` false or absent means UNANSWERED — full stop: no draft is
 * prefilled for such a slot and it counts as 未答, even if a payload happens to
 * travel with it. Nothing here may invent a state the server did not report,
 * and no local cache may stand in for this read (it would silently disagree
 * with the server).
 */

import type {
  ExamAnswerBody,
  ExamAnswerPayload,
  ExamAnswerRecord,
  ExamQuestionSlot,
} from '../../lib/types';

// Re-exported so the page keeps importing its state vocabulary from here,
// while the shapes themselves stay derived from the client (lib/types.ts):
// a feature-local copy is exactly what renderer-conventions forbids.
export type {
  ExamAnswerBody,
  ExamAnswerPayload,
  ExamAnswerRecord,
  ExamQuestionSlot,
};

/** The four states of one slot's save button (G03 requirement). */
export type QuestionSaveStatus = 'idle' | 'saving' | 'saved' | 'error';

/**
 * What the server states about ONE slot, in the single shape the page's maps
 * hold. Each read contributes its own field, which is why all three are optional:
 *  - `answeredAt` — the stamp on the `ExamAnswerView` the answer POST returned;
 *  - `submitted` — the same POST's echo flag (C10), which says the server took
 *    this payload without necessarily stamping it;
 *  - `answered` — the attempt read's own flag (`AttemptQuestionView.answered`).
 */
export interface ServerAnswerState {
  answeredAt?: string;
  submitted?: boolean;
  answered?: boolean;
}

/**
 * W04 + A6.2 / C10: "this slot has an answer" is the SERVER's statement, in any
 * of its shapes. An explicit `false` from either flag decides on its own (so a
 * row that says `answered:false` / `submitted:false` is unanswered even next to a
 * stored stamp); without a flag, the presence of `answeredAt` is what counts.
 */
export function isSavedOnServer(
  state: ServerAnswerState | undefined
): boolean {
  if (state == null) return false;
  if (state.answered === false || state.submitted === false) return false;
  if (state.answered === true || state.submitted === true) return true;
  return (
    typeof state.answeredAt === 'string' && state.answeredAt.trim() !== ''
  );
}

/**
 * The POST's `ExamAnswerView` read as per-slot state. C10 made it echo the
 * payload it stored plus a `submitted` flag, so a 2xx that says `submitted:true`
 * is proof even without a stamp — and one that says neither is still not.
 */
export function serverStateOfRecord(
  record: ExamAnswerRecord | undefined
): ServerAnswerState | undefined {
  if (record == null) return undefined;
  return { answeredAt: record.answeredAt, submitted: record.submitted };
}

/**
 * The attempt read's row (`AttemptQuestionView`) read as per-slot state.
 * `answered` is the only proof it carries, so a row that does not say `true`
 * contributes nothing — the slot stays idle and empty.
 */
function serverStateOfAttemptQuestion(
  question: Pick<ExamQuestionSlot, 'answered'>
): ServerAnswerState | undefined {
  return question.answered === true ? { answered: true } : undefined;
}

export interface QuestionSaveStateInput {
  /** This very slot is in flight (never another slot's mutation). */
  saving: boolean;
  /** The last save attempt for THIS slot failed. */
  failed: boolean;
  /** The server's state for THIS slot, from whichever read produced it. */
  serverRecord?: ServerAnswerState;
  /** The draft was edited after that record arrived -> needs a re-save. */
  editedSinceSave: boolean;
}

/**
 * The state machine. Order matters: an in-flight save outranks everything,
 * then a failed save (so a re-save failure is never hidden behind an older
 * "已保存"), then the server-confirmed saved record — which only counts while
 * the draft still matches it.
 */
export function questionSaveStatus({
  saving,
  failed,
  serverRecord,
  editedSinceSave,
}: QuestionSaveStateInput): QuestionSaveStatus {
  if (saving) return 'saving';
  if (failed) return 'error';
  if (isSavedOnServer(serverRecord) && !editedSinceSave) return 'saved';
  return 'idle';
}

export const QUESTION_SAVE_LABELS: Record<QuestionSaveStatus, string> = {
  idle: '保存答案',
  saving: '保存中…',
  saved: '已保存',
  error: '重试保存',
};

/**
 * W06: answered/unanswered counts over the whole paper. `answered` only ever
 * counts slots the SERVER confirmed (`isSavedOnServer` — its `answered` flag on
 * entry, its `answeredAt` stamp after a save), so a draft that was typed but
 * never saved is not reported as answered. The progress line can not lie about
 * what is stored.
 */
export function countAnswered(
  questionIds: readonly number[],
  serverRecords: Readonly<Record<number, ServerAnswerState | undefined>>
): number {
  return questionIds.filter((id) => isSavedOnServer(serverRecords[id])).length;
}

/**
 * A6.2: everything a re-opened attempt needs before the learner touches
 * anything — the server's save state per slot, and the saved payload as the
 * draft that slot's form shows.
 */
export interface AttemptResumeState {
  serverRecords: Record<number, ServerAnswerState>;
  drafts: Record<number, ExamAnswerPayload>;
}

/**
 * Rebuild the attempt from the rows the server just returned
 * (`GET /api/v1/spaces/{spaceId}/exam-attempts/{attemptId}`).
 *
 * One entry per slot it reports as `answered`: `answered` becomes the save
 * state (so the slot reads 已保存 and counts as 已作答) and its `answer`
 * payload becomes the draft, in the wire shape the renderer and the grammar in
 * `components/answer-grammar.ts` already read — `selectedOptionKeys` for the
 * choice types, `booleanAnswer`, `textAnswer`, the 1-based `orderingAnswer`
 * positions, the serialized `matchingAnswer` pairs. Nothing is translated here,
 * which is why a resumed slot re-sends exactly what was stored.
 *
 * A slot whose `answered` is absent or false gets NO entry at all: it is
 * unanswered, and prefilling it from a payload the server did not count as
 * answered is the invention this feature keeps being audited for.
 */
export function resumeFromAttempt(
  questions: readonly ExamQuestionSlot[]
): AttemptResumeState {
  const serverRecords: Record<number, ServerAnswerState> = {};
  const drafts: Record<number, ExamAnswerPayload> = {};

  for (const question of questions) {
    const state = serverStateOfAttemptQuestion(question);
    if (state === undefined) continue;
    serverRecords[question.examQuestionId] = state;
    // An `answered: true` row with no payload is still answered — the form
    // stays empty for it rather than showing anything the server did not send.
    if (question.answer != null) {
      drafts[question.examQuestionId] = question.answer;
    }
  }

  return { serverRecords, drafts };
}

/**
 * Fold a later attempt read into state the page already holds. Only slots this
 * client knows NOTHING about are added: a slot it has already saved (or started
 * editing) keeps its own entry, because the read that arrives after an
 * invalidation may predate that save, and rolling a fresh 已保存 back to
 * another question's state is the cross-slot bug G03 was about.
 *
 * Returns `previous` unchanged (same object) when the read adds nothing, so the
 * page can call it from an effect on every re-render without looping.
 */
export function mergeAttemptResume(
  previous: AttemptResumeState,
  questions: readonly ExamQuestionSlot[]
): AttemptResumeState {
  const resumed = resumeFromAttempt(questions);
  const serverRecords = { ...previous.serverRecords };
  const drafts = { ...previous.drafts };
  let changed = false;

  for (const question of questions) {
    const id = question.examQuestionId;
    if (previous.serverRecords[id] !== undefined) continue;
    const state = resumed.serverRecords[id];
    if (state === undefined) continue;
    serverRecords[id] = state;
    const draft = resumed.drafts[id];
    if (draft !== undefined) drafts[id] = draft;
    changed = true;
  }

  return changed ? { serverRecords, drafts } : previous;
}

/**
 * Build the request body from a local draft, exactly per question type.
 * Exported so the type->field mapping is testable without rendering.
 */
export function buildAnswerBody(
  slot: Pick<ExamQuestionSlot, 'examQuestionId' | 'questionType'>,
  draft: ExamAnswerPayload | undefined
): ExamAnswerBody {
  const type = (slot.questionType ?? '').toUpperCase();
  const answer: ExamAnswerPayload = {};
  if (type === 'SINGLE_CHOICE' || type === 'MULTIPLE_CHOICE') {
    answer.selectedOptionKeys = draft?.selectedOptionKeys ?? [];
  } else if (type === 'TRUE_FALSE') {
    // Never fabricate `false` for an unselected answer: the server rejects a
    // missing booleanAnswer with 400, and a silent false would be stored as a
    // wrong judgement the learner never made.
    answer.booleanAnswer = draft?.booleanAnswer;
  } else if (type === 'ORDERING') {
    // `orderingAnswer` is the only field gradeOrdering reads, and it throws
    // (a 400) on a null/empty one — so the field is set only when the learner
    // committed a sequence. `QuestionRenderer` keeps 提交 disabled until that
    // array covers every option with 1-based positions, so a partial one never
    // reaches this call.
    answer.orderingAnswer = draft?.orderingAnswer;
  } else if (type === 'MATCHING') {
    // Same rule for the pair editor: `matchingAnswer` is a JSON array of
    // {left,right} maps (gradeMatching parses it, a malformed string is a 400),
    // and the editor emits it only once every left item is paired.
    answer.matchingAnswer = draft?.matchingAnswer;
  } else {
    answer.textAnswer = draft?.textAnswer ?? '';
  }
  return { examQuestionId: slot.examQuestionId, answer };
}
