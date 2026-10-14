/**
 * Authoring rules (W09) — the decision table behind the form's messages.
 *
 * Written against the contract's own shape rules: an option key must be
 * unique and <= 16 chars, option content <= 500, each type must carry its
 * type-specific correct answer, and no type may exceed the limits the
 * generated contract declares. The UI wiring lives in `QuestionForm.test.tsx`;
 * this file keeps the rules honest when nobody clicks anything.
 */

import { describe, expect, it } from 'vitest';
import { validateQuestionForm, type QuestionFormValues } from './question-form-rules';
import { QUESTION_FIELD_LIMITS } from '../../lib/format';

function values(patch: Partial<QuestionFormValues> = {}): QuestionFormValues {
  return {
    questionType: 'SINGLE_CHOICE',
    stem: '单选题干',
    options: [
      { optionKey: 'A', content: '第一项' },
      { optionKey: 'B', content: '第二项' },
    ],
    correctSingle: 'A',
    correctMultiple: [],
    correctBoolean: null,
    referenceAnswer: '',
    explanation: '',
    ...patch,
  };
}

describe('validateQuestionForm — choice types', () => {
  it('accepts a complete single choice', () => {
    expect(validateQuestionForm(values())).toEqual([]);
  });

  it('rejects a duplicated option key', () => {
    const errors = validateQuestionForm(
      values({
        options: [
          { optionKey: 'A', content: '第一项' },
          { optionKey: 'A', content: '第二项' },
        ],
      })
    );
    expect(errors).toContain('选项标识不能重复：A。');
  });

  it('rejects keys that only differ by surrounding spaces', () => {
    const errors = validateQuestionForm(
      values({
        options: [
          { optionKey: 'A', content: '第一项' },
          { optionKey: ' A', content: '第二项' },
        ],
        correctSingle: 'A',
      })
    );
    expect(errors).toContain('选项标识不能重复：A。');
  });

  it('rejects an empty option key or content', () => {
    const errors = validateQuestionForm(
      values({
        options: [
          { optionKey: '', content: '第一项' },
          { optionKey: 'B', content: '  ' },
        ],
        correctSingle: 'B',
      })
    );
    expect(errors).toContain('第 1 个选项缺少选项标识。');
    expect(errors).toContain('选项「B」缺少内容。');
  });

  it('applies the contract maxLength to optionKey and content', () => {
    const longKey = 'k'.repeat(QUESTION_FIELD_LIMITS.optionKey + 1);
    const longContent = 'x'.repeat(QUESTION_FIELD_LIMITS.optionContent + 1);
    const errors = validateQuestionForm(
      values({
        options: [
          { optionKey: longKey, content: longContent },
          { optionKey: 'B', content: '第二项' },
        ],
        correctSingle: 'B',
      })
    );
    expect(errors.some((e) => e.includes('超过 16 个字符'))).toBe(true);
    expect(errors.some((e) => e.includes('超过 500 个字符'))).toBe(true);
  });

  it('requires at least two options', () => {
    const errors = validateQuestionForm(values({ options: [{ optionKey: 'A', content: '只有一项' }] }));
    expect(errors).toContain('选择题至少需要 2 个选项。');
  });

  it('requires a correctOptionKey that exists for SINGLE_CHOICE', () => {
    expect(validateQuestionForm(values({ correctSingle: '' }))).toContain(
      '单选题必须指定一个正确答案。'
    );
    expect(validateQuestionForm(values({ correctSingle: 'Z' }))).toContain(
      '单选题的正确答案必须是已存在的选项标识。'
    );
  });

  it('requires existing keys for MULTIPLE_CHOICE', () => {
    const base = { questionType: 'MULTIPLE_CHOICE' as const, correctSingle: '' };
    expect(validateQuestionForm(values({ ...base, correctMultiple: [] }))).toContain(
      '多选题必须至少指定一个正确选项。'
    );
    expect(validateQuestionForm(values({ ...base, correctMultiple: ['A', 'Z'] }))).toContain(
      '正确选项必须是已存在的选项标识：Z。'
    );
    expect(validateQuestionForm(values({ ...base, correctMultiple: ['A', 'B'] }))).toEqual([]);
  });

  it('caps correctOptionKeys at the contract maxItems', () => {
    const many = Array.from({ length: QUESTION_FIELD_LIMITS.correctOptionKeys + 1 }, (_, i) => `K${i}`);
    const options = many.map((key) => ({ optionKey: key, content: `内容 ${key}` }));
    const errors = validateQuestionForm(
      values({ questionType: 'MULTIPLE_CHOICE', correctSingle: '', options, correctMultiple: many })
    );
    expect(errors).toContain('正确选项不能超过 16 个。');
  });
});

describe('validateQuestionForm — non-choice types', () => {
  it('requires correctBoolean for TRUE_FALSE', () => {
    const base = { questionType: 'TRUE_FALSE' as const, stem: '地球是平的' };
    expect(
      validateQuestionForm(values({ ...base, correctBoolean: null }))
    ).toContain('判断题必须选择正确答案（正确 / 错误）。');
    expect(
      validateQuestionForm(values({ ...base, correctBoolean: false }))
    ).toEqual([]);
  });

  it('requires referenceAnswer for SHORT_ANSWER', () => {
    const base = { questionType: 'SHORT_ANSWER' as const, stem: '简述原因' };
    expect(validateQuestionForm(values({ ...base, referenceAnswer: '' }))).toContain(
      '简答题必须填写参考答案。'
    );
    expect(
      validateQuestionForm(values({ ...base, referenceAnswer: '因为它绕着太阳转' }))
    ).toEqual([]);
  });

  it('requires referenceAnswer for FILL_BLANK (G02)', () => {
    const base = { questionType: 'FILL_BLANK' as const, stem: '水的化学式是 ___' };
    expect(validateQuestionForm(values({ ...base, referenceAnswer: '   ' }))).toContain(
      '填空题必须填写参考答案。'
    );
    expect(
      validateQuestionForm(values({ ...base, referenceAnswer: 'H2O' }))
    ).toEqual([]);
  });

  it('caps referenceAnswer and explanation at 4000', () => {
    const long = 'x'.repeat(QUESTION_FIELD_LIMITS.referenceAnswer + 1);
    const errors = validateQuestionForm(
      values({ questionType: 'FILL_BLANK', correctSingle: '', referenceAnswer: long, explanation: long })
    );
    expect(errors).toContain('参考答案最多 4000 个字符。');
    expect(errors).toContain('解析最多 4000 个字符。');
  });

  it('requires a stem for every type', () => {
    expect(validateQuestionForm(values({ stem: '   ' }))).toContain('题干不能为空。');
  });
});
