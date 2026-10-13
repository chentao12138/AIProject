/**
 * formatBytes tests (FE-002A PHASE 11) + the question-type support matrix
 * and contract-gap copy this line gates on (G02 / W08 / W09).
 */

import { describe, expect, it } from 'vitest';
import {
  AUTHORABLE_QUESTION_TYPES,
  CONTRACT_GAP_NOTICES,
  QUESTION_FIELD_LIMITS,
  QUESTION_TYPES,
  formatBytes,
  isAuthorableQuestionType,
  isPublishableQuestionType,
  questionTypeGap,
  questionTypeLabel,
  questionTypeSupport,
} from './format';

describe('formatBytes', () => {
  it('returns — for undefined / null', () => {
    expect(formatBytes(undefined)).toBe('—');
    expect(formatBytes(null)).toBe('—');
  });

  it('returns — for invalid and negative values', () => {
    expect(formatBytes(NaN)).toBe('—');
    expect(formatBytes(Infinity)).toBe('—');
    expect(formatBytes(-1)).toBe('—');
    expect(formatBytes(-1024)).toBe('—');
  });

  it('formats 0 as 0 B', () => {
    expect(formatBytes(0)).toBe('0 B');
  });

  it('formats byte values', () => {
    expect(formatBytes(1)).toBe('1 B');
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(1023)).toBe('1023 B');
  });

  it('formats KB values', () => {
    expect(formatBytes(1024)).toBe('1 KB');
    expect(formatBytes(1536)).toBe('1.5 KB');
    expect(formatBytes(102400)).toBe('100 KB');
  });

  it('formats MB values', () => {
    expect(formatBytes(1024 * 1024)).toBe('1 MB');
    expect(formatBytes(1024 * 1024 * 5.5)).toBe('5.5 MB');
  });

  it('formats GB values', () => {
    expect(formatBytes(1024 * 1024 * 1024)).toBe('1 GB');
    expect(formatBytes(1024 * 1024 * 1024 * 2.25)).toBe('2.3 GB');
  });
});

describe('AUTHORABLE_QUESTION_TYPES (G02)', () => {
  it('offers FILL_BLANK because its answer and reference answer are both in the contract', () => {
    expect(AUTHORABLE_QUESTION_TYPES).toContain('FILL_BLANK');
    expect(isAuthorableQuestionType('FILL_BLANK')).toBe(true);
  });

  it('keeps every previously authorable type authorable', () => {
    for (const type of ['SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER']) {
      expect(isAuthorableQuestionType(type)).toBe(true);
    }
  });

  it('opens ORDERING and MATCHING now that the wire format exists', () => {
    // `question-type-contract.test.ts` is what proves the contract really
    // carries correctOrder / matches / orderingAnswer / matchingAnswer; this
    // only pins that the matrix agrees with it.
    expect(AUTHORABLE_QUESTION_TYPES).toContain('ORDERING');
    expect(AUTHORABLE_QUESTION_TYPES).toContain('MATCHING');
    expect(isAuthorableQuestionType('ORDERING')).toBe(true);
    expect(isPublishableQuestionType('ORDERING')).toBe(true);
    expect(isPublishableQuestionType('MATCHING')).toBe(true);
    expect(questionTypeSupport('ORDERING')?.answerable).toBe(true);
    expect(questionTypeSupport('MATCHING')?.answerable).toBe(true);
  });

  it('treats an unknown questionType as no opinion at all', () => {
    // The contract types questionType as a free-form string, so an unseen
    // value must not be gated by a guess — the renderer keeps showing it.
    expect(questionTypeSupport('OSS_QUESTION')).toBeNull();
    expect(questionTypeSupport(undefined)).toBeNull();
    expect(isAuthorableQuestionType('OSS_QUESTION')).toBe(false);
    expect(questionTypeGap('OSS_QUESTION')).toBeNull();
  });

  it('is a subset of the seven backend types', () => {
    expect(
      AUTHORABLE_QUESTION_TYPES.every((t) =>
        (QUESTION_TYPES as readonly string[]).includes(t)
      )
    ).toBe(true);
  });
});

describe('contract-gap copy', () => {
  it('names the field it is talking about instead of saying "not supported"', () => {
    expect(CONTRACT_GAP_NOTICES.QUESTION_UPDATE_FIELDS).toContain(
      'UpdateQuestionRequest'
    );
  });

  // C09b closed: `QuestionAnswerView` now declares `correctOrder` and `matches`,
  // so the two "cannot read it back" notices must not come back. This assertion
  // is the guard — a re-added notice key turns it red.
  it('has no read-side gap notice left', () => {
    expect(Object.keys(CONTRACT_GAP_NOTICES)).toEqual(['QUESTION_UPDATE_FIELDS']);
  });

  it('has no authoring gap left to attach', () => {
    // Every type the contract can carry is now creatable, so nothing shows a
    // refusal banner on the create form.
    for (const type of AUTHORABLE_QUESTION_TYPES) {
      expect(questionTypeGap(type), `${type} still refuses authoring`).toBeNull();
    }
    expect(questionTypeGap('FILL_BLANK')).toBeNull();
    expect(questionTypeGap('SINGLE_CHOICE')).toBeNull();
  });

  it('labels the three new types for display', () => {
    expect(questionTypeLabel('FILL_BLANK')).toBe('填空');
    expect(questionTypeLabel('ordering')).toBe('排序');
    expect(questionTypeLabel('MATCHING')).toBe('配对');
  });
});

describe('QUESTION_FIELD_LIMITS (W09)', () => {
  it('caps the inputs the contract caps', () => {
    expect(QUESTION_FIELD_LIMITS.optionKey).toBe(16);
    expect(QUESTION_FIELD_LIMITS.optionContent).toBe(500);
    expect(QUESTION_FIELD_LIMITS.explanation).toBe(4000);
    expect(QUESTION_FIELD_LIMITS.referenceAnswer).toBe(4000);
    expect(QUESTION_FIELD_LIMITS.textAnswer).toBe(4000);
  });

  it('does not cap stem, because the contract does not', () => {
    expect(
      Object.prototype.hasOwnProperty.call(QUESTION_FIELD_LIMITS, 'stem')
    ).toBe(false);
  });
});
