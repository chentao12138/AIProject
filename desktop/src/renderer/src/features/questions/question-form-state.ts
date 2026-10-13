/**
 * ORDERING / MATCHING authoring state (G02, C09b).
 *
 * Split out of `QuestionForm.tsx` for the same reason `question-form-rules.ts`
 * exists: the component file must keep exporting one component, and these
 * rules are decisions the backend makes, so they belong where they can be
 * tested without clicking.
 *
 * What the contract actually allows (verified against openapi.json and
 * `QuestionService.java`):
 *
 *  - `CreateQuestionRequest.correctOrder` is an integer array, `maxItems: 100`.
 *    `AnswerPayloadView.orderingAnswer` items carry `minimum: 1`, and
 *    `QuestionAnswerEvaluator.gradeOrdering` (QuestionAnswerEvaluator.java:139-150)
 *    decides correctness by exact list equality. So the author and the learner
 *    share one index space: **1-based option positions, never 0**. A `0` in
 *    `correctOrder` would make the question permanently unanswerable.
 *  - the position is the option's place in the list as submitted:
 *    `insertOptions()` (QuestionService.java:286-303) fills `sortOrder` with the
 *    array index and `QuestionOptionMapper.selectByQuestionId` reads them back
 *    `ORDER BY sort_order ASC, id ASC`, so item `i` (0-based) is position `i + 1`.
 *  - `CreateQuestionRequest.matches` is a **string** (`maxLength: 4000`) that the
 *    server stores verbatim as `matchesJson` (AnswerDataCodec.java:76-82) and
 *    grades by whole-map set equality
 *    (`QuestionAnswerEvaluator.gradeMatching`, lines 152-181). A pair carrying
 *    any key besides `left` / `right` therefore never equals the learner's pair,
 *    so `serializeMatches` is the only place pairs become wire text — and the
 *    values in it are OPTION KEYS, because that is what the answer side puts in
 *    `matchingAnswer` (see {@link MatchingDraft}).
 *  - neither type's stored answer can be read back: `QuestionAnswerView` has no
 *    `correctOrder` / `matchesJson`. Authoring is one-way on purpose; the
 *    review copy in `lib/format.ts` says so and nothing here fakes it.
 */

import { QUESTION_FIELD_LIMITS } from '../../lib/format';
import type { OptionInput } from './question-form-rules';

/**
 * One pair as it travels on the wire. Exactly the two keys the server's grader
 * compares — never add a third (an `id`, a `sortOrder`, a note), because the
 * extra key lands in `matchesJson` and silently breaks grading for every
 * answer to the question. The values are option keys, not item text.
 */
export interface MatchPair {
  left: string;
  right: string;
}

/**
 * `UpdateQuestionRequest` caps, copied out of openapi.json. They are NOT the
 * create caps: update allows a 5000-character `stem` but only a 2000-character
 * `explanation` (`CreateQuestionRequest.explanation` allows 4000), so an edit
 * form that reused `QUESTION_FIELD_LIMITS.explanation` would accept text the
 * server rejects. `question-form-state.test.ts` pins these three numbers
 * against the contract for the same reason `question-type-contract.test.ts`
 * pins the create limits.
 */
export const UPDATE_QUESTION_FIELD_LIMITS = {
  stem: 5000,
  explanation: 2000,
  difficulty: 32,
} as const;

/** The editable subset of a stored question — the whole of `PUT /questions/{id}`. */
export interface QuestionEditValues {
  stem: string;
  explanation: string;
  difficulty: string;
}

/* ─────────────── ORDERING sequence ─────────────── */

/**
 * The standard order, held as 0-based indexes into the item list.
 *
 * Indexes rather than positions on purpose: an index follows the item when the
 * author removes another item or reorders, and `toCorrectOrder` is the single
 * place that turns it into the 1-based number the contract expects.
 */
export function initialOrderingSequence(itemCount: number): number[] {
  return Array.from({ length: itemCount }, (_, index) => index);
}

/** Adding an item appends it to the standard order, so the order stays complete. */
export function sequenceAfterAdd(sequence: number[], nextItemIndex: number): number[] {
  return [...sequence, nextItemIndex];
}

/**
 * Removing item `removedIndex` drops it from the order and renumbers the items
 * after it — the item list shifted, so the positions the order points at did
 * too. Skipping this would leave the sequence pointing at an index that no
 * longer exists, and `correctOrder` would then name the wrong item.
 */
export function sequenceAfterRemove(
  sequence: number[],
  removedIndex: number
): number[] {
  return sequence
    .filter((index) => index !== removedIndex)
    .map((index) => (index > removedIndex ? index - 1 : index));
}

