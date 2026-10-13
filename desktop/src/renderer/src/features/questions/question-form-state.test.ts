/**
 * ORDERING / MATCHING authoring state (G02).
 *
 * The wire format is the whole point of these two types, so the assertions
 * below are about what leaves the browser, not what is on screen:
 *
 *  - `correctOrder` is a 1-based permutation of the item list. The server's
 *    `AnswerPayloadView.orderingAnswer` items carry `minimum: 1` and
 *    `QuestionAnswerEvaluator.gradeOrdering` (QuestionAnswerEvaluator.java:148)
 *    compares lists for exact equality, so a single 0 makes the question
 *    permanently unanswerable — the sequence helpers and their validation are
 *    the only thing between a click and that outcome.
 *  - `matches` is a string of `{"left":…,"right":…}` objects.
 *    `gradeMatching` (lines 169-176) compares WHOLE maps, so a stray key is a
 *    silent 100%-wrong question, and the pair text is compared exactly, so the
 *    serializer has to trim.
 *  - the limits are the contract's, and `UpdateQuestionRequest` is a different
 *    set from the create one — pinned against openapi.json here, the same way
 *    `question-type-contract.test.ts` pins `QUESTION_FIELD_LIMITS`.
 */

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { QUESTION_FIELD_LIMITS } from '../../lib/format';
import {
  initialOrderingSequence,
  moveInSequence,
  placeAtEnd,
  serializeMatches,
  sequenceAfterAdd,
  sequenceAfterRemove,
  toCorrectOrder,
  unplacedItemIndexes,
  unplaceAtPosition,
  UPDATE_QUESTION_FIELD_LIMITS,
  validateMatchingAuthoring,
  validateOrderingAuthoring,
  validateQuestionEdit,
  type MatchPair,
} from './question-form-state';

const CONTRACT_PATH = join(
  process.cwd(),
  '..',
  'packages',
  'api-client',
  'src',
  'generated',
  'openapi.json'
);
const hasContract = existsSync(CONTRACT_PATH);

const items = (count: number) =>
  Array.from({ length: count }, (_, index) => ({
    optionKey: String.fromCharCode(65 + index),
    content: `第 ${index + 1} 步`,
  }));

/* ─────────────── ORDERING sequence helpers ─────────────── */

describe('ordering sequence keeps one index space with the answer side', () => {
  it('emits 1-based positions, so the standard order of three items is [3,1,2]', () => {
    // Indexes [2,0,1] mean: item 3 first, then item 1, then item 2. This is the
    // codec javadoc's own example (AnswerDataCodec.java:23) for three items.
    expect(toCorrectOrder([2, 0, 1])).toEqual([3, 1, 2]);
    expect(toCorrectOrder(initialOrderingSequence(3))).toEqual([1, 2, 3]);
  });

  it('never produces a 0, because orderingAnswer items have minimum 1', () => {
    for (const sequence of [
      initialOrderingSequence(4),
      moveInSequence(initialOrderingSequence(4), 3, -1),
      placeAtEnd(unplaceAtPosition(initialOrderingSequence(4), 0), 0),
      sequenceAfterRemove(initialOrderingSequence(4), 1),
    ]) {
      expect(toCorrectOrder(sequence).every((position) => position >= 1)).toBe(true);
    }
  });

  it('moves an entry with a neighbour swap and refuses to leave the list', () => {
    const base = initialOrderingSequence(3);
    expect(moveInSequence(base, 2, -1)).toEqual([0, 2, 1]);
    expect(moveInSequence(base, 0, -1)).toEqual(base);
    expect(moveInSequence(base, 2, 1)).toEqual(base);
  });

  it('adds a new item at the end of the order', () => {
    expect(sequenceAfterAdd([0, 1], 2)).toEqual([0, 1, 2]);
  });

  it('renumbers the order when an item is deleted, so no position dangles', () => {
    // Order [2,0,1] over items A,B,C: deleting B leaves the order pointing at
    // A and C, and C's index has moved from 2 to 1.
    expect(sequenceAfterRemove([2, 0, 1], 1)).toEqual([1, 0]);
    expect(toCorrectOrder(sequenceAfterRemove([2, 0, 1], 1))).toEqual([2, 1]);
  });

  it('reports which items are still missing from the order', () => {
    expect(unplacedItemIndexes([2, 0], 4)).toEqual([1, 3]);
    expect(placeAtEnd([2, 0], 1)).toEqual([2, 0, 1]);
    // A double click must not place the same item twice.
    expect(placeAtEnd([2, 0, 1], 1)).toEqual([2, 0, 1]);
  });
});

