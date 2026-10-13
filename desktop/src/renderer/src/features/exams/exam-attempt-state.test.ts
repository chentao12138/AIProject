/**
 * Per-question exam save state (G03 / W04 / W06 / A6.2) — the pure half of the fix.
 *
 * The `questionSaveStatus` cases below are the G03 gate in miniature: a save
 * that happened for slot A must not be reported for slot B. The `resumeFromAttempt`
 * cases are A6.2's gate: what a re-opened attempt shows is decided by the
 * attempt read's own `answered` flag and `answer` payload, never by memory.
 */

import { describe, expect, it } from 'vitest';
import {
  buildAnswerBody,
  countAnswered,
  isSavedOnServer,
  mergeAttemptResume,
  questionSaveStatus,
  resumeFromAttempt,
  serverStateOfRecord,
  QUESTION_SAVE_LABELS,
  type ExamAnswerPayload,
  type ExamAnswerRecord,
  type ExamQuestionSlot,
} from './exam-attempt-state';

const savedRecord: ExamAnswerRecord = {
  id: 900,
  examAttemptId: 5,
  examQuestionId: 11,
  gradingStatus: 'GRADED',
  answeredAt: '2026-10-13T10:00:00',
};

/** A paper slot exactly as `GET .../exam-attempts/{attemptId}` returns it. */
function slot(examQuestionId: number, questionType: string): ExamQuestionSlot {
  return {
    examQuestionId,
    questionId: 100 + examQuestionId,
    sortOrder: examQuestionId,
    score: 5,
    questionType,
    stem: `第 ${examQuestionId} 题题干`,
    options: [],
  };
}

/** The same row once the server has an answer for it (A6.2's `answered`). */
function answeredSlot(
  examQuestionId: number,
  questionType: string,
  answer: ExamAnswerPayload
): ExamQuestionSlot {
  return { ...slot(examQuestionId, questionType), answered: true, answer };
}

