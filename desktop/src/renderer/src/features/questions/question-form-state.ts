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
 *    so `serializeMatches` is the only place pairs become wire text.
 *  - neither type's stored answer can be read back: `QuestionAnswerView` has no
 *    `correctOrder` / `matchesJson`. Authoring is one-way on purpose; the
 *    review copy in `lib/format.ts` says so and nothing here fakes it.
 */

import { QUESTION_FIELD_LIMITS } from '../../lib/format';
import type { OptionInput } from './question-form-rules';

/**
 * One authoring pair. Exactly the two keys the server's grader compares —
 * never add a third (an `id`, a `sortOrder`, a note) without renaming this
 * shape, because the extra key travels into `matches` and silently breaks
 * grading for every answer to the question.
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

/* ─────────────── MATCHING serialization ─────────────── */

/**
 * `matches` as the server wants it: a JSON array of `{"left":…,"right":…}`.
 *
 * The object literal is written key-first on purpose — `JSON.stringify` emits
 * the keys in insertion order, and a pair built from a spread of user state
 * could grow a stray key. Trimming happens here too, because the grader
 * compares the strings exactly and a trailing space is a different string.
 */
export function serializeMatches(pairs: readonly MatchPair[]): string {
  return JSON.stringify(
    pairs.map((pair) => ({
      left: pair.left.trim(),
      right: pair.right.trim(),
    }))
  );
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
 * MATCHING rules: at least one pair, both sides non-blank (the server 400s an
 * empty `matches`, and a blank side is a pair nobody can match), and the
 * serialized text inside `maxLength: 4000`.
 */
export function validateMatchingAuthoring(
  pairs: readonly MatchPair[]
): string[] {
  const errors: string[] = [];

  if (pairs.length === 0) {
    errors.push('配对题至少需要 1 组配对。');
  }

  pairs.forEach((pair, index) => {
    if (pair.left.trim() === '') {
      errors.push(`第 ${index + 1} 组配对缺少左侧内容。`);
    }
    if (pair.right.trim() === '') {
      errors.push(`第 ${index + 1} 组配对缺少右侧内容。`);
    }
  });

  const serialized = serializeMatches(pairs);
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