/** Swap a placed item with its neighbour. Buttons, so it works from the keyboard. */
export function moveInSequence(
  sequence: number[],
  position: number,
  delta: -1 | 1
): number[] {
  const target = position + delta;
  if (position < 0 || position >= sequence.length) {
    return sequence;
  }
  if (target < 0 || target >= sequence.length) {
    return sequence;
  }
  const next = [...sequence];
  const moved = next[position];
  next[position] = next[target];
  next[target] = moved;
  return next;
}

/** Take one entry out of the standard order; the item itself still exists. */
export function unplaceAtPosition(sequence: number[], position: number): number[] {
  return sequence.filter((_, index) => index !== position);
}

/** Put an item at the end of the standard order, ignoring one already placed. */
export function placeAtEnd(sequence: number[], itemIndex: number): number[] {
  if (sequence.includes(itemIndex)) {
    return sequence;
  }
  return [...sequence, itemIndex];
}

/** Items that are entered but not yet part of the standard order. */
export function unplacedItemIndexes(sequence: number[], itemCount: number): number[] {
  const placed = new Set(sequence);
  const missing: number[] = [];
  for (let index = 0; index < itemCount; index += 1) {
    if (!placed.has(index)) {
      missing.push(index);
    }
  }
  return missing;
}

/** The `correctOrder` wire value: 1-based positions, in the standard order. */
export function toCorrectOrder(sequence: number[]): number[] {
  return sequence.map((index) => index + 1);
}

/* ─────────────── MATCHING columns, keys and pairs ─────────────── */

/**
 * The authoring model for a MATCHING question.
 *
 * NOT a list of `{left,right}` texts. `QuestionRenderer` builds the learner's
 * answer out of the question's OPTIONS
 * (`components/answer-grammar.ts:73-78` + `QuestionRenderer.tsx:193-221`):
 * `splitMatchingOptions` takes the first half of the option list as the left
 * column, and the pair it serializes into `matchingAnswer` is
 * `{left: leftOptionKey, right: rightOptionKey}`. `gradeMatching`
 * (QuestionAnswerEvaluator.java:169-176) then compares that set of maps with
 * the stored `matchesJson` for equality. So the pairs an author writes only
 * grade correctly when their `left` / `right` values are the very option keys
 * the answer side will emit — free text would be a pair nobody can produce.
 *
 * Hence: two columns of content, plus the standard pairing as
 * leftIndex → rightIndex, and everything on the wire derived from those.
 */
export interface MatchingDraft {
  /** Left column, in the order the learner will see it. */
  left: string[];
  /** Right column, in the order the learner will see it. */
  right: string[];
  /**
   * `pairs[i]` is the index into `right` that item `left[i]` belongs to, or -1
   * when it is not paired yet. Indexes rather than keys so that removing a
   * right item can renumber them in one place.
   */
  pairs: number[];
}

/** Option keys that stay readable in the review list and never collide. */
export function matchingLeftKey(index: number): string {
  return `L${index + 1}`;
}

export function matchingRightKey(index: number): string {
  return `R${index + 1}`;
}

/**
 * The option list the question is stored with: the whole left column first,
 * then the whole right column. That is what makes `splitMatchingOptions` cut
 * the list back into the same two columns — see {@link matchingColumnProblem}.
 */
export function matchingOptions(draft: MatchingDraft): OptionInput[] {
  return [
    ...draft.left.map((content, index) => ({
      optionKey: matchingLeftKey(index),
      content,
    })),
    ...draft.right.map((content, index) => ({
      optionKey: matchingRightKey(index),
      content,
    })),
  ];
}

/** A new left item starts unpaired; a new right item joins nothing yet. */
export function emptyMatchingDraft(): MatchingDraft {
  return { left: ['', ''], right: ['', ''], pairs: [-1, -1] };
}

export function pairingAfterLeftAdd(pairs: readonly number[]): number[] {
  return [...pairs, -1];
}

/**
 * Removing a left item removes its pairing row. The right indexes inside the
 * other rows are untouched — they point into `right`, which did not change.
 */
export function pairingAfterLeftRemove(
  pairs: readonly number[],
  removedIndex: number
): number[] {
  return pairs.filter((_, left) => left !== removedIndex);
}

/**
 * Deleting a right item removes it from every pair that pointed at it and
 * shifts the ones after it down — otherwise a pair would silently name the
 * neighbour of the item the author meant.
 */
export function pairingAfterRightRemove(
  pairs: readonly number[],
  removedIndex: number
): number[] {
  return pairs.map((right) => {
    if (right === removedIndex) {
      return -1;
    }
    return right > removedIndex ? right - 1 : right;
  });
}