describe('isSavedOnServer (W04 + A6.2: server truth, not memory)', () => {
  it('counts a record only when the server sent answeredAt', () => {
    expect(isSavedOnServer(savedRecord)).toBe(true);
  });

  it('rejects a successful POST that carried no answer record', () => {
    // `unwrap` yields `undefined` for an empty 200 body, and the contract's
    // ExamAnswerView marks every field optional — neither proves storage.
    expect(isSavedOnServer(undefined)).toBe(false);
    const recordWithoutStamp: ExamAnswerRecord = {
      id: 1,
      examQuestionId: 11,
    };
    expect(isSavedOnServer(recordWithoutStamp)).toBe(false);
    expect(isSavedOnServer({ answeredAt: '   ' })).toBe(false);
  });

  it("accepts the attempt read's `answered` flag as the server's word", () => {
    expect(isSavedOnServer({ answered: true })).toBe(true);
    // A6.2: the flag decides on its own. `answered: false` is unanswered even
    // beside a stamp — the server said this slot has no answer, and that is
    // the end of the discussion.
    expect(isSavedOnServer({ answered: false })).toBe(false);
    expect(isSavedOnServer({ answered: false, answeredAt: '2026-10-13T10:00:00' })).toBe(
      false
    );
    // Neither field at all (a row from a contract that predates the read) is
    // not a statement about the slot.
    expect(isSavedOnServer({})).toBe(false);
  });

  // C10 made the answer POST echo a `submitted` flag next to its payload.
  it("accepts the POST's `submitted` echo as proof, and its `false` as refusal", () => {
    expect(isSavedOnServer({ submitted: true })).toBe(true);
    // Explicitly not stored outranks a stamp that may have arrived with it.
    expect(isSavedOnServer({ submitted: false, answeredAt: '2026-10-13T10:00:00' })).toBe(
      false
    );
    // A 2xx that says nothing at all is still not proof of a stored answer.
    expect(isSavedOnServer({ answeredAt: undefined, submitted: undefined })).toBe(
      false
    );
  });

  it('carries the submitted flag out of the POST response', () => {
    const echoed: ExamAnswerRecord = {
      id: 901,
      examAttemptId: 5,
      examQuestionId: 12,
      gradingStatus: 'UNGRADED',
      submitted: true,
      answer: { selectedOptionKeys: ['A'] },
    };
    expect(serverStateOfRecord(echoed)).toEqual({
      answeredAt: undefined,
      submitted: true,
    });
    expect(isSavedOnServer(serverStateOfRecord(echoed))).toBe(true);
  });
});
describe('resumeFromAttempt (A6.2: rebuild the paper from the server read)', () => {
  it('restores each question type from its own answer field', () => {
    const matchingWire = JSON.stringify([
      { left: 'A', right: 'C' },
      { left: 'B', right: 'D' },
    ]);
    const questions: ExamQuestionSlot[] = [
      answeredSlot(11, 'SINGLE_CHOICE', { selectedOptionKeys: ['B'] }),
      answeredSlot(12, 'MULTIPLE_CHOICE', { selectedOptionKeys: ['A', 'C'] }),
      answeredSlot(13, 'TRUE_FALSE', { booleanAnswer: false }),
      answeredSlot(14, 'SHORT_ANSWER', { textAnswer: '因为引擎不同。' }),
      // 1-based option positions, straight off the wire (`minimum: 1`).
      answeredSlot(15, 'ORDERING', { orderingAnswer: [3, 1, 2] }),
      // the serialized {left,right} array `gradeMatching` parses.
      answeredSlot(16, 'MATCHING', { matchingAnswer: matchingWire }),
    ];

    const resumed = resumeFromAttempt(questions);

    // Every slot is answered per the server, so every slot is 已保存 / 已作答.
    expect(
      Object.keys(resumed.serverRecords)
        .map(Number)
        .sort((a, b) => a - b)
    ).toEqual([11, 12, 13, 14, 15, 16]);
    expect(
      countAnswered(
        questions.map((question) => question.examQuestionId),
        resumed.serverRecords
      )
    ).toBe(6);
    // The draft is the stored payload verbatim — the wire shapes the renderer
    // and components/answer-grammar.ts already read — so a resumed slot re-sends
    // exactly what is stored, with nothing re-derived here.
    expect(resumed.drafts[11]).toEqual({ selectedOptionKeys: ['B'] });
    expect(resumed.drafts[12]).toEqual({ selectedOptionKeys: ['A', 'C'] });
    expect(resumed.drafts[13]).toEqual({ booleanAnswer: false });
    expect(resumed.drafts[14]).toEqual({ textAnswer: '因为引擎不同。' });
    expect(resumed.drafts[15]).toEqual({ orderingAnswer: [3, 1, 2] });
    expect(resumed.drafts[16]).toEqual({ matchingAnswer: matchingWire });
    // A resumed slot needs no re-save: the label is 已保存, not 保存答案.
    expect(
      QUESTION_SAVE_LABELS[
        questionSaveStatus({
          saving: false,
          failed: false,
          serverRecord: resumed.serverRecords[15],
          editedSinceSave: false,
        })
      ]
    ).toBe('已保存');
    // And saving that resumed draft sends the stored positions back unchanged.
    expect(buildAnswerBody(slot(15, 'ORDERING'), resumed.drafts[15])).toEqual({
      examQuestionId: 15,
      answer: { orderingAnswer: [3, 1, 2] },
    });
  });

  it('leaves a slot the server does not report as answered unanswered', () => {
    const questions: ExamQuestionSlot[] = [
      // A fresh row: neither flag nor payload.
      slot(11, 'SINGLE_CHOICE'),
      // `answered: false` is unanswered even when a payload travelled with it —
      // nothing here may promote content the server does not count.
      { ...slot(12, 'SHORT_ANSWER'), answered: false, answer: { textAnswer: '草稿' } },
      { ...slot(13, 'TRUE_FALSE'), answered: false },
    ];

    const resumed = resumeFromAttempt(questions);

    expect(resumed.serverRecords).toEqual({});
    expect(resumed.drafts).toEqual({});
    expect(countAnswered([11, 12, 13], resumed.serverRecords)).toBe(0);
  });

  it('counts an answered slot with no payload as answered, and keeps it empty', () => {
    // The server says a row exists but sends no content for it: honest reading
    // is 已作答 with an empty form, not a fabricated draft and not 未答.
    const resumed = resumeFromAttempt([
      { ...slot(11, 'SINGLE_CHOICE'), answered: true },
    ]);

    expect(isSavedOnServer(resumed.serverRecords[11])).toBe(true);
    expect(resumed.drafts[11]).toBeUndefined();
  });
});

