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
  matchingOptions,
  moveInSequence,
  pairingAfterLeftAdd,
  pairingAfterLeftRemove,
  pairingAfterRightRemove,
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
  type MatchingDraft,
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

/* ─────────────── MATCHING columns, keys and pairs ─────────────── */

const cities: MatchingDraft = {
  left: ['北京', '巴黎'],
  right: ['法国', '中国'],
  pairs: [1, 0],
};

describe('serializeMatches writes the key grammar the answer side emits', () => {
  it('pairs option keys, never the item text', () => {
    // The columns become options L1.., R1.. (left half then right half), and
    // `QuestionRenderer` answers with those option keys. A `matches` holding
    // the texts could never equal the learner's payload.
    expect(serializeMatches(cities)).toEqual(
      '[{"left":"L1","right":"R2"},{"left":"L2","right":"R1"}]'
    );
    const parsed = JSON.parse(serializeMatches(cities)) as Array<Record<string, string>>;
    for (const pair of parsed) {
      expect(Object.keys(pair).sort()).toEqual(['left', 'right']);
    }
  });

  it('emits an empty array only when nothing is paired, and skips unpaired rows', () => {
    expect(serializeMatches({ left: ['北京'], right: ['中国'], pairs: [-1] })).toBe('[]');
    expect(
      serializeMatches({ left: ['北京', '巴黎'], right: ['中国', '法国'], pairs: [1, -1] })
    ).toBe('[{"left":"L1","right":"R2"}]');
    // A pairing that points past the column (a deleted right item) cannot name
    // a key that does not exist on the wire.
    expect(
      serializeMatches({ left: ['北京'], right: ['中国'], pairs: [7] })
    ).toBe('[]');
  });

  it('builds the option list the answer side splits back into the same columns', () => {
    const options = matchingOptions(cities);
    expect(options).toEqual([
      { optionKey: 'L1', content: '北京' },
      { optionKey: 'L2', content: '巴黎' },
      { optionKey: 'R1', content: '法国' },
      { optionKey: 'R2', content: '中国' },
    ]);
    // The contract has no side field, so `splitMatchingOptions` cuts the flat
    // list at ceil(n / 2). With 4 options that is index 2 — the left column.
    const mid = Math.ceil(options.length / 2);
    expect(options.slice(0, mid).map((o) => o.optionKey)).toEqual(['L1', 'L2']);
    expect(options.slice(mid).map((o) => o.optionKey)).toEqual(['R1', 'R2']);
  });

  it('trims the column content it stores as option text', () => {
    const padded: MatchingDraft = { left: [' 北京 '], right: [' 中国 '], pairs: [0] };
    // Content is trimmed at the wire edge for the ORDERING/choice lists; for
    // MATCHING the columns go through `matchingOptions` untouched, so the
    // pairing keys — not the text — are what the grader compares.
    expect(serializeMatches(padded)).toEqual('[{"left":"L1","right":"R1"}]');
    expect(validateMatchingAuthoring(padded)).toEqual([]);
  });

  it('renumbers the pairs when a right item is deleted', () => {
    // Pairs [2,0,1] read as: left 1 → right 2, left 2 → right 0,
    // left 3 → right 1. Deleting right index 1 therefore un-pairs LEFT 3 (it
    // pointed exactly there) and pulls left 1 from the old right 2 down to
    // right 1, while left 2 is untouched because 0 < 1.
    expect(pairingAfterRightRemove([2, 0, 1], 1)).toEqual([1, 0, -1]);
    expect(pairingAfterLeftRemove([2, 0, 1], 0)).toEqual([0, 1]);
    expect(pairingAfterLeftAdd([2, 0])).toEqual([2, 0, -1]);
  });
});

describe('validateMatchingAuthoring', () => {
  it('accepts a complete pairing of two equal columns', () => {
    expect(validateMatchingAuthoring(cities)).toEqual([]);
  });

  it('requires at least one item on each side', () => {
    expect(validateMatchingAuthoring({ left: [], right: [], pairs: [] })).toContain(
      '配对题至少需要 1 个左侧条目与 1 个右侧条目。'
    );
    expect(
      validateMatchingAuthoring({ left: ['北京'], right: [], pairs: [-1] })
    ).toContain('配对题至少需要 1 个右侧条目。');
  });

  it('refuses columns the answer widget would split wrong', () => {
    // 1 left + 3 right: ceil(4/2) = 2, so the learner would be shown two left
    // items — one of them the author meant as a right item.
    const errors = validateMatchingAuthoring({
      left: ['北京'],
      right: ['中国', '法国', '德国'],
      pairs: [0],
    });
    expect(
      errors.find((error) => error.startsWith('左右两列必须一样多'))
    ).toContain('左 1 个、右 3 个');
  });

  it('refuses a left item with no standard pair', () => {
    expect(
      validateMatchingAuthoring({ left: ['北京'], right: ['中国'], pairs: [-1] })
    ).toContain('「北京」还没有标准配对，请为它选择一个右侧条目。');
  });

  it('names a blank column entry', () => {
    const errors = validateMatchingAuthoring({
      left: ['北京', '  '],
      right: ['中国', '法国'],
      pairs: [0, 1],
    });
    expect(errors).toContain('第 2 个左侧条目缺少内容。');
  });

  it('caps the serialized text at CreateQuestionRequest.maxLength 4000', () => {
    // The pairs are option keys, so the only way to run into `matches`'
    // maxLength is sheer pair count: ~26 characters each, so past ~150 pairs
    // the document is over the cap even though every entry is legal on its own.
    const count = 170;
    const draft: MatchingDraft = {
      left: Array.from({ length: count }, (_, index) => `左${index}`),
      right: Array.from({ length: count }, (_, index) => `右${index}`),
      pairs: Array.from({ length: count }, (_, index) => index),
    };
    const serialized = serializeMatches(draft);
    expect(serialized.length).toBeGreaterThan(QUESTION_FIELD_LIMITS.matches);

    const errors = validateMatchingAuthoring(draft);
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain(
      `配对提交文本共 ${serialized.length} 个字符，超过 CreateQuestionRequest.matches 的 ${QUESTION_FIELD_LIMITS.matches} 个上限`
    );
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