/* ─────────────── ORDERING validation ─────────────── */

describe('validateOrderingAuthoring', () => {
  it('accepts a complete permutation', () => {
    expect(validateOrderingAuthoring(items(3), [2, 0, 1])).toEqual([]);
  });

  it('requires at least two items', () => {
    expect(validateOrderingAuthoring([items(1)[0]], [0])).toContain(
      '排序题至少需要 2 个条目。'
    );
  });

  it('requires every item to be placed exactly once', () => {
    const errors = validateOrderingAuthoring(items(3), [0, 1]);
    expect(errors).toContain('标准顺序里还缺少 1 个条目：第 3 步。');

    // A repeated index and one that points past the list are both refused:
    // either way `correctOrder` would not describe the items the learner sees.
    expect(validateOrderingAuthoring(items(3), [0, 0, 2])).toContain(
      '标准顺序里每个条目只能出现一次，且必须是上面已存在的条目。请重新排列。'
    );
    expect(validateOrderingAuthoring(items(3), [0, 1, 5])).toContain(
      '标准顺序里每个条目只能出现一次，且必须是上面已存在的条目。请重新排列。'
    );
  });

  it('rejects an empty item or a duplicated key, which the create endpoint 400s', () => {
    const errors = validateOrderingAuthoring(
      [
        { optionKey: 'A', content: '  ' },
        { optionKey: 'A', content: '第二步' },
      ],
      [0, 1]
    );
    expect(errors).toContain('第 1 个条目缺少内容。');
    expect(errors).toContain('条目标识不能重复：A。');
  });

  it('caps the order at CreateQuestionRequest.correctOrder maxItems', () => {
    const many = items(QUESTION_FIELD_LIMITS.correctOrder + 1);
    const errors = validateOrderingAuthoring(
      many,
      initialOrderingSequence(many.length)
    );
    expect(
      errors.some((error) => error.includes('排序题最多 100 个条目'))
    ).toBe(true);
  });
});

/* ─────────────── MATCHING ─────────────── */

describe('serializeMatches writes the shape the grader compares', () => {
  it('emits exactly the left/right keys and trims both sides', () => {
    const pairs: MatchPair[] = [
      { left: ' 北京 ', right: ' 中国 ' },
      { left: '上海', right: '直辖市' },
    ];
    expect(serializeMatches(pairs)).toEqual(
      '[{"left":"北京","right":"中国"},{"left":"上海","right":"直辖市"}]'
    );

    // The server parses this into List<Map<String,String>> and compares the
    // maps themselves, so the key set of every pair has to be exactly
    // left/right — an extra key would never equal the learner's pair.
    const parsed = JSON.parse(serializeMatches(pairs)) as Array<Record<string, string>>;
    expect(parsed.map((pair) => Object.keys(pair).sort())).toEqual([
      ['left', 'right'],
      ['left', 'right'],
    ]);
  });

  it('stays parseable JSON for the empty and awkward cases', () => {
    expect(JSON.parse(serializeMatches([]))).toEqual([]);
    const tricky: MatchPair[] = [{ left: 'a"b', right: 'c\\d' }];
    expect(JSON.parse(serializeMatches(tricky))).toEqual([
      { left: 'a"b', right: 'c\\d' },
    ]);
  });
});

