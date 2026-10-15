/**
 * Reusable Question renderer (A4/A5/A6 shared component).
 *
 * Renders the backend vocabulary in lib/format.ts: single choice, multiple
 * choice, true/false and short answer as before, plus the three types the
 * contract only used to partly support (G02 / C09):
 *
 *  - FILL_BLANK  — the answer is `AnswerPayloadView.textAnswer`, the reference
 *    answer is `QuestionAnswerView.referenceAnswer`.
 *  - ORDERING    — the answer is `AnswerPayloadView.orderingAnswer`: 1-based
 *    positions of the options this question presents (the contract puts
 *    `minimum: 1` on every item and `maxItems: 100` on the array, so a 0 is
 *    never emitted and a sequence longer than the paper never is either).
 *    Keyboard-operable: up/down buttons over a committed sequence, plus an
 *    explicit "确认当前顺序" so the presented order itself stays a legal answer.
 *  - MATCHING    — the answer is `AnswerPayloadView.matchingAnswer`: a JSON
 *    string holding `{left,right}` objects and nothing else, which is the only
 *    shape `QuestionAnswerEvaluator.gradeMatching()` can compare (it parses
 *    both sides into lists of maps and compares them as SETS).
 *
 * `OptionView` carries no side field, so — exactly as the read side of the
 * question bank presents it — the first half of the option list is the left
 * column and the rest is the right column, and a pair names the two
 * `optionKey`s. That is the same convention `CreateQuestionRequest.matches`
 * documents (`[{"left":"A","right":"1"}]`).
 *
 * Props stay additive: every prop below existed before this file learned
 * about the three new types, and unknown `questionType` values still fall
 * back to rendering their options instead of crashing.
 */