/**
 * `matches` as the server wants it: a JSON array whose objects have exactly
 * `left` and `right`, holding the option keys of the paired items.
 *
 * The object literal is written key-first on purpose — `JSON.stringify` emits
 * keys in insertion order, and a pair assembled from spread state could grow a
 * stray key, which `gradeMatching` can never match. Unpaired left items are
 * skipped here and refused by {@link validateMatchingAuthoring}, so a body that
 * reaches the wire is always the complete key.
 */
export function serializeMatches(draft: MatchingDraft): string {
  const pairs: MatchPair[] = [];
  draft.left.forEach((_, leftIndex) => {
    const rightIndex = draft.pairs[leftIndex] ?? -1;
    if (rightIndex < 0 || rightIndex >= draft.right.length) {
      return;
    }
    pairs.push({
      left: matchingLeftKey(leftIndex),
      right: matchingRightKey(rightIndex),
    });
  });
  return JSON.stringify(pairs);
}

/** The pair a review of this draft would show: `北京 → 中国`, by content. */
export function matchingPreviewRows(
  draft: MatchingDraft
): Array<{ left: string; right: string; paired: boolean }> {
  return draft.left.map((left, index) => {
    const rightIndex = draft.pairs[index] ?? -1;
    const paired = rightIndex >= 0 && rightIndex < draft.right.length;
    return {
      left: left.trim() === '' ? `左侧 ${index + 1}` : left.trim(),
      right: paired ? (draft.right[rightIndex] ?? '').trim() : '（未配对）',
      paired,
    };
  });
}

/**
 * Why the two columns must be the same size: the answer side splits a flat
 * option list at `Math.ceil(length / 2)` and calls the front the left column.
 * With `left` longer than `right` by more than one the cut lands inside what
 * this form considered the left column, and the learner is shown a different
 * question than the author paired.
 */
export function matchingColumnProblem(draft: MatchingDraft): string | null {
  const total = draft.left.length + draft.right.length;
  if (total === 0) {
    return '配对题至少需要 1 个左侧条目与 1 个右侧条目。';
  }
  if (draft.left.length === 0) {
    return '配对题至少需要 1 个左侧条目。';
  }
  if (draft.right.length === 0) {
    return '配对题至少需要 1 个右侧条目。';
  }
  if (Math.ceil(total / 2) !== draft.left.length) {
    return `左右两列必须一样多（当前左 ${draft.left.length} 个、右 ${draft.right.length} 个）：答题控件按「前一半是左列」切分选项（answer-grammar.ts:73-78），数量不齐时学员看到的列与你排的就不是同一份。`;
  }
  return null;
}

/* ─────────────── validation ─────────────── */

const ORDERING_MIN_ITEMS = 2;

/**
 * ORDERING rules: at least {@link ORDERING_MIN_ITEMS} items, every item
 * well-formed (the same `QuestionOptionInput` limits the choice types get),
 * and a standard order that is a permutation of the whole item list with no
 * zero and no repeat, inside `correctOrder`'s `maxItems`.
 */
export function validateOrderingAuthoring(
  items: readonly OptionInput[],
  sequence: readonly number[]
): string[] {
  const errors: string[] = [];

  if (items.length < ORDERING_MIN_ITEMS) {
    errors.push(`排序题至少需要 ${ORDERING_MIN_ITEMS} 个条目。`);
  }
  if (items.length > QUESTION_FIELD_LIMITS.correctOrder) {
    errors.push(
      `排序题最多 ${QUESTION_FIELD_LIMITS.correctOrder} 个条目（CreateQuestionRequest.correctOrder 的 maxItems）。`
    );
  }

  const keys: string[] = [];
  items.forEach((item, index) => {
    const key = item.optionKey.trim();
    const content = item.content.trim();
    if (key === '') {
      errors.push(`第 ${index + 1} 个条目缺少标识。`);
    } else if (key.length > QUESTION_FIELD_LIMITS.optionKey) {
      errors.push(
        `条目标识「${key}」超过 ${QUESTION_FIELD_LIMITS.optionKey} 个字符。`
      );
    } else if (keys.includes(key)) {
      errors.push(`条目标识不能重复：${key}。`);
    } else {
      keys.push(key);
    }
    if (content === '') {
      errors.push(`第 ${index + 1} 个条目缺少内容。`);
    } else if (content.length > QUESTION_FIELD_LIMITS.optionContent) {
      errors.push(
        `条目「${key || index + 1}」内容超过 ${QUESTION_FIELD_LIMITS.optionContent} 个字符。`
      );
    }
  });

  if (sequence.length > QUESTION_FIELD_LIMITS.correctOrder) {
    errors.push(
      `标准顺序最多 ${QUESTION_FIELD_LIMITS.correctOrder} 项（CreateQuestionRequest.correctOrder 的 maxItems）。`
    );
  }

  const inRange = sequence.every(
    (index) => Number.isInteger(index) && index >= 0 && index < items.length
  );
  const unique = new Set(sequence).size === sequence.length;
  if (!inRange || !unique) {
    errors.push(
      '标准顺序里每个条目只能出现一次，且必须是上面已存在的条目。请重新排列。'
    );
  } else if (sequence.length !== items.length) {
    const labels = unplacedItemIndexes(sequence as number[], items.length).map(
      (index) => items[index].content.trim() || items[index].optionKey.trim() || `第 ${index + 1} 项`
    );
    errors.push(`标准顺序里还缺少 ${labels.length} 个条目：${labels.join('、')}。`);
  } else {
    const positions = toCorrectOrder(sequence as number[]);
    if (positions.some((value) => !Number.isInteger(value) || value < 1)) {
      errors.push(
        '标准顺序必须是从 1 开始的编号（服务端 orderingAnswer 的 items.minimum 是 1），不能出现 0。'
      );
    }
  }

  return errors;
}