describe('mergeAttemptResume (a later read never rolls back this client)', () => {
  it('adds only slots the page does not know yet', () => {
    const previous = {
      serverRecords: { 11: { answeredAt: '2026-10-13T10:00:00' } },
      drafts: { 11: { selectedOptionKeys: ['A'] } },
    };
    // Slot 11 also arrives as an `answered` row with a DIFFERENT payload (a read
    // that predates this client's newer save): the local entry must survive.
    const questions: ExamQuestionSlot[] = [
      answeredSlot(11, 'SINGLE_CHOICE', { selectedOptionKeys: ['B'] }),
      answeredSlot(12, 'SINGLE_CHOICE', { selectedOptionKeys: ['C'] }),
    ];

    const merged = mergeAttemptResume(previous, questions);

    expect(merged.serverRecords[11]).toEqual({ answeredAt: '2026-10-13T10:00:00' });
    expect(merged.drafts[11]).toEqual({ selectedOptionKeys: ['A'] });
    expect(merged.serverRecords[12]).toEqual({ answered: true });
    expect(merged.drafts[12]).toEqual({ selectedOptionKeys: ['C'] });
  });

  it('returns the same object when the read adds nothing new', () => {
    const previous = {
      serverRecords: { 11: { answered: true } },
      drafts: { 11: { selectedOptionKeys: ['A'] } },
    };
    const questions: ExamQuestionSlot[] = [
      answeredSlot(11, 'SINGLE_CHOICE', { selectedOptionKeys: ['A'] }),
      slot(12, 'SINGLE_CHOICE'),
    ];

    expect(mergeAttemptResume(previous, questions)).toBe(previous);
  });
});

describe('questionSaveStatus (G03: state per examQuestionId)', () => {
  it('is idle with nothing saved', () => {
    expect(
      questionSaveStatus({ saving: false, failed: false, editedSinceSave: false })
    ).toBe('idle');
  });

  it('is saved ONLY from the server record, never from a shared flag', () => {
    expect(
      questionSaveStatus({
        saving: false,
        failed: false,
        serverRecord: savedRecord,
        editedSinceSave: false,
      })
    ).toBe('saved');
  });

  it('G03: another slot being saved leaves THIS slot idle', () => {
    // The pre-fix page fed `submitAnswer.isSuccess` (one flag for the whole
    // paper) into every slot, so slot 12 inherited slot 11's "saved".
    const slot11 = questionSaveStatus({
      saving: false,
      failed: false,
      serverRecord: savedRecord,
      editedSinceSave: false,
    });
    const slot12 = questionSaveStatus({
      saving: false,
      failed: false,
      serverRecord: undefined, // nothing stored for THIS examQuestionId
      editedSinceSave: false,
    });

    expect(slot11).toBe('saved');
    expect(slot12).toBe('idle');
    expect(QUESTION_SAVE_LABELS[slot12]).toBe('保存答案');
  });

  it('reports saving only for the slot actually in flight', () => {
    expect(
      questionSaveStatus({ saving: true, failed: false, editedSinceSave: false })
    ).toBe('saving');
  });

  it('a failed save outranks an older saved record (re-save failure)', () => {
    expect(
      questionSaveStatus({
        saving: false,
        failed: true,
        serverRecord: savedRecord,
        editedSinceSave: true,
      })
    ).toBe('error');
    expect(QUESTION_SAVE_LABELS.error).toBe('重试保存');
  });

  it('editing after a save drops the slot back to idle so it can be corrected', () => {
    expect(
      questionSaveStatus({
        saving: false,
        failed: false,
        serverRecord: savedRecord,
        editedSinceSave: true,
      })
    ).toBe('idle');
  });

  it('in flight beats a stored failure (retry is running)', () => {
    expect(
      questionSaveStatus({ saving: true, failed: true, editedSinceSave: false })
    ).toBe('saving');
  });
});

