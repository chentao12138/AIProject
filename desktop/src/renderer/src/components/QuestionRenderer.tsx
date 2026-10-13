/**
 * Reusable Question renderer (A4/A5/A6 shared component).
 *
 * Renders the backend vocabulary in lib/format.ts: single choice, multiple
 * choice, true/false and short answer as before, plus the three types the
 * contract only partly supports (G02):
 *
 *  - FILL_BLANK — closes fully: the answer is `AnswerPayloadView.textAnswer`,
 *    the reference answer is `QuestionAnswerView.referenceAnswer`.
 *  - ORDERING   — the interaction is here and keyboard-operable (up/down
 *    buttons, no drag required), and the order travels in the one contract
 *    field that has an order: `selectedOptionKeys` as an ordered array. The
 *    *correct* order has no contract field at all, so review can only show
 *    the submitted order and says so (C09).
 *  - MATCHING   — read-only. The contract has no pair structure anywhere, so
 *    this component refuses to invent one and refuses to offer an answer.
 *
 * Props stay additive: every prop below existed before this file learned
 * about the three new types, and unknown `questionType` values still fall
 * back to rendering their options instead of crashing.
 */

import { type KeyboardEvent } from 'react';
import { Button } from './Button';
import {
  CONTRACT_GAP_NOTICES,
  QUESTION_FIELD_LIMITS,
} from '../lib/format';

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
  /**
   * For ORDERING this array IS the answer: its index is the position the
   * user put each option in. For the choice types only membership matters.
   */
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
  const isBoolean = qType === 'TRUE_FALSE';
  const isFillBlank = qType === 'FILL_BLANK';
  const isOrdering = qType === 'ORDERING';
  const isMatching = qType === 'MATCHING';
  const isText = qType === 'SHORT_ANSWER' || isFillBlank;
  const isReview = mode === 'review';
  const options = question.options ?? [];

  /**
   * The order the user has committed to. Controlled: until the parent has a
   * value we show the options in the order the backend sent them, which is
   * the starting position the up/down buttons operate on.
   */
  const orderingKeys =
    selectedOptionKeys.length > 0
      ? selectedOptionKeys
      : options.map((opt) => opt.optionKey);
  const optionByKey = new Map(
    options.map((opt) => [opt.optionKey, opt] as const)
  );

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

  /** Swap one item with its neighbour. Buttons, so keyboard works for free. */
  const moveItem = (index: number, delta: -1 | 1) => {
    if (isReview || !onSelectedOptionKeysChange) return;
    const target = index + delta;
    if (target < 0 || target >= orderingKeys.length) return;
    const next = [...orderingKeys];
    const moved = next[index];
    next[index] = next[target];
    next[target] = moved;
    onSelectedOptionKeysChange(next);
  };

  const handleTextKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && onSubmit && !disabled) {
      e.preventDefault();
      onSubmit();
    }
  };

  /** Copy telling the user which part of the loop the contract cannot close. */
  const gapNotice = isOrdering
    ? isReview
      ? CONTRACT_GAP_NOTICES.ORDERING_REVIEW
      : CONTRACT_GAP_NOTICES.ORDERING_ANSWER
    : isMatching
      ? CONTRACT_GAP_NOTICES.MATCHING
      : null;

  const submitDisabled =
    disabled ||
    (isBoolean
      ? booleanAnswer === undefined
      : isText
        ? (textAnswer ?? '').trim() === ''
        : selectedOptionKeys.length === 0);

  // Left / right column split of a flat option list. Layout only: the
  // contract has no `pairs`, so nothing here claims which item matches which.
  const matchingMid = Math.ceil(options.length / 2);

  return (
    <div className="question-renderer" data-testid="question-renderer">
      <p className="question-renderer__stem">{question.stem}</p>

      {gapNotice && (
        <p className="form__note" role="note" data-testid="contract-gap-notice">
          {gapNotice}
        </p>
      )}

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
            正确
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
            错误
          </Button>
        </div>
      )}

      {isText && (
        <textarea
          className="textarea"
          value={textAnswer ?? ''}
          onChange={(e) => onTextAnswerChange?.(e.target.value)}
          onKeyDown={handleTextKeyDown}
          placeholder={
            isFillBlank
              ? '填写空里的答案…'
              : '输入你的答案…'
          }
          rows={3}
          disabled={disabled || isReview}
          maxLength={QUESTION_FIELD_LIMITS.textAnswer}
          aria-label="作答"
        />
      )}

      {isText && isReview && answer?.referenceAnswer && (
        <div
          className="question-renderer__reference"
          data-testid="reference-answer"
        >
          <strong>参考答案：</strong> {answer.referenceAnswer}
        </div>
      )}

      {isOrdering && (
        <ol className="question-renderer__ordering" data-testid="ordering-list">
          {orderingKeys.map((key, idx) => (
            <li
              key={key}
              className="question-renderer__ordering-item"
              data-testid="ordering-item"
              data-option-key={key}
            >
              <span className="question-renderer__ordering-rank">{idx + 1}</span>
              <span className="question-renderer__ordering-text">
                {optionByKey.get(key)?.content ?? key}
              </span>
              {!isReview && onSelectedOptionKeysChange && (
                <span className="question-renderer__ordering-actions">
                  <Button
                    type="button"
                    variant="secondary"
                    aria-label={`上移第 ${idx + 1} 项`}
                    disabled={disabled || idx === 0}
                    onClick={() => moveItem(idx, -1)}
                  >
                    上移
                  </Button>
                  <Button
                    type="button"
                    variant="secondary"
                    aria-label={`下移第 ${idx + 1} 项`}
                    disabled={disabled || idx === orderingKeys.length - 1}
                    onClick={() => moveItem(idx, 1)}
                  >
                    下移
                  </Button>
                </span>
              )}
            </li>
          ))}
        </ol>
      )}

      {isOrdering && isReview && selectedOptionKeys.length === 0 && (
        <p className="muted" data-testid="ordering-empty">
          没有已提交的顺序。
        </p>
      )}

      {isMatching && (
        <div
          className="question-renderer__matching"
          data-testid="matching-readonly"
        >
          <div
            className="question-renderer__matching-column"
            data-testid="matching-column"
          >
            <h3 className="section-title">选项（左列）</h3>
            <ul>
              {options.slice(0, matchingMid).map((opt) => (
                <li key={opt.optionKey}>
                  {opt.optionKey}. {opt.content}
                </li>
              ))}
            </ul>
          </div>
          <div
            className="question-renderer__matching-column"
            data-testid="matching-column"
          >
            <h3 className="section-title">选项（右列）</h3>
            <ul>
              {options.slice(matchingMid).map((opt) => (
                <li key={opt.optionKey}>
                  {opt.optionKey}. {opt.content}
                </li>
              ))}
            </ul>
          </div>
        </div>
      )}

      {!isBoolean &&
        !isText &&
        !isOrdering &&
        !isMatching &&
        options.length > 0 && (
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

      {/* MATCHING has no contract field to answer with, so it gets no
          submit button — offering one would promise a payload we cannot
          build. ORDERING stays submittable but only once an order exists. */}
      {!isReview && !isMatching && onSubmit && (
        <div className="question-renderer__actions">
          <Button
            type="button"
            variant="primary"
            onClick={onSubmit}
            disabled={submitDisabled}
          >
            {submitLabel}
          </Button>
          {isOrdering && selectedOptionKeys.length === 0 && (
            <span className="field__hint">
              用「上移 / 下移」确定顺序后再提交。
            </span>
          )}
        </div>
      )}
    </div>
  );
}
