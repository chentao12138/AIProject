/**
 * Authoring rules for the question create form (W09, A4.4).
 *
 * Split out of `QuestionForm.tsx` so the form module keeps exporting only the
 * component (react-refresh) and so the rules are testable without driving a
 * dozen clicks per case.
 *
 * Every rule here mirrors a field the published contract declares:
 *
 *   SINGLE_CHOICE    options + correctOptionKey   (optionKey unique, <= 16)
 *   MULTIPLE_CHOICE  options + correctOptionKeys  (maxItems 16)
 *   TRUE_FALSE       correctBoolean, no options
 *   SHORT_ANSWER     referenceAnswer, no options
 *   FILL_BLANK       referenceAnswer, no options (G02)
 *
 * `question-type-contract.test.ts` fails if one of those contract fields ever
 * disappears or changes its limit, and the limits themselves live in
 * `lib/format.QUESTION_FIELD_LIMITS` where the same guard pins them.
 */

import { QUESTION_FIELD_LIMITS, type QuestionType } from '../../lib/format';

export interface OptionInput {
  optionKey: string;
  content: string;
}

export interface QuestionFormValues {
  questionType: QuestionType;
  stem: string;
  options: OptionInput[];
  correctSingle: string;
  correctMultiple: string[];
  correctBoolean: boolean | null;
  referenceAnswer: string;
  explanation: string;
}

const CHOICE_TYPES: readonly string[] = ['SINGLE_CHOICE', 'MULTIPLE_CHOICE'];
const TEXT_ANSWER_TYPES: readonly string[] = ['SHORT_ANSWER', 'FILL_BLANK'];

export function isChoiceType(type: QuestionType): boolean {
  return CHOICE_TYPES.includes(type);
}

/**
 * Shape checks for one question type, as one readable message list.
 *
 * A form that passes here cannot 400 for shape, and a form that fails here
 * never put a half-written question into the space (there is no delete
 * endpoint to clean one up — see G04 / G09).
 */
export function validateQuestionForm(values: QuestionFormValues): string[] {
  const errors: string[] = [];
  const { questionType } = values;

  if (values.stem.trim() === '') {
    errors.push('题干不能为空。');
  }
  if (values.explanation.length > QUESTION_FIELD_LIMITS.explanation) {
    errors.push(`解析最多 ${QUESTION_FIELD_LIMITS.explanation} 个字符。`);
  }

  if (isChoiceType(questionType)) {
    const keys: string[] = [];
    values.options.forEach((opt, idx) => {
      const key = opt.optionKey.trim();
      const content = opt.content.trim();
      if (key === '') {
        errors.push(`第 ${idx + 1} 个选项缺少选项标识。`);
      } else if (key.length > QUESTION_FIELD_LIMITS.optionKey) {
        errors.push(
          `选项标识「${key}」超过 ${QUESTION_FIELD_LIMITS.optionKey} 个字符。`
        );
      } else if (keys.includes(key)) {
        errors.push(`选项标识不能重复：${key}。`);
      } else {
        keys.push(key);
      }
      if (content === '') {
        errors.push(`选项「${key || idx + 1}」缺少内容。`);
      } else if (content.length > QUESTION_FIELD_LIMITS.optionContent) {
        errors.push(
          `选项「${key}」内容超过 ${QUESTION_FIELD_LIMITS.optionContent} 个字符。`
        );
      }
    });

    if (values.options.length < 2) {
      errors.push('选择题至少需要 2 个选项。');
    }

    if (questionType === 'SINGLE_CHOICE') {
      if (values.correctSingle.trim() === '') {
        errors.push('单选题必须指定一个正确答案。');
      } else if (!keys.includes(values.correctSingle.trim())) {
        errors.push('单选题的正确答案必须是已存在的选项标识。');
      }
    } else {
      const picked = values.correctMultiple.map((k) => k.trim()).filter(Boolean);
      if (picked.length === 0) {
        errors.push('多选题必须至少指定一个正确选项。');
      }
      if (picked.length > QUESTION_FIELD_LIMITS.correctOptionKeys) {
        errors.push(
          `正确选项不能超过 ${QUESTION_FIELD_LIMITS.correctOptionKeys} 个。`
        );
      }
      const unknown = picked.filter((k) => !keys.includes(k));
      if (unknown.length > 0) {
        errors.push(`正确选项必须是已存在的选项标识：${unknown.join('、')}。`);
      }
    }
  }

  if (questionType === 'TRUE_FALSE' && values.correctBoolean === null) {
    errors.push('判断题必须选择正确答案（正确 / 错误）。');
  }

  if (TEXT_ANSWER_TYPES.includes(questionType)) {
    if (values.referenceAnswer.trim() === '') {
      errors.push(
        questionType === 'FILL_BLANK'
          ? '填空题必须填写参考答案。'
          : '简答题必须填写参考答案。'
      );
    }
    if (values.referenceAnswer.length > QUESTION_FIELD_LIMITS.referenceAnswer) {
      errors.push(
        `参考答案最多 ${QUESTION_FIELD_LIMITS.referenceAnswer} 个字符。`
      );
    }
  }

  return errors;
}