import { type KeyboardEvent } from 'react';
import { Button } from './Button';
import { SelectMenu } from './SelectMenu';
import {
  identityPositions,
  isCompleteOrdering,
  normalizeOrderingAnswer,
  parseMatchingPairs,
  serializeMatchingPairs,
  splitMatchingOptions,
  type MatchingPair,
} from './answer-grammar';
import { QUESTION_FIELD_LIMITS } from '../lib/format';

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
   * For the choice types only membership matters. ORDERING no longer travels
   * here: it has its own contract field (`orderingAnswer`).
   */
  selectedOptionKeys?: string[];
  booleanAnswer?: boolean;
  textAnswer?: string;
  /**
   * ORDERING: 1-based positions into `question.options`, one entry per option.
   * This IS the answer, in the shape the backend grades, so the page stores
   * exactly what it will send.
   */
  orderingAnswer?: number[];
  /** MATCHING: the serialized `[{left,right},…]` array, i.e. the wire value. */
  matchingAnswer?: string;
  onSelectedOptionKeysChange?: (keys: string[]) => void;
  onBooleanAnswerChange?: (value: boolean) => void;
  onTextAnswerChange?: (value: string) => void;
  onOrderingAnswerChange?: (orderingAnswer: number[]) => void;
  onMatchingAnswerChange?: (matchingAnswer: string) => void;
  onSubmit?: () => void;
  /**
   * Keyboard-only submit for pages that render their own submit button.
   * `onSubmit` also draws a button, so a host with its own control (the
   * practice card, whose button carries per-question saving/error labels)
   * passes this instead — Enter still sends, no second button appears.
   */
  onTextEnterSubmit?: () => void;
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
  orderingAnswer,
  matchingAnswer,
  onSelectedOptionKeysChange,
  onBooleanAnswerChange,
  onTextAnswerChange,
  onOrderingAnswerChange,
  onMatchingAnswerChange,
  onSubmit,
  onTextEnterSubmit,
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

  /* ------------------------------------------------------------------ *
   * ORDERING — the committed sequence is 1-based positions of `options`.
   * ------------------------------------------------------------------ */
  const committedOrdering = normalizeOrderingAnswer(orderingAnswer, options.length);
  const orderingComplete = isCompleteOrdering(orderingAnswer, options.length);
  /**
   * Controlled: the page holds the sequence. In answer mode an uncommitted
   * sequence still shows the options in the order the backend sent them — that
   * is the starting point the up/down buttons operate on, and "确认当前顺序"
   * turns it into the answer. In review mode nothing was committed, so nothing
   * is drawn: printing the option order there would pass a shuffle off as the
   * user's response.
   */
  const orderingRows = isReview
    ? committedOrdering
    : [...committedOrdering, ...identityPositions(options.length).filter((p) => !committedOrdering.includes(p))];
  const canEditOrdering = !isReview && Boolean(onOrderingAnswerChange);

  const optionByKey = new Map(options.map((opt) => [opt.optionKey, opt] as const));
  const { left: leftItems, right: rightItems } = splitMatchingOptions(options);

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
    if (!canEditOrdering || !onOrderingAnswerChange) return;
    const target = index + delta;
    if (target < 0 || target >= orderingRows.length) return;
    const next = [...orderingRows];
    const moved = next[index];
    next[index] = next[target];
    next[target] = moved;
    // A swap is a decision about the whole sequence, so the emitted array is
    // always complete: 1-based, one entry per option, never a 0.
    onOrderingAnswerChange(next);
  };

  const commitOrdering = () => {
    if (!canEditOrdering || !onOrderingAnswerChange) return;
    onOrderingAnswerChange(orderingRows);
  };

  /* ------------------------------------------------------------------ *
   * MATCHING — rows keyed by the left item, value = the chosen right key.
   * ------------------------------------------------------------------ */
  const matchingPairs = parseMatchingPairs(
    matchingAnswer,
    leftItems.map((item) => item.optionKey),
    rightItems.map((item) => item.optionKey)
  );
  const rightByLeft = new Map(matchingPairs.map((pair) => [pair.left, pair.right] as const));
  const matchingWire = serializeMatchingPairs(matchingPairs);
  const matchingComplete =
    leftItems.length > 0 &&
    matchingPairs.length === leftItems.length &&
    matchingWire.length <= QUESTION_FIELD_LIMITS.matchingAnswer;
  const canEditPairs = !isReview && Boolean(onMatchingAnswerChange);

  const setPair = (leftKey: string, rightKey: string) => {
    if (!canEditPairs || !onMatchingAnswerChange) return;
    const nextRight = new Map(rightByLeft);
    if (rightKey === '') nextRight.delete(leftKey);
    else nextRight.set(leftKey, rightKey);
    // Built from the row model, so every left item appears at most once and
    // every pair has exactly the two keys the grader parses.
    onMatchingAnswerChange(
      serializeMatchingPairs(
        leftItems
          .map((item) => {
            const right = nextRight.get(item.optionKey);
            return right ? { left: item.optionKey, right } : null;
          })
          .filter((pair): pair is MatchingPair => pair !== null)
      )
    );
  };

  const handleTextKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    const submit = onSubmit ?? onTextEnterSubmit;
    if (e.key === 'Enter' && !e.shiftKey && submit && !disabled) {
      e.preventDefault();
      submit();
    }
  };

  const submitDisabled =
    disabled ||
    (isBoolean
      ? booleanAnswer === undefined
      : isOrdering
        ? !orderingComplete
        : isMatching
          ? !matchingComplete
          : isText
            ? (textAnswer ?? '').trim() === ''
            : selectedOptionKeys.length === 0);

  const labelOf = (key: string): string => optionByKey.get(key)?.content ?? key;

  /**
   * A position spoken as the item the learner sees, for the live region below.
   * `aria-live` is what makes "上移 / 下移" verifiable without looking: moving a
   * row changes only the DOM order, which assistive tech reports as nothing.
   */
  const positionLabel = (position: number): string => {
    const opt = options[position - 1];
    if (!opt) {
      return `选项 ${position}`;
    }
    const content = opt.content.trim();
    return content !== '' ? content : opt.optionKey;
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
            aria-pressed={booleanAnswer === true}
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
            aria-pressed={booleanAnswer === false}
          >
            错误
          </Button>
        </div>
      )}

      {/* A controlled input the page cannot read is worse than none: the user
          types into it and the value is thrown away. So an answer-mode text
          control appears only when the page wired the callback (review mode
          always shows it, disabled, as the submitted answer). */}
      {isText && (mode === 'review' || onTextAnswerChange) && (
        <textarea
          className="textarea"
          value={textAnswer ?? ''}
          onChange={(e) => onTextAnswerChange?.(e.target.value)}
          onKeyDown={handleTextKeyDown}
          placeholder={
            isFillBlank
              ? '按题目顺序填写，多个空用英文逗号分隔（需与参考答案写法完全一致）'
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

      {isOrdering && options.length === 0 && (
        <p className="muted" data-testid="ordering-no-options">
          本题没有可排序的选项，无法作答。
        </p>
      )}

      {isOrdering && options.length > 0 && (
        <ol className="question-renderer__ordering" data-testid="ordering-list">
          {orderingRows.map((position, idx) => {
            const opt = options[position - 1];
            return (
              <li
                key={position}
                className="question-renderer__ordering-item"
                data-testid="ordering-item"
                data-option-key={opt?.optionKey ?? ''}
              >
                <span className="question-renderer__ordering-rank">{idx + 1}</span>
                <span className="question-renderer__ordering-text">
                  {opt?.content ?? `选项 ${position}`}
                </span>
                {canEditOrdering && (
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
                      disabled={disabled || idx === orderingRows.length - 1}
                      onClick={() => moveItem(idx, 1)}
                    >
                      下移
                    </Button>
                  </span>
                )}
              </li>
            );
          })}
        </ol>
      )}

      {isOrdering && canEditOrdering && (
        <p
          className="field__hint"
          role="status"
          data-testid="ordering-status"
        >
          {orderingComplete
            ? `已提交顺序：${committedOrdering.map(positionLabel).join('、')}`
            : `当前排列：${orderingRows
                .map(positionLabel)
                .join('、')}（共 ${options.length} 项，尚未确认）`}
        </p>
      )}

      {isOrdering && canEditOrdering && !orderingComplete && (
        <div className="question-renderer__ordering-commit">
          <Button
            type="button"
            variant="secondary"
            aria-label="确认当前顺序"
            disabled={disabled || options.length === 0}
            onClick={commitOrdering}
          >
            确认当前顺序
          </Button>
          <span className="field__hint">
            用「上移 / 下移」调整，或直接确认当前顺序；提交需要为每个选项确定位置。
          </span>
        </div>
      )}

      {isOrdering && isReview && committedOrdering.length === 0 && (
        <p className="muted" data-testid="ordering-empty">
          没有已提交的顺序。
        </p>
      )}

      {/* MATCHING: a pair editor over the two columns. The emitted string is
          exactly `[{left,right},…]` — the only thing gradeMatching() compares. */}
      {isMatching && canEditPairs && leftItems.length > 0 && (
        <ul className="question-renderer__matching-rows" data-testid="matching-editor">
          {leftItems.map((item) => {
            const selected = rightByLeft.get(item.optionKey) ?? '';
            return (
              <li
                key={item.optionKey}
                className="question-renderer__matching-row"
                data-testid="matching-row"
                data-left-key={item.optionKey}
              >
                <SelectMenu
                  label={`为「${item.content}」选择配对项`}
                  value={selected}
                  disabled={disabled}
                  onValueChange={(value) => setPair(item.optionKey, value)}
                  options={[
                    // The empty value is the "no pair yet" state: `setPair`
                    // deletes the left key for it, so choosing it again un-pairs
                    // the row and leaves the answer incomplete.
                    { value: '', label: '请选择配对项' },
                    ...rightItems.map((right) => ({
                      value: right.optionKey,
                      label: right.content,
                    })),
                  ]}
                />
              </li>
            );
          })}
        </ul>
      )}

      {isMatching && canEditPairs && leftItems.length === 0 && (
        <p className="muted" data-testid="matching-no-options">
          本题没有可配对的选项，无法作答。
        </p>
      )}

      {isMatching && isReview && matchingPairs.length === 0 && (
        <p className="muted" data-testid="matching-empty">
          没有已提交的配对。
        </p>
      )}

      {isMatching && isReview && matchingPairs.length > 0 && (
        <ul className="question-renderer__matching-pairs" data-testid="matching-pair-list">
          {matchingPairs.map((pair) => (
            <li key={pair.left} className="question-renderer__matching-pair" data-testid="matching-pair">
              <span className="question-renderer__matching-pair-text">
                {labelOf(pair.left)} ↔ {labelOf(pair.right)}
              </span>
            </li>
          ))}
        </ul>
      )}

      {/* The item pool, read-only: shown in review (so a stored question still
          lists what can be paired) and in answer mode when the page cannot hold
          pairs — a controlled editor the page cannot read is worse than none. */}
      {isMatching && !canEditPairs && (
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
              {leftItems.map((opt) => (
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
              {rightItems.map((opt) => (
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
                    aria-pressed={selected}
                    aria-label={
                      isReview && opt.isCorrect
                        ? `${opt.content}（标准答案）`
                        : undefined
                    }
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

      {/* ORDERING and MATCHING are submittable now, but only once the sequence
          / the pairs are complete — an incomplete one is a 400 or a guaranteed
          wrong grade, so the button stays disabled. */}
      {!isReview && onSubmit && (
        <div className="question-renderer__actions">
          <Button
            type="button"
            variant="primary"
            onClick={onSubmit}
            disabled={submitDisabled}
          >
            {submitLabel}
          </Button>
          {isOrdering && !orderingComplete && (
            <span className="field__hint">请先确定每个选项的位置。</span>
          )}
          {isMatching && !matchingComplete && (
            <span className="field__hint">请为每个左侧项目选择一个配对项。</span>
          )}
        </div>
      )}
    </div>
  );
}