describe('validateMatchingAuthoring', () => {
  it('requires at least one pair', () => {
    expect(validateMatchingAuthoring([])).toContain('配对题至少需要 1 组配对。');
  });

  it('names the pair and side that is blank', () => {
    const errors = validateMatchingAuthoring([
      { left: '北京', right: '  ' },
      { left: '', right: '中国' },
    ]);
    expect(errors).toContain('第 1 组配对缺少右侧内容。');
    expect(errors).toContain('第 2 组配对缺少左侧内容。');
  });

  it('caps the serialized text at CreateQuestionRequest.maxLength 4000', () => {
    // 100-character sides, 25 pairs: the pair count is legal, the TEXT is what
    // the contract caps, so the message has to name the serialized length.
    const pairs: MatchPair[] = Array.from({ length: 25 }, (_, index) => ({
      left: '左'.repeat(100),
      right: `右-${index}-`.repeat(10),
    }));
    const serialized = serializeMatches(pairs);
    expect(serialized.length).toBeGreaterThan(QUESTION_FIELD_LIMITS.matches);

    const errors = validateMatchingAuthoring(pairs);
    // The message quotes the real serialized length, because the pair count is
    // legal here — only the text is too long.
    expect(
      errors.find((error) =>
        error.includes('超过 CreateQuestionRequest.matches 的 4000 个上限')
      )
    ).toContain(`${serialized.length} 个字符`);
    // The pairs themselves are all filled in, so no per-pair message is mixed
    // into a length problem — the user would go looking for blanks that are not
    // there.
    expect(errors).toHaveLength(1);
  });
});

/* ─────────────── edit form ─────────────── */

describe('validateQuestionEdit', () => {
  it('requires a stem, which PUT does not check for us', () => {
    expect(
      validateQuestionEdit({ stem: '   ', explanation: '', difficulty: '' })
    ).toContain('题干不能为空。');
    expect(
      validateQuestionEdit({ stem: '水是什么', explanation: '', difficulty: '' })
    ).toEqual([]);
  });

  it('applies the update DTO caps, which differ from the create ones', () => {
    const errors = validateQuestionEdit({
      stem: 'x'.repeat(UPDATE_QUESTION_FIELD_LIMITS.stem + 1),
      explanation: 'y'.repeat(UPDATE_QUESTION_FIELD_LIMITS.explanation + 1),
      difficulty: 'HARD',
    });
    expect(
      errors.some((error) => error.includes('题干最多 5000 个字符'))
    ).toBe(true);
    expect(
      errors.some((error) => error.includes('解析最多 2000 个字符'))
    ).toBe(true);
  });
});

/* ─────────────── contract pins ─────────────── */

describe.skipIf(!hasContract)('limits come from openapi.json, not from a guess', () => {
  const contract = JSON.parse(readFileSync(CONTRACT_PATH, 'utf8')) as {
    components: {
      schemas: Record<
        string,
        {
          properties?: Record<
            string,
            {
              type?: string;
              maxLength?: number;
              maxItems?: number;
              items?: { minimum?: number };
            }
          >;
        }
      >;
    };
  };
  const create = contract.components.schemas.CreateQuestionRequest.properties ?? {};
  const update = contract.components.schemas.UpdateQuestionRequest.properties ?? {};
  const payload = contract.components.schemas.AnswerPayloadView.properties ?? {};

  it('matches UpdateQuestionRequest exactly', () => {
    expect(Object.keys(update).sort()).toEqual([
      'difficulty',
      'explanation',
      'stem',
    ]);
    expect(UPDATE_QUESTION_FIELD_LIMITS.stem).toBe(update.stem?.maxLength);
    expect(UPDATE_QUESTION_FIELD_LIMITS.explanation).toBe(
      update.explanation?.maxLength
    );
    expect(UPDATE_QUESTION_FIELD_LIMITS.difficulty).toBe(update.difficulty?.maxLength);
  });

  it('is narrower on explanation than the create form, so the two stay apart', () => {
    expect(UPDATE_QUESTION_FIELD_LIMITS.explanation).toBeLessThan(
      QUESTION_FIELD_LIMITS.explanation
    );
  });

  it('keeps ordering positions 1-based and matches a string', () => {
    expect(payload.orderingAnswer?.items?.minimum).toBe(1);
    expect(create.correctOrder?.maxItems).toBe(QUESTION_FIELD_LIMITS.correctOrder);
    expect(create.matches?.maxLength).toBe(QUESTION_FIELD_LIMITS.matches);
    // `matches` is a string on the wire, not an array: the server parses it.
    expect(create.matches?.type).toBe('string');
    expect(create.correctOrder?.type).toBe('array');
  });
});