/**
 * MATCHING rules, all of them consequences of how the answer side and the
 * grader read this question:
 *
 *  - both columns non-empty and equally long ({@link matchingColumnProblem});
 *  - every left item paired, because an unpaired left item is a row the
 *    learner can answer but the stored key cannot match;
 *  - every content non-blank and inside `QuestionOptionInput.content`
 *    maxLength, because the columns ARE the option rows on the wire;
 *  - the serialized `matches` inside its own maxLength 4000.
 */
export function validateMatchingAuthoring(draft: MatchingDraft): string[] {
  const errors: string[] = [];

  const columnProblem = matchingColumnProblem(draft);
  if (columnProblem) {
    errors.push(columnProblem);
  }

  const columns: Array<[side: string, values: string[]]> = [
    ['左侧', draft.left],
    ['右侧', draft.right],
  ];
  for (const [side, values] of columns) {
    values.forEach((value, index) => {
      const content = value.trim();
      if (content === '') {
        errors.push(`第 ${index + 1} 个${side}条目缺少内容。`);
      } else if (content.length > QUESTION_FIELD_LIMITS.optionContent) {
        errors.push(
          `${side}条目「${content.slice(0, 12)}…」超过 ${QUESTION_FIELD_LIMITS.optionContent} 个字符。`
        );
      }
    });
  }

  draft.left.forEach((left, index) => {
    const rightIndex = draft.pairs[index] ?? -1;
    if (rightIndex < 0 || rightIndex >= draft.right.length) {
      const label = left.trim() === '' ? `左侧 ${index + 1}` : left.trim();
      errors.push(`「${label}」还没有标准配对，请为它选择一个右侧条目。`);
    }
  });

  const serialized = serializeMatches(draft);
  if (serialized.length > QUESTION_FIELD_LIMITS.matches) {
    errors.push(
      `配对提交文本共 ${serialized.length} 个字符，超过 CreateQuestionRequest.matches 的 ${QUESTION_FIELD_LIMITS.matches} 个上限。请精简条目内容或减少配对数量。`
    );
  }

  return errors;
}

/**
 * The edit form's rules. The server does NOT enforce them:
 * `UpdateQuestionRequest.stem` has no `@NotBlank` (CreateQuestionRequest.java:44
 * vs UpdateQuestionRequest.java:16-21), and `QuestionService.update()` writes
 * whatever it is given, so a blank stem would be accepted and stored. The size
 * caps come from the update DTO, which is narrower than create on `explanation`.
 */
export function validateQuestionEdit(values: QuestionEditValues): string[] {
  const errors: string[] = [];

  if (values.stem.trim() === '') {
    errors.push('题干不能为空。');
  }
  if (values.stem.length > UPDATE_QUESTION_FIELD_LIMITS.stem) {
    errors.push(
      `题干最多 ${UPDATE_QUESTION_FIELD_LIMITS.stem} 个字符（UpdateQuestionRequest.stem 的 maxLength）。`
    );
  }
  if (values.explanation.length > UPDATE_QUESTION_FIELD_LIMITS.explanation) {
    errors.push(
      `解析最多 ${UPDATE_QUESTION_FIELD_LIMITS.explanation} 个字符（UpdateQuestionRequest 的上限比创建时的 ${QUESTION_FIELD_LIMITS.explanation} 更严）。`
    );
  }

  return errors;
}
