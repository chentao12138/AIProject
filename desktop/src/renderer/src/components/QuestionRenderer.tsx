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

import { type KeyboardEvent, useId } from 'react';
import { Button } from './Button';
import {
  QUESTION_FIELD_LIMITS,
  QUESTION_TYPE_DISPLAY_GAP,
  type QuestionType,
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

/**
 * One MATCHING pair. The wire format is an array of these serialized, and the
 * server's grader compares the parsed maps for equality, so a pair with an
 * extra or renamed key can only ever be graded wrong — `parseMatchingPairs`
 * refuses to produce one.
 */
export interface MatchingPair {
  left: string;
  right: string;
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

/** 1 … count: the order the backend presented the options in. */
function identityPositions(count: number): number[] {
  return Array.from({ length: count }, (_, index) => index + 1);
}

/**
 * Read a drafted/stored `orderingAnswer` back into rows. Only whole positions
 * inside the question are kept, and a repeat is dropped: the contract demands
 * `minimum: 1` per item and the grader wants one permutation, so anything else
 * is shown as unanswered rather than submitted as a 400.
 */
export function normalizeOrderingAnswer(
  value: readonly number[] | undefined,
  count: number
): number[] {
  const seen = new Set<number>();
  const kept: number[] = [];
  for (const position of value ?? []) {
    if (!Number.isInteger(position) || position < 1 || position > count) continue;
    if (seen.has(position)) continue;
    seen.add(position);
    kept.push(position);
  }
  return kept;
}

/** Every position exactly once — what makes an ORDERING answer submittable. */
export function isCompleteOrdering(
  value: readonly number[] | undefined,
  count: number
): boolean {
  if (count <= 0) return false;
  return normalizeOrderingAnswer(value, count).length === count;
}

/**
 * The left / right pools of a MATCHING question. `OptionView` has no side
 * field, so the first half (rounded up) is the left column — the same split
 * the read side of this component has always shown.
 */
export function splitMatchingOptions<T extends { optionKey: string; content: string }>(
  options: readonly T[]
): { left: T[]; right: T[] } {
  const mid = Math.ceil(options.length / 2);
  return { left: options.slice(0, mid), right: options.slice(mid) };
}

function isPairShape(value: unknown): value is MatchingPair {
  if (typeof value !== 'object' || value === null) return false;
  const record = value as { left?: unknown; right?: unknown };
  const keys = Object.keys(record);
  // Exactly {left,right}: the grader compares maps, so a stray key — or a
  // renamed one — is a pair that can never match.
  return (
    keys.length === 2 &&
    typeof record.left === 'string' &&
    typeof record.right === 'string' &&
    record.left.trim() !== '' &&
    record.right.trim() !== ''
  );
}

/**
 * Parse the wire string back into rows, keeping only pairs this question can
 * still express: known keys, a left item not yet paired, a non-blank right.
 * Malformed JSON reads as "no pairs yet" — the server rejects that string with
 * a 400, so it must never be re-sent just because something stored it.
 */
export function parseMatchingPairs(
  value: string | undefined,
  leftKeys: readonly string[],
  rightKeys: readonly string[]
): MatchingPair[] {
  const raw = value ?? '';
  if (raw.trim() === '') return [];
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return [];
  }
  if (!Array.isArray(parsed)) return [];
  const knownLeft = new Set(leftKeys);
  const knownRight = new Set(rightKeys);
  const usedLeft = new Set<string>();
  const pairs: MatchingPair[] = [];
  for (const entry of parsed) {
    if (!isPairShape(entry)) continue;
    if (!knownLeft.has(entry.left) || !knownRight.has(entry.right)) continue;
    if (usedLeft.has(entry.left)) continue;
    usedLeft.add(entry.left);
    pairs.push({ left: entry.left, right: entry.right });
  }
  return pairs;
}

/** The wire value for a row model: `{left,right}` and nothing else. */
export function serializeMatchingPairs(pairs: readonly MatchingPair[]): string {
  return JSON.stringify(pairs.map((pair) => ({ left: pair.left, right: pair.right })));
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
  const selectIdBase = useId();
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

  /** Copy telling the user which part of the loop the contract cannot close. */
  const displayGap = QUESTION_TYPE_DISPLAY_GAP[qType as QuestionType];
  const gapNotice = isReview ? displayGap?.review ?? null : null;

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
          {leftItems.map((item, idx) => {
            const selected = rightByLeft.get(item.optionKey) ?? '';
            const selectId = `${selectIdBase}-matching-${idx}`;
            return (
              <li
                key={item.optionKey}
                className="question-renderer__matching-row"
                data-testid="matching-row"
                data-left-key={item.optionKey}
              >
                <label
                  className="question-renderer__matching-left"
                  htmlFor={selectId}
                >
                  {idx + 1}. {item.content}
                </label>
                <select
                  id={selectId}
                  className="select"
                  value={selected}
                  disabled={disabled}
                  aria-label={`为「${item.content}」选择配对项`}
                  onChange={(e) => setPair(item.optionKey, e.target.value)}
                >
                  <option value="">请选择配对项</option>
                  {rightItems.map((right) => (
                    <option key={right.optionKey} value={right.optionKey}>
                      {right.content}
                    </option>
                  ))}
                </select>
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

      {isMatching && isReview && (
        <div className="question-renderer__matching" data-testid="matching-review">
          {matchingPairs.length === 0 ? (
            <p className="muted" data-testid="matching-empty">
              没有已提交的配对。
            </p>
          ) : (
            <ul data-testid="matching-pair-list">
              {matchingPairs.map((pair) => (
                <li key={pair.left} data-testid="matching-pair">
                  {labelOf(pair.left)} ↔ {labelOf(pair.right)}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {/* Answer mode with no wired callback: show the two columns as the item
          pool, but never an editor whose value the page cannot read. */}
      {isMatching && !isReview && !canEditPairs && (
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
