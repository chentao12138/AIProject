/**
 * Per-question exam save state (G03 / W04 / W06) — the pure half of the fix.
 *
 * The `questionSaveStatus` cases below are the G03 gate in miniature: a save
 * that happened for slot A must not be reported for slot B.
 */

import { describe, expect, it } from 'vitest';
import {
  buildAnswerBody,
  countAnswered,
  isSavedOnServer,
  questionSaveStatus,
  QUESTION_SAVE_LABELS,
  type ExamAnswerRecord,
} from './exam-attempt-state';

const savedRecord: ExamAnswerRecord = {
  id: 900,
  examAttemptId: 5,
  examQuestionId: 11,
  gradingStatus: 'GRADED',
  answeredAt: '2026-10-13T10:00:00',
};

describe('isSavedOnServer (W04: server truth, not memory)', () => {
  it('counts a record only when the server sent answeredAt', () => {
    expect(isSavedOnServer(savedRecord)).toBe(true);
  });

  it('rejects a successful POST that carried no answer record', () => {
    // `unwrap` yields `undefined` for an empty 200 body, and the contract's
    // ExamAnswerView marks every field optional — neither proves storage.
    expect(isSavedOnServer(undefined)).toBe(false);
    expect(isSavedOnServer({ id: 1, examQuestionId: 11 })).toBe(false);
    expect(isSavedOnServer({ answeredAt: '   ' })).toBe(false);
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
