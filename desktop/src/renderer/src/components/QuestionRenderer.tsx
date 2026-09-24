/**
 * Reusable Question renderer (A4/A5/A6 shared component).
 *
 * Renders all stable question types declared in the backend contract:
 * - Single-choice (radio)
 * - Multiple-choice (checkbox)
 * - Boolean (true/false)
 * - Text / reference answer (textarea)
 */

import { type KeyboardEvent } from 'react';
import { Button } from './Button';

export type QuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'BOOLEAN' | 'TEXT_ANSWER';

interface Option {
  optionKey: string;
  content: string;
  sortOrder?: number;
  isCorrect?: boolean;
}

export interface QuestionPayload {
  questionType?: string;
  stem: string;
  options?: Option[];
  explanation?: string;
}

interface QuestionRendererProps {
  question: QuestionPayload;
  answer?: {
    correctOptionKey?: string;
    correctOptionKeys?: string[];
    correctBoolean?: boolean;
    referenceAnswer?: string;
    isCorrect?: boolean;
    explanation?: string;
    score?: number;
  };
  mode: 'answer' | 'review';
  selectedOptionKeys?: string[];
  booleanAnswer?: boolean;
  textAnswer?: string;
  onSelectedOptionKeysChange?: (keys: string[]) => void;
  onBooleanAnswerChange?: (value: boolean) => void;
  onTextAnswerChange?: (value: string) => void;
  onSubmit?: () => void;
  submitLabel?: string;
  disabled?: boolean;
}

export function QuestionRenderer({
  question,
  answer,
  mode,
  selectedOptionKeys = [],
  booleanAnswer,
  textAnswer,
  onSelectedOptionKeysChange,
  onBooleanAnswerChange,
  onTextAnswerChange,
  onSubmit,
  submitLabel = '提交',
  disabled = false,
}: QuestionRendererProps) {
  const qType = (question.questionType ?? '').toUpperCase();
  const isSingle = qType === 'SINGLE_CHOICE';
  const isBoolean = qType === 'BOOLEAN';
  const isText = qType === 'TEXT_ANSWER' || qType === 'REFERENCE_ANSWER';
  const isReview = mode === 'review';
  const options = question.options ?? [];

  const toggleKey = (key: string) => {
    if (isReview) return;
    if (!onSelectedOptionKeysChange) return;
    if (isSingle) {
      onSelectedOptionKeysChange([key]);
    } else {
      const next = selectedOptionKeys.includes(key)
        ? selectedOptionKeys.filter((k) => k !== key)
        : [...selectedOptionKeys, key];
      onSelectedOptionKeysChange(next);
    }
  };

  const handleTextKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && onSubmit && !disabled) {
      e.preventDefault();
      onSubmit();
    }
  };

  return (
    <div className="question-renderer" data-testid="question-renderer">
      <p className="question-renderer__stem">{question.stem}</p>

      {isBoolean && (
        <div className="question-renderer__options">
          <Button
            type="button"
            variant={
              booleanAnswer === true
                ? answer?.isCorrect === true
                  ? 'primary'
                  : 'danger'
                : 'secondary'
            }
            disabled={disabled || isReview}
            onClick={() => onBooleanAnswerChange?.(true)}
          >
            True
          </Button>
          <Button
            type="button"
            variant={
              booleanAnswer === false
                ? answer?.isCorrect === true
                  ? 'primary'
                  : 'danger'
                : 'secondary'
            }
            disabled={disabled || isReview}
            onClick={() => onBooleanAnswerChange?.(false)}
          >
            False
          </Button>
        </div>
      )}

      {isText && (
        <textarea
          className="textarea"
          value={textAnswer ?? ''}
          onChange={(e) => onTextAnswerChange?.(e.target.value)}
          onKeyDown={handleTextKeyDown}
          placeholder="输入你的答案…"
          rows={3}
          disabled={disabled || isReview}
          aria-label="作答"
        />
      )}

      {!isBoolean && !isText && options.length > 0 && (
        <ul className="question-renderer__options">
          {options.map((opt) => {
            const selected = selectedOptionKeys.includes(opt.optionKey);
            let variant: 'primary' | 'danger' | 'secondary' = 'secondary';
            if (isReview) {
              if (opt.isCorrect) variant = 'primary';
              else if (selected) variant = 'danger';
            } else if (selected) {
              variant = 'primary';
            }
            return (
              <li key={opt.optionKey}>
                <Button
                  type="button"
                  variant={variant}
                  disabled={disabled || isReview}
                  onClick={() => toggleKey(opt.optionKey)}
                  className="question-renderer__option"
                >
                  {opt.content}
                </Button>
              </li>
            );
          })}
        </ul>
      )}

      {isReview && answer?.explanation && (
        <div className="question-renderer__explanation" data-testid="question-explanation">
          <strong>解析：</strong> {answer.explanation}
        </div>
      )}

      {!isReview && onSubmit && (
        <div className="question-renderer__actions">
          <Button
            type="button"
            variant="primary"
            onClick={onSubmit}
            disabled={
              disabled ||
              (isBoolean
                ? booleanAnswer === undefined
                : isText
                  ? (textAnswer ?? '').trim() === ''
                  : selectedOptionKeys.length === 0)
            }
          >
            {submitLabel}
          </Button>
        </div>
      )}
    </div>
  );
}
