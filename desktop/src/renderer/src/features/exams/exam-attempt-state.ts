/**
 * Per-question save state for a live exam attempt (G03 / W04 / W06).
 *
 * G03 — the root cause. `ExamAttemptPage` used to derive the "保存答案"
 * button from the ONE shared `submitAnswer` mutation's `isSuccess`, so after
 * the first successful save EVERY slot looked saved and its button stayed
 * disabled: question 2 could never be answered. TanStack Query keeps a single
 * state per `useMutation` hook, therefore per-question state has to live in a
 * map keyed by `examQuestionId` — exactly what the practice page already does
 * (`PracticeSessionPage.tsx:171,205`).
 *
 * W04 — "已保存" is decided by the SERVER, not by memory: the only server
 * statement that an answer exists is the `ExamAnswerView` the answer POST
 * returns, and the field that makes it a fact is `answeredAt`. A mutation that
 * resolves WITHOUT that record (e.g. an empty 200 body) must NOT be shown as
 * saved.
 *
 * CONTRACT LIMIT (C10 / A6.2, still open after the contract regeneration)
 * `ExamAttemptView` is `{id, examId, examPaperId, status, startedAt, deadlineAt,
 * submittedAt, questions}` — there is NO `answers` field; its question rows are
 * `AttemptQuestionView{examQuestionId, questionId, sortOrder, score,
 * questionType, stem, options}` with no answer field either; and the contract
 * declares `POST /exam-attempts/{attemptId}/answers` only — no GET. The only
 * `ExamAnswerView` producer is that POST, and `ExamAnswerView` itself is
 * `{id, examAttemptId, examQuestionId, gradingStatus, answeredAt}` — no answer
 * CONTENT. So after a refresh the server cannot tell us which questions were
 * answered, nor restore what was typed: an exam attempt does NOT resume. (The
 * practice side got its read endpoint — `GET .../practice-sessions/{id}/answers`
 * — and this one is the remaining half of that request.)
 * The maps below are therefore best-effort for the CURRENT visit:
 * server-authoritative per question while the page is open, not recoverable
 * across reloads. Do not "fix" that with a localStorage cache — it would
 * silently disagree with the server, and no copy here may claim a resume works.
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
 * W04: server truth for "this slot has an answer" is the presence of
 * `answeredAt` on the returned `ExamAnswerView`. `undefined` / empty is NOT
 * saved, even if the POST resolved.
 */
export function isSavedOnServer(
  record: ExamAnswerRecord | undefined
): boolean {
  return (
    record != null &&
    typeof record.answeredAt === 'string' &&
    record.answeredAt.trim() !== ''
  );
}

export interface QuestionSaveStateInput {
  /** This very slot is in flight (never another slot's mutation). */
  saving: boolean;
  /** The last save attempt for THIS slot failed. */
  failed: boolean;
  /** The `ExamAnswerView` the server returned for THIS slot, if any. */
  serverRecord?: ExamAnswerRecord;
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
 * counts slots the SERVER confirmed (`isSavedOnServer`), so a draft that was
 * typed but never saved is not reported as answered — the progress line can
 * not lie about what is stored.
 */
export function countAnswered(
  questionIds: readonly number[],
  serverRecords: Readonly<Record<number, ExamAnswerRecord | undefined>>
): number {
  return questionIds.filter((id) => isSavedOnServer(serverRecords[id])).length;
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