describe('countAnswered (W06: progress from server-confirmed slots)', () => {
  const records = { 11: savedRecord, 12: undefined, 13: { answeredAt: undefined } };

  it('counts saved slots and ignores drafts and answer-less records', () => {
    expect(countAnswered([11, 12, 13], records)).toBe(1);
  });

  it('is zero for a fresh paper', () => {
    expect(countAnswered([11, 12], {})).toBe(0);
  });
});

describe('buildAnswerBody (contract ExamAnswerRequest)', () => {
  it('sends selectedOptionKeys for choice types', () => {
    expect(
      buildAnswerBody({ examQuestionId: 11, questionType: 'MULTIPLE_CHOICE' }, {
        selectedOptionKeys: ['A', 'C'],
      })
    ).toEqual({
      examQuestionId: 11,
      answer: { selectedOptionKeys: ['A', 'C'] },
    });
  });

  it('sends booleanAnswer for TRUE_FALSE', () => {
    expect(
      buildAnswerBody({ examQuestionId: 12, questionType: 'TRUE_FALSE' }, {
        booleanAnswer: true,
      })
    ).toEqual({ examQuestionId: 12, answer: { booleanAnswer: true } });
  });

  it('sends textAnswer for the text types', () => {
    expect(
      buildAnswerBody({ examQuestionId: 13, questionType: 'SHORT_ANSWER' }, {
        textAnswer: '因为引擎不同。',
      })
    ).toEqual({ examQuestionId: 13, answer: { textAnswer: '因为引擎不同。' } });
  });

  it('carries only the one field its type uses (no cross-type leakage)', () => {
    const body = buildAnswerBody(
      { examQuestionId: 11, questionType: 'SINGLE_CHOICE' },
      { selectedOptionKeys: ['A'], textAnswer: 'stale draft of another slot' }
    );
    expect(body.answer).toEqual({ selectedOptionKeys: ['A'] });
  });

  it('never invents a judgement for an untouched TRUE_FALSE draft', () => {
    // The server answers 400 when booleanAnswer is missing
    // (QuestionAnswerEvaluator: "TRUE_FALSE requires booleanAnswer"). Sending
    // `false` would store a wrong answer the learner never chose, so the field
    // stays unset and the rejection surfaces as what it is.
    const body = buildAnswerBody(
      { examQuestionId: 11, questionType: 'TRUE_FALSE' },
      undefined
    );
    expect(body.answer.booleanAnswer).toBeUndefined();
    expect(JSON.parse(JSON.stringify(body))).toEqual({
      examQuestionId: 11,
      answer: {},
    });
  });

  // C09 closed: the paper's ORDERING / MATCHING slots answer with their own
  // contract fields. Falling through to `textAnswer: ''` (the old behaviour) was
  // a guaranteed 400 — gradeOrdering / gradeMatching only read their own field.
  it('sends orderingAnswer for ORDERING, positions and nothing else', () => {
    const body = buildAnswerBody(
      { examQuestionId: 14, questionType: 'ORDERING' },
      { orderingAnswer: [3, 1, 2], textAnswer: 'leftover draft' }
    );
    expect(body).toEqual({
      examQuestionId: 14,
      answer: { orderingAnswer: [3, 1, 2] },
    });
  });

  it('sends matchingAnswer for MATCHING, the pair JSON untouched', () => {
    const wire = JSON.stringify([
      { left: 'A', right: '1' },
      { left: 'B', right: '2' },
    ]);
    const body = buildAnswerBody(
      { examQuestionId: 15, questionType: 'MATCHING' },
      { matchingAnswer: wire }
    );
    expect(body).toEqual({ examQuestionId: 15, answer: { matchingAnswer: wire } });
  });

  it('sends neither when the learner has not committed an answer yet', () => {
    // The renderer keeps 保存答案 disabled until the sequence / the pairs are
    // complete, so the only thing an empty draft may produce is an empty answer.
    expect(
      buildAnswerBody({ examQuestionId: 14, questionType: 'ORDERING' }, undefined).answer
    ).toEqual({ orderingAnswer: undefined });
    expect(
      buildAnswerBody({ examQuestionId: 15, questionType: 'MATCHING' }, undefined).answer
    ).toEqual({ matchingAnswer: undefined });
  });
});
